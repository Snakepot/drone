package cn.tedu.droneagent.mcp;

import cn.tedu.droneagent.tool.Tool;
import cn.tedu.droneagent.tool.ToolContext;
import cn.tedu.droneagent.tool.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 把一个**远程 MCP 工具**包装成本地 {@link Tool}。
 *
 * <h2>为什么需要这层适配</h2>
 * 本地工具直接实现 {@code Tool}；远程工具的行为在另一台服务器上，通过 JSON-RPC 调用。
 * 编排层不应该知道「这个工具是本地的还是远程的」——它只按名字取 {@code Tool} 然后 {@code invoke}。
 *
 * <h2>身份怎么过去</h2>
 * 当该服务器配置了 {@code forward-authorization=true}（例如 `drone-mcp-server`），
 * 这里会把 {@link ToolContext#token()} 以 `Authorization: Bearer …` 的形式加到**本次调用**的请求头。
 * 没带身份就**直接失败、不发请求**——绝不能出现「服务匿名替用户操作」的路径。
 *
 * <h2>异常为什么必须在这里吞掉</h2>
 * {@link Tool} 的契约是「失败也要返回 {@code ToolResult.failed}，不要抛异常」。
 * 远程调用可能超时、断网、返回坏 JSON；任何一种若抛出去，都会让整个对话 500。
 *
 * <h2>日志怎么写</h2>
 * 一进一出、带工具名与耗时、标明执行线程；参数只记长度，**不记原文，也绝不记 Token**。
 */
@Slf4j
public class McpToolAdapter implements Tool {

    /** 暴露给模型的名字：{@code 别名__远程名} */
    private final String exposedName;

    /** 远程服务器上的原名 */
    private final String remoteName;

    private final String serverAlias;
    private final McpClient client;

    /** 是否把调用方的 JWT 转发给该 MCP 服务器 */
    private final boolean forwardAuthorization;

    /** 是否打印原始入参与原始结果；排障开关，生产建议关闭 */
    private final boolean logRawPayload;

    public McpToolAdapter(String exposedName, String remoteName, String serverAlias,
                          McpClient client, boolean forwardAuthorization, boolean logRawPayload) {
        this.exposedName = exposedName;
        this.remoteName = remoteName;
        this.serverAlias = serverAlias;
        this.client = client;
        this.forwardAuthorization = forwardAuthorization;
        this.logRawPayload = logRawPayload;
    }

    @Override
    public String name() {
        return exposedName;
    }

    @Override
    public ToolResult invoke(String argumentsJson) {
        // 没有身份上下文：对需要转发的服务器会失败关闭（见 invoke(String, ToolContext)）
        return invoke(argumentsJson, ToolContext.anonymous());
    }

    @Override
    public ToolResult invoke(String argumentsJson, ToolContext context) {
        Map<String, String> headers = new LinkedHashMap<>();
        // traceId 与是否转发身份无关：高德等第三方服务也可以带上，便于本地日志对齐（REQ-AGENT-0026）
        if (context != null && context.traceId() != null && !context.traceId().isBlank()) {
            headers.put("X-Trace-Id", context.traceId());
        }
        // 写操作 id：续跑/重试复用同一个，MCP 幂等层据此去重（REQ-AGENT-0029）
        if (context != null && context.operationId() != null && !context.operationId().isBlank()) {
            headers.put("X-Operation-Id", context.operationId());
        }
        if (forwardAuthorization) {
            if (context == null || !context.hasToken()) {
                log.warn("【MCP】拒绝调用：tool={}，原因=该服务器要求转发用户身份，但当前请求没有 Token",
                        exposedName);
                return ToolResult.failed("MCP 工具「" + exposedName + "」需要用户身份，"
                        + "但当前请求没有携带登录凭证，请重新登录后再试。");
            }
            headers.put("Authorization", "Bearer " + context.token());
        }
        return doInvoke(argumentsJson, headers);
    }

    private ToolResult doInvoke(String argumentsJson, Map<String, String> headers) {
        long start = System.nanoTime();
        int argumentLength = argumentsJson == null ? 0 : argumentsJson.length();
        log.info("【MCP】调用远程工具：tool={}，server={}，remote={}，参数 {} 字符，线程={}",
                exposedName, serverAlias, remoteName, argumentLength, Thread.currentThread().getName());
        if (logRawPayload) {
            log.info("【MCP-RAW】工具入参：tool={}，arguments={}", exposedName, argumentsJson);
        }

        try {
            JsonNode result = client.callTool(remoteName, argumentsJson, headers);
            boolean isError = result.path("isError").asBoolean(false);
            String text = flatten(result.path("content"));
            long cost = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

            if (isError) {
                if (logRawPayload) {
                    log.info("【MCP-RAW】工具结果（isError=true）：tool={}，content={}", exposedName, text);
                }
                log.warn("【MCP】远程工具返回业务错误：tool={}，耗时={}ms，错误内容 {} 字符；"
                                + "将作为失败结果回填给模型，由模型向用户解释",
                        exposedName, cost, text.length());
                return ToolResult.failed(text.isBlank()
                        ? "MCP 工具「" + exposedName + "」返回了错误，但没有给出原因"
                        : text);
            }

            log.info("【MCP】远程工具返回：tool={}，耗时={}ms，结果 {} 字符",
                    exposedName, cost, text.length());
            if (logRawPayload) {
                log.info("【MCP-RAW】工具结果：tool={}，content={}", exposedName, text);
            }
            return ToolResult.ok(text);
        } catch (RuntimeException e) {
            long cost = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            log.warn("【MCP】远程工具调用失败：tool={}，耗时={}ms，原因={}；"
                            + "本轮按失败回填给模型，不做重试（重试由编排层的轮次预算统一控制）",
                    exposedName, cost, e.getMessage());
            return ToolResult.failed("MCP 工具「" + exposedName + "」调用失败：" + e.getMessage());
        }
    }

    /**
     * 把 MCP 的 {@code content[]} 摊平成一段文本。
     *
     * <p>文本项直接拼接；图片/资源等非文本项留一个占位标记——
     * 模型读不了二进制，但需要知道「这里还有别的东西」，否则会以为结果不完整。
     */
    private static String flatten(JsonNode content) {
        if (content == null || !content.isArray() || content.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (JsonNode item : content) {
            if (out.length() > 0) {
                out.append('\n');
            }
            String type = item.path("type").asText("");
            if ("text".equals(type)) {
                out.append(item.path("text").asText(""));
            } else {
                out.append("[非文本结果：").append(type.isBlank() ? "unknown" : type).append("]");
            }
        }
        return out.toString().trim();
    }
}
