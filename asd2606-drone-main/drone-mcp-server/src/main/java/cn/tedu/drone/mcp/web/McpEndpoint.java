package cn.tedu.drone.mcp.web;

import cn.tedu.drone.mcp.catalog.ToolCatalogLoader;
import cn.tedu.drone.mcp.config.McpServerProperties;
import cn.tedu.drone.mcp.catalog.ToolSpec;
import cn.tedu.drone.mcp.invoke.ToolCallResult;
import cn.tedu.drone.mcp.invoke.ToolInvoker;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * MCP 的 JSON-RPC 端点。
 *
 * <h2>只实现四个方法（够客户端用）</h2>
 * <pre>
 * initialize              → 协商版本与能力
 * notifications/initialized → 202 空体（通知没有 JSON-RPC 响应）
 * tools/list              → 工具目录
 * tools/call              → 执行并转发给 drone-server
 * </pre>
 *
 * <h2>错误分两层</h2>
 * <ul>
 *   <li><b>协议错误</b>（JSON 坏、方法不存在、params 不是对象）→ JSON-RPC 的 {@code error}；</li>
 *   <li><b>业务结果</b>（无权限、登录失效、上游业务失败）→ 正常的 {@code result} 里
 *       {@code isError:true} + 可读文本。模型需要读得懂失败原因，而不是看到一个传输层异常。</li>
 * </ul>
 *
 * <h2>身份从哪来</h2>
 * 每次 {@code tools/call} 从 HTTP 头取 {@code Authorization}，由 {@link ToolInvoker} 原样转发。
 * 本端点**不做鉴权判定**——判定在 {@code drone-server}，这里只保证「没有身份就不发请求」。
 */
@Slf4j
@RestController
public class McpEndpoint {

    /** 与 drone-agent 客户端协商的版本保持一致 */
    private static final String PROTOCOL_VERSION = "2025-03-26";

    private final ToolCatalogLoader catalogLoader;
    private final ToolInvoker invoker;
    private final ObjectMapper objectMapper;
    private final boolean logRawPayload;

    public McpEndpoint(ToolCatalogLoader catalogLoader, ToolInvoker invoker,
                       ObjectMapper objectMapper, McpServerProperties properties) {
        this.catalogLoader = catalogLoader;
        this.invoker = invoker;
        this.objectMapper = objectMapper;
        this.logRawPayload = properties.logRawPayload();
    }

    /** 兼容既有测试/调用：不带 operationId */
    public ResponseEntity<String> handle(String body, String authorization, String traceId) {
        return handle(body, authorization, traceId, null);
    }

    @PostMapping(value = "/mcp", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> handle(@RequestBody(required = false) String body,
                                         @RequestHeader(value = "Authorization", required = false) String authorization,
                                         @RequestHeader(value = "X-Trace-Id", required = false) String traceId,
                                         @RequestHeader(value = "X-Operation-Id", required = false) String operationId) {
        if (logRawPayload) {
            log.info("【MCP-RAW】MCP 请求体 ← traceId={}，operationId={}，body={}",
                    traceId, operationId, body);
        }
        ResponseEntity<String> response = doHandle(body, authorization, traceId, operationId);
        if (logRawPayload) {
            log.info("【MCP-RAW】MCP 响应体 → HTTP {}，body={}",
                    response.getStatusCode().value(), response.getBody());
        }
        return response;
    }

    private ResponseEntity<String> doHandle(String body, String authorization, String traceId,
                                            String operationId) {
        if (body == null || body.isBlank()) {
            return rpcError(null, -32700, "Parse error");
        }

        JsonNode rpc;
        try {
            rpc = objectMapper.readTree(body);
        } catch (Exception e) {
            return rpcError(null, -32700, "Parse error");
        }
        if (rpc.isArray()) {
            // 不做批量：客户端只发单条，批量会显著增加错误处理面
            return rpcError(null, -32600, "Batch requests are not supported");
        }

        String method = rpc.path("method").asText("");
        JsonNode id = rpc.get("id");

        // 通知：没有 id，服务端不回 JSON-RPC 响应（HTTP 202 + 空体）
        if (method.startsWith("notifications/")) {
            log.debug("【MCP-Server】收到通知：{}", method);
            return ResponseEntity.status(202).build();
        }
        if (method.isBlank()) {
            return rpcError(id, -32600, "Invalid Request: missing method");
        }

        return switch (method) {
            case "initialize" -> rpcResult(id, initializeResult());
            case "ping" -> rpcResult(id, objectMapper.createObjectNode());
            case "tools/list" -> rpcResult(id, listTools());
            case "tools/call" -> handleCall(id, rpc.path("params"), authorization, traceId, operationId);
            default -> {
                log.warn("【MCP-Server】未知方法：{}", method);
                yield rpcError(id, -32601, "Method not found: " + method);
            }
        };
    }

    /** 健康检查：给部署平台与联调用，不暴露工具细节 */
    @GetMapping(value = "/health", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UP");
        body.put("tools", catalogLoader.current().size());
        body.put("fingerprint", catalogLoader.current().fingerprint());
        return body;
    }

    private ResponseEntity<String> handleCall(JsonNode id, JsonNode params, String authorization,
                                              String traceId, String operationId) {
        if (!params.isObject()) {
            return rpcError(id, -32602, "Invalid params");
        }
        String name = params.path("name").asText("");
        ToolSpec spec = catalogLoader.current().find(name);
        if (spec == null) {
            // 未知工具属于「模型用错了名字」，给可读文本比协议错误更有用
            return toolResult(id, ToolCallResult.error("未知工具：" + name));
        }
        JsonNode arguments = params.path("arguments");
        ToolCallResult result = invoker.call(spec, arguments, authorization, traceId, operationId);
        return toolResult(id, result);
    }

    private ObjectNode initializeResult() {
        ObjectNode result = objectMapper.createObjectNode();
        result.put("protocolVersion", PROTOCOL_VERSION);
        result.putObject("capabilities").putObject("tools");
        ObjectNode serverInfo = result.putObject("serverInfo");
        serverInfo.put("name", "drone-mcp-server");
        serverInfo.put("version", "0.0.1");
        return result;
    }

    private ObjectNode listTools() {
        ObjectNode result = objectMapper.createObjectNode();
        ArrayNode tools = result.putArray("tools");
        for (ToolSpec spec : catalogLoader.current().tools()) {
            ObjectNode tool = tools.addObject();
            tool.put("name", spec.name());
            tool.put("description", spec.description());
            tool.set("inputSchema", spec.inputSchema());
            tool.putObject("annotations").put("readOnlyHint", spec.readOnly());
        }
        return result;
    }

    private ResponseEntity<String> toolResult(JsonNode id, ToolCallResult callResult) {
        ObjectNode result = objectMapper.createObjectNode();
        ArrayNode content = result.putArray("content");
        content.addObject().put("type", "text").put("text", callResult.text());
        result.put("isError", callResult.isError());
        return rpcResult(id, result);
    }

    private ResponseEntity<String> rpcResult(JsonNode id, JsonNode result) {
        ObjectNode message = objectMapper.createObjectNode();
        message.put("jsonrpc", "2.0");
        message.set("id", id == null ? objectMapper.nullNode() : id);
        message.set("result", result);
        return ResponseEntity.ok(message.toString());
    }

    private ResponseEntity<String> rpcError(JsonNode id, int code, String message) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", id == null ? objectMapper.nullNode() : id);
        ObjectNode error = response.putObject("error");
        error.put("code", code);
        error.put("message", message);
        return ResponseEntity.ok(response.toString());
    }
}
