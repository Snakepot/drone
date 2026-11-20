package cn.tedu.droneagent.context;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 一次模型调用的「上下文账单」：消息按类别汇总，外加工具定义这一行。
 *
 * <h2>为什么工具定义不在消息里，却要进账单</h2>
 * 它确实不是对话内容（没有 role、不参与生成），但它是**每次请求都重发的固定成本**。
 * 实测它占请求体的 88%——账单如果把它漏掉，就等于只统计了零头。
 *
 * <h2>总量的组成</h2>
 * <pre>
 *   合计 = 模板固定开销(22) + 所有消息 + 工具定义
 * </pre>
 * 模板开销来自模型服务的对话模板，不属于任何一条消息，所以单列。
 *
 * <h2>它不负责什么</h2>
 * <ul>
 *   <li><b>不拦截</b>：它只算账，不因为超预算就改消息（预算策略是后续需求）；</li>
 *   <li><b>不打正文</b>：日志只输出数量与 token，避免把用户数据写进常规日志。</li>
 * </ul>
 */
public record ContextBill(List<ContextMessage> messages, int toolsTokens, int toolCount) {

    public ContextBill {
        messages = List.copyOf(messages);
    }

    /** 不含工具定义的「消息部分」合计 */
    public int messageTokens() {
        int total = 0;
        for (ContextMessage message : messages) {
            total += message.tokens();
        }
        return total;
    }

    /** 本次请求的估算合计 */
    public int totalTokens() {
        return TokenEstimator.TEMPLATE_TOKENS + messageTokens() + toolsTokens;
    }

    /** 按类别汇总；顺序固定（按枚举声明顺序），便于日志横向对比 */
    public Map<MessageKind, Bucket> byKind() {
        Map<MessageKind, Bucket> buckets = new EnumMap<>(MessageKind.class);
        for (MessageKind kind : MessageKind.values()) {
            buckets.put(kind, new Bucket(0, 0));
        }
        for (ContextMessage message : messages) {
            Bucket old = buckets.get(message.kind());
            buckets.put(message.kind(), new Bucket(old.count() + 1, old.tokens() + message.tokens()));
        }
        return buckets;
    }

    /** 某个类别下的工具名与条数（工具结果专用），用于回答「贵在哪个工具」 */
    public Map<String, Integer> toolResultCounts() {
        Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        for (ContextMessage message : messages) {
            if (message.kind() == MessageKind.TOOL_RESULT) {
                String name = message.toolName() == null ? "（未知工具）" : message.toolName();
                counts.merge(name, 1, Integer::sum);
            }
        }
        return counts;
    }

    /**
     * 渲染成日志片段，例如：
     * <pre>模板 22 / 系统提示词 1856×1 / 长期记忆 2104×8 / 当前会话 987×12 / 工具定义 5621×73</pre>
     * 数量为 0 的类别不出现。
     */
    public String describe() {
        StringBuilder out = new StringBuilder("模板 ").append(TokenEstimator.TEMPLATE_TOKENS);
        for (Map.Entry<MessageKind, Bucket> entry : byKind().entrySet()) {
            Bucket bucket = entry.getValue();
            if (bucket.count() == 0) {
                continue;
            }
            out.append(" / ").append(entry.getKey().label())
                    .append(' ').append(bucket.tokens())
                    .append('×').append(bucket.count());
        }
        if (toolCount > 0) {
            out.append(" / 工具定义 ").append(toolsTokens).append('×').append(toolCount);
        }
        return out.toString();
    }

    /** 一个类别的条数与 token */
    public record Bucket(int count, int tokens) {
    }
}
