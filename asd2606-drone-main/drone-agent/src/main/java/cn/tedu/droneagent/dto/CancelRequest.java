package cn.tedu.droneagent.dto;

/**
 * 取消一次在途请求（`REQ-AGENT-0029`）。
 *
 * <p>用 conversationId 而不是 traceId：traceId 要到流结束才回传，中途取消拿不到；
 * 会话锁保证同一会话至多一个在途请求。
 *
 * @param conversationId 要取消的会话
 */
public record CancelRequest(String conversationId) {
}
