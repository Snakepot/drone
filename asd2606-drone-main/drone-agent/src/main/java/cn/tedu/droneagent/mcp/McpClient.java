package cn.tedu.droneagent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * MCP 的**协议层**：在传输之上实现本服务用到的四个 RPC。
 *
 * <h2>一次完整的 MCP 会话（2025-03-26 版本）</h2>
 * <pre>
 * 客户端                                          服务器
 *   |-- initialize (protocolVersion/capabilities) -->|
 *   |&lt;-- result{protocolVersion, capabilities} ----|   （响应头可能带 Mcp-Session-Id）
 *   |-- notifications/initialized ---------------->|   （通知，回 202 空体）
 *   |-- tools/list ------------------------------->|
 *   |&lt;-- result{tools:[...], nextCursor?} --------|   （cursor 非空则翻页）
 *   |-- tools/call {name, arguments} ------------->|
 *   |&lt;-- result{content:[...], isError} ----------|
 * </pre>
 *
 * <h2>为什么 initialize 失败不算致命</h2>
 * 2026-07-28 版规范**移除了握手**，服务器可以不认识 {@code initialize}。
 * 实测高德认识并回 2025-03-26；但为了兼容更新的服务器，这里把 initialize 当**尽力而为**：
 * 失败只记 WARN，继续直接 {@code tools/list}。
 *
 * <h2>它不负责什么</h2>
 * <ul>
 *   <li><b>不解析工具结果语义</b>：{@code tools/call} 原样返回 result，由适配器解释。</li>
 *   <li><b>不管权限与重试</b>：那是编排层与 {@code ToolGovernance} 的事。</li>
 * </ul>
 */
@Slf4j
public class McpClient {

    /** 客户端声明支持的最高版本；高德会回它自己的版本（实测 2025-03-26） */
    private static final String CLIENT_PROTOCOL_VERSION = "2025-03-26";

    /** tools/list 的翻页上限：防止服务端给出循环 cursor 把启动拖死 */
    private static final int MAX_PAGES = 10;

    private final String alias;
    private final McpTransport transport;
    private final ObjectMapper objectMapper;
    private final AtomicInteger idSequence = new AtomicInteger();

    private volatile String protocolVersion = "unknown";

    public McpClient(String alias, McpTransport transport, ObjectMapper objectMapper) {
        this.alias = alias;
        this.transport = transport;
        this.objectMapper = objectMapper;
    }

    /**
     * 尽力完成握手。
     *
     * @return 协商到的协议版本；握手失败时返回 {@code "unknown"}，但**不抛异常**
     */
    public String initialize() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("protocolVersion", CLIENT_PROTOCOL_VERSION);
        params.set("capabilities", objectMapper.createObjectNode());
        ObjectNode clientInfo = objectMapper.createObjectNode();
        clientInfo.put("name", "drone-agent");
        clientInfo.put("version", "1.0.0");
        params.set("clientInfo", clientInfo);

        try {
            JsonNode response = transport.request(request("initialize", params));
            protocolVersion = response.path("result").path("protocolVersion").asText("unknown");
            log.info("【MCP】initialize 完成：server={}，协商版本={}，会话={}",
                    alias, protocolVersion,
                    transport.sessionId() == null ? "无（按无状态处理）" : "已建立");
            transport.notify(notification("notifications/initialized"));
            return protocolVersion;
        } catch (RuntimeException e) {
            // 新版规范没有握手；握手失败不代表服务器不可用，继续尝试 tools/list
            log.warn("【MCP】initialize 未成功，按无状态服务器继续：server={}，原因={}", alias, e.getMessage());
            return protocolVersion;
        }
    }

    /**
     * 列出服务器暴露的全部工具（自动翻页）。
     *
     * <p>服务端没给 {@code tools} 字段时按空列表处理，不报错——
     * 这通常是「服务器在但没配任何工具」，属于可降级情形。
     */
    public List<McpToolInfo> listTools() {
        List<McpToolInfo> tools = new ArrayList<>();
        String cursor = null;
        for (int page = 1; page <= MAX_PAGES; page++) {
            ObjectNode params = objectMapper.createObjectNode();
            if (cursor != null && !cursor.isBlank()) {
                params.put("cursor", cursor);
            }
            JsonNode result = transport.request(request("tools/list", params)).path("result");
            JsonNode array = result.path("tools");
            if (array.isArray()) {
                for (JsonNode tool : array) {
                    McpToolInfo info = toInfo(tool);
                    if (info != null) {
                        tools.add(info);
                    }
                }
            }
            String next = result.path("nextCursor").asText(null);
            if (next == null || next.isBlank()) {
                break;
            }
            cursor = next;
        }
        log.debug("【MCP】tools/list 完成：server={}，共 {} 个原始工具", alias, tools.size());
        return tools;
    }

    /**
     * 调用一个远程工具。
     *
     * @param remoteName    远程原名（不带别名前缀）
     * @param argumentsJson 模型给的参数 JSON 字符串
     * @return 服务器返回的 {@code result} 节点（通常是 {@code {content, isError}}）
     */
    public JsonNode callTool(String remoteName, String argumentsJson) {
        return callTool(remoteName, argumentsJson, java.util.Map.of());
    }

    /**
     * 调用一个远程工具，并附加本次调用的请求头（如用户身份）。
     *
     * <p>工具调用发生在执行器线程上，身份必须由调用方显式传入，
     * 不能依赖 ThreadLocal——这与 {@code ToolContext} 的约定一致。
     *
     * @param remoteName    远程原名（不带别名前缀）
     * @param argumentsJson 模型给的参数 JSON 字符串
     * @param headers       本次调用的额外请求头
     * @return 服务器返回的 {@code result} 节点（通常是 {@code {content, isError}}）
     */
    public JsonNode callTool(String remoteName, String argumentsJson,
                             java.util.Map<String, String> headers) {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("name", remoteName);
        params.set("arguments", parseArguments(argumentsJson));

        JsonNode response = transport.request(request("tools/call", params), headers);
        return response.path("result");
    }

    public String protocolVersion() {
        return protocolVersion;
    }

    /** 模型偶尔会传空串或半截 JSON；空串按空对象处理，坏 JSON 让它抛给适配器兜底 */
    private JsonNode parseArguments(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(argumentsJson);
        } catch (Exception e) {
            throw new McpException("工具参数不是合法 JSON：" + e.getMessage(), e);
        }
    }

    private McpToolInfo toInfo(JsonNode tool) {
        String name = tool.path("name").asText("");
        if (name.isBlank()) {
            log.warn("【MCP】忽略一个没有名字的远程工具：server={}", alias);
            return null;
        }
        JsonNode schema = tool.path("inputSchema");
        String schemaJson = "{\"type\":\"object\"}";
        if (schema.isObject()) {
            try {
                schemaJson = objectMapper.writeValueAsString(schema);
            } catch (Exception e) {
                log.warn("【MCP】工具 {} 的 Schema 无法序列化，改用空对象：{}", name, e.getMessage());
            }
        }
        // annotations.readOnlyHint 缺省按只读处理：MCP 约定查询类工具应显式声明写操作
        boolean readOnly = tool.path("annotations").path("readOnlyHint").asBoolean(true);
        return new McpToolInfo(name, tool.path("description").asText(""), schemaJson, readOnly);
    }

    private JsonNode request(String method, JsonNode params) {
        ObjectNode rpc = objectMapper.createObjectNode();
        rpc.put("jsonrpc", "2.0");
        rpc.put("id", idSequence.incrementAndGet());
        rpc.put("method", method);
        rpc.set("params", params == null ? objectMapper.createObjectNode() : params);
        return rpc;
    }

    private JsonNode notification(String method) {
        ObjectNode rpc = objectMapper.createObjectNode();
        rpc.put("jsonrpc", "2.0");
        rpc.put("method", method);
        return rpc;
    }
}
