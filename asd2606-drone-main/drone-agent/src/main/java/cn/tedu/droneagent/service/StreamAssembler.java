package cn.tedu.droneagent.service;

import cn.tedu.droneagent.context.TokenUsage;
import cn.tedu.droneagent.tool.ToolCall;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 流式响应组装器：把一段段 SSE `delta` 拼成一次完整的模型回合。
 *
 * <h2>流式里的三种增量</h2>
 * <ol>
 *   <li>{@code reasoning_content}：深度思考的文字，边想边推；</li>
 *   <li>{@code content}：最终答复的文字，边生成边推；</li>
 *   <li>{@code tool_calls}：**按 index 分片**到达——
 *       第一片带 id 与函数名，参数是一段段 JSON 字符串。</li>
 * </ol>
 * 这里只做「拼接与计时」，不做网络与回调；因此可以用固定报文完全离线单测。
 *
 * <h2>为什么按 index 而不是按 id 合并</h2>
 * 后续分片可能不再带 id，只带 index；按 id 合并会丢参数。
 */
public final class StreamAssembler {

    private final StringBuilder content = new StringBuilder();
    private final StringBuilder reasoning = new StringBuilder();
    private final Map<Integer, CallBuilder> calls = new TreeMap<>();

    /** 首个正文增量出现时的相对耗时（纳秒）；没出现正文则保持 -1 */
    private long firstContentNanos = -1;

    /**
     * 模型返回的真实用量。**只在带了 {@code stream_options.include_usage} 时才有**，
     * 且出现在最后一帧（那一帧通常 {@code choices} 为空）。
     */
    private TokenUsage usage;

    /**
     * 吃一条 SSE data 负载里的 {@code delta}。
     *
     * @param root         解析后的 JSON
     * @param elapsedNanos 从请求发出到现在的耗时
     * @return 本次增量（可能为空串）
     */
    public Increment accept(JsonNode root, long elapsedNanos) {
        // 用量帧没有 delta，先认它；否则下面按 choices 取 delta 会得到空串，白跑一趟
        JsonNode usageNode = root.path("usage");
        if (usageNode.has("prompt_tokens")) {
            int cached = usageNode.has("prompt_cache_hit_tokens")
                    ? usageNode.path("prompt_cache_hit_tokens").asInt(0)
                    : usageNode.path("prompt_tokens_details").path("cached_tokens").asInt(0);
            usage = new TokenUsage(usageNode.path("prompt_tokens").asInt(0), cached);
        }

        JsonNode delta = root.path("choices").path(0).path("delta");
        String reasoningDelta = delta.path("reasoning_content").asText("");
        String contentDelta = delta.path("content").asText("");

        if (!reasoningDelta.isEmpty()) {
            reasoning.append(reasoningDelta);
        }
        if (!contentDelta.isEmpty()) {
            if (firstContentNanos < 0) {
                firstContentNanos = Math.max(0, elapsedNanos);
            }
            content.append(contentDelta);
        }

        JsonNode toolCalls = delta.path("tool_calls");
        if (toolCalls.isArray()) {
            for (JsonNode item : toolCalls) {
                int index = item.path("index").asInt(0);
                CallBuilder builder = calls.computeIfAbsent(index, key -> new CallBuilder());
                if (item.hasNonNull("id")) {
                    builder.id = item.path("id").asText("");
                }
                JsonNode function = item.path("function");
                if (function.hasNonNull("name")) {
                    builder.name.append(function.path("name").asText(""));
                }
                if (function.hasNonNull("arguments")) {
                    builder.arguments.append(function.path("arguments").asText(""));
                }
            }
        }
        return new Increment(reasoningDelta, contentDelta);
    }

    /** 模型给的完整正文；没有则返回 null（与 {@code ModelTurn} 的约定一致） */
    public String content() {
        return content.isEmpty() ? null : content.toString();
    }

    /** 完整的思考过程；没有则返回 null */
    public String reasoning() {
        return reasoning.isEmpty() ? null : reasoning.toString();
    }

    /** 拼接完成的工具调用；没有则空列表 */
    public List<ToolCall> toolCalls() {
        List<ToolCall> result = new ArrayList<>(calls.size());
        for (CallBuilder builder : calls.values()) {
            result.add(new ToolCall(builder.id, builder.name.toString(), builder.arguments.toString()));
        }
        return result;
    }

    /** 首个正文增量距请求发出的毫秒数；没有正文为 0 */
    public long reasoningMs() {
        return firstContentNanos < 0 ? 0 : Math.max(0, firstContentNanos / 1_000_000);
    }

    /** 模型返回的真实用量；流里没有 usage 帧时为 {@code null}（不能编一个） */
    public TokenUsage usage() {
        return usage;
    }

    /** 一条增量：两个字符串都可能为空 */
    public record Increment(String reasoningDelta, String contentDelta) {
    }

    private static final class CallBuilder {
        private String id = "";
        private final StringBuilder name = new StringBuilder();
        private final StringBuilder arguments = new StringBuilder();
    }
}
