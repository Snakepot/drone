package cn.tedu.droneagent.dto;

/**
 * 对话历史里的一条消息。
 *
 * <p>{@code role} 只允许 {@code user} 与 {@code assistant}，由服务端校验，
 * 见 {@code ChatService#normalizeHistory}。{@code system} 不在其列 ——
 * 助手人设只能由服务端的系统提示词决定，不能被客户端塞进来。
 */
public record ChatMessage(String role, String content) {
}
