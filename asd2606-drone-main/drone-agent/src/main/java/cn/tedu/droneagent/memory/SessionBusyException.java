package cn.tedu.droneagent.memory;

/**
 * 同一会话上已有请求在处理，等不到锁。
 *
 * <p>单独一个异常类型，是为了让它映射到与「入参不合法」不同的状态码：
 * 这不是请求写错了，而是**时机不对、重发就行**。
 *
 * <h2>它在链路里的位置，以及它不负责什么</h2>
 * <p><b>位置</b>：由 {@link SessionLocks} 的等待超时触发，被
 * {@code ConversationService} 抛出、由 {@code GlobalExceptionHandler} 转成 HTTP 409。</p>
 *
 * <ul>
 *   <li><b>不重试</b>：它只是一次失败的通知；要不要重发由前端的用户决定。</li>
 *   <li><b>不判断是不是同一会话</b>：「谁和谁撞上了」是 {@link SessionLocks} 的事，
 *       它只带一句给人看的话。</li>
 * </ul>
 */
public class SessionBusyException extends RuntimeException {

    public SessionBusyException(String message) {
        super(message);
    }
}
