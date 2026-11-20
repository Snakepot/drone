package cn.tedu.droneagent.dto;

/**
 * 「待确认」信息：危险工具**没有被立即执行**，等用户确认（`REQ-AGENT-0006`）。
 *
 * <p>随 {@code /chat} 的响应一起回给前端；前端弹确认框，用户点确认后再把 {@link #pendingId}
 * 带回 {@code /chat/confirm}，才会真正执行。
 *
 * @param pendingId 票据 id，前端确认时**原样带回**
 * @param tool      要执行的工具名
 * @param summary   给人看的动作摘要（如「delete_device（参数 {"id":12}）」）
 */
public record PendingConfirmation(String pendingId, String tool, String summary) {
}
