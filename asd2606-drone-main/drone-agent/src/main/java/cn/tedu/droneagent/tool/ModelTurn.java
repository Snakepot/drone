package cn.tedu.droneagent.tool;

import java.util.List;

/**
 * 模型**一次**响应的结果：要么是一段正文，要么是「我想调这些工具」。
 *
 * <h2>它在链路里的位置，以及它不负责什么</h2>
 * <p><b>位置</b>：{@link cn.tedu.droneagent.service.ChatService#chatStep} 的返回值。
 * 编排层看 {@link #hasToolCalls()}：
 * 有就执行工具、把结果回填后再调一次；没有就把 {@link #content()} 当最终答复。
 *
 * <ul>
 *   <li><b>不知道工具怎么执行</b>：它只是「模型想要什么」。</li>
 *   <li><b>不代表对话结束</b>：有 {@code toolCalls} 时对话还要继续。</li>
 * </ul>
 *
 * <p>正常情况二选一：只要正文时 {@code content} 有值、{@code toolCalls} 为空；
 * 请求工具时 {@code content} 为 null、{@code toolCalls} 非空。两者都空属异常响应。
 *
 * @param content   正文；模型请求工具时为 null
 * @param toolCalls 模型想调用的工具；没有则为空列表（**不会是 null**）
 * @param reasoningContent 深度思考模式下的思考过程；没有开启时为 null。
 *        请求工具时必须**随 assistant 帧一起回传**，否则下一次调用会被模型服务拒绝
 *        （详见 REQ-AGENT-0006 确认续跑的修复记录）。
 * @param reasoningMs 流式场景下的「思考用时」：请求发出到首个正文增量的毫秒数；
 *        非流式或没有正文时为 0，仅供前端展示
 */
public record ModelTurn(String content, List<ToolCall> toolCalls, String reasoningContent,
                        long reasoningMs) {

    public ModelTurn {
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }

    /** 兼容旧调用：没有 reasoning 与用时的回合 */
    public ModelTurn(String content, List<ToolCall> toolCalls) {
        this(content, toolCalls, null, 0);
    }

    /** 兼容旧调用：有 reasoning、没有用时 */
    public ModelTurn(String content, List<ToolCall> toolCalls, String reasoningContent) {
        this(content, toolCalls, reasoningContent, 0);
    }

    /** 只有正文、没有工具调用的回合 */
    public static ModelTurn text(String content) {
        return new ModelTurn(content, List.of());
    }

    /** 模型是否要求调用工具 */
    public boolean hasToolCalls() {
        return !toolCalls.isEmpty();
    }
}
