package cn.tedu.droneagent.context;

/**
 * 模型返回的**真实**用量，用于和账单估算对账。
 *
 * <p>非流式响应在 {@code usage} 节点；流式要在请求里带
 * {@code stream_options.include_usage}，最后一帧才有这个节点。
 *
 * @param promptTokens 输入 token（含系统提示词、历史、工具定义）
 * @param cachedTokens 命中前缀缓存的 token 数；模型不返回时为 0
 */
public record TokenUsage(int promptTokens, int cachedTokens) {
}
