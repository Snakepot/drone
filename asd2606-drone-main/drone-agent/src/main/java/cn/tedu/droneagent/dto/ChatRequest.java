package cn.tedu.droneagent.dto;

/**
 * 对话请求。
 *
 * <h2>为什么不再接收 messages</h2>
 * 服务端自己持有会话（见 `REQ-AGENT-0003`），历史由服务端组装，
 * 客户端只报「哪个会话」和「这次说了什么」。这也从根上消除了
 * 「客户端伪造历史轮次」的可能——它根本没有地方可以塞。
 *
 * <p>`conversationId` 由前端生成，但**归属以服务端记录的 userId 为准**：
 * 服务端把它和 Token 里的 userId 拼成键，拿别人的会话 id 只会查到自己名下的空会话。
 *
 * @param conversationId 会话标识，由前端生成（字母数字下划线连字符，1~64 位）
 * @param message        用户这一轮说的话
 */
public record ChatRequest(String conversationId, String message,
                          cn.tedu.droneagent.cancel.CancellationReport.ResumeToken resumeToken) {
}
