package cn.tedu.droneagent.memory;

/**
 * 会话内压缩的「摘要器」（`REQ-AGENT-0020`）。
 *
 * <p>定义成函数式接口，是为了让 {@link ContextAssembler} **不直接依赖**
 * {@code ChatService}：记忆子系统只关心「给我一段文本，还我一段摘要」，
 * 具体用哪个模型、哪个提示词、超时多少，都是服务层的事。
 *
 * <p>实现方在失败时应当抛 {@link RuntimeException}——组装层会捕获并回退到
 * 「只保留最近若干条」的旧行为，绝不能因为摘要失败让用户请求失败。
 */
@FunctionalInterface
public interface ConversationCompactor {

    /**
     * 把一段对话转录压缩成摘要。
     *
     * @param transcript 早期对话（已格式化成「用户：… / 助手：…」）
     * @param maxChars   摘要长度上限
     * @return 摘要正文；空串视为失败
     */
    String compact(String transcript, int maxChars);
}
