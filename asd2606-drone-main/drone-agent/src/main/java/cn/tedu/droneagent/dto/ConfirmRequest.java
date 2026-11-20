package cn.tedu.droneagent.dto;

/**
 * 危险操作的**确认**请求（`REQ-AGENT-0006`）。
 *
 * @param conversationId 会话
 * @param pendingId      票据 id，来自 {@code /chat} 响应里的 {@code pending.pendingId}
 * @param approve        {@code true}=执行，{@code false}=拒绝；用包装类型便于校验「没传」
 */
public record ConfirmRequest(String conversationId, String pendingId, Boolean approve) {
}
