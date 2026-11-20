package cn.tedu.droneagent.tool;

import cn.tedu.droneagent.dto.ChatMessage;

import java.util.List;

/**
 * 发给 / 来自模型的**一条线格式消息**，是 OpenAI 兼容协议的超集。
 *
 * <h2>为什么不用 {@link ChatMessage}</h2>
 * {@link ChatMessage} 只有 {@code role + content}，表达得了「用户说」「助手答」，
 * 但表达不了工具调用这条链上的两种消息：
 * <ul>
 *   <li>助手**请求调用工具**：要带 {@code tool_calls}，正文可能是空的；</li>
 *   <li>**工具执行结果**：角色是 {@code tool}，要带 {@code tool_call_id} 与正文。</li>
 * </ul>
 * 把这两个字段塞进 {@link ChatMessage} 会污染会话记忆（那里只该有 user/assistant），
 * 所以另立一个「线格式」类型，只在调模型这条路上用。
 *
 * <h2>四种消息怎么造</h2>
 * <ul>
 *   <li>{@link #of(ChatMessage)}：把记忆里的消息搬过来；</li>
 *   <li>{@link #assistantToolCalls(List)}：助手请求调工具；</li>
 *   <li>{@link #tool(String, String)}：把工具结果回填给模型；</li>
 *   <li>{@link #user(String)} / {@link #assistant(String)}：普通一问一答。</li>
 * </ul>
 *
 * @param role      角色：{@code system} / {@code user} / {@code assistant} / {@code tool}
 * @param content   正文；助手请求调工具时可为 null
 * @param toolCallId 仅 {@code tool} 消息用：对应哪次 {@link ToolCall#id()}
 * @param toolCalls 仅助手消息用：它想调用哪些工具
 * @param reasoningContent 仅助手消息用：深度思考的思考过程，要在下次请求中原样带回
 */
public record ModelMessage(String role, String content, String toolCallId, List<ToolCall> toolCalls,
                           String reasoningContent) {

    public ModelMessage {
        toolCalls = toolCalls == null ? null : List.copyOf(toolCalls);
    }

    /** 兼容旧调用：没有 reasoning 的消息 */
    public ModelMessage(String role, String content, String toolCallId, List<ToolCall> toolCalls) {
        this(role, content, toolCallId, toolCalls, null);
    }

    /** 把记忆里的一条消息搬成线格式（只有 role/content） */
    public static ModelMessage of(ChatMessage message) {
        return new ModelMessage(message.role(), message.content(), null, null);
    }

    public static ModelMessage user(String content) {
        return new ModelMessage("user", content, null, null);
    }

    public static ModelMessage assistant(String content) {
        return new ModelMessage("assistant", content, null, null);
    }

    /** 助手请求调用工具：正文为空，带上一组 tool_calls */
    public static ModelMessage assistantToolCalls(List<ToolCall> toolCalls) {
        return new ModelMessage("assistant", null, null, toolCalls, null);
    }

    /**
     * 助手请求调用工具，并带上深度思考的 reasoning。
     *
     * <p>续跑（人工确认后）必须用这一版**原样回放**模型当初那条消息，
     * 否则模型服务会报 `reasoning_content must be passed back`。
     */
    public static ModelMessage assistantToolCalls(List<ToolCall> toolCalls, String reasoningContent) {
        return new ModelMessage("assistant", null, null, toolCalls, reasoningContent);
    }

    /** 把某次工具调用的结果回填给模型；toolCallId 必须与请求时一致 */
    public static ModelMessage tool(String toolCallId, String content) {
        return new ModelMessage("tool", content, toolCallId, null);
    }
}
