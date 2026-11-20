package cn.tedu.drone.task.ws;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * 处理飞手端的 WebSocket 连接与消息。
 *
 * <h2>这个 handler 负责三件事</h2>
 * <ol>
 *   <li><b>连上时登记</b>：把这条连接记进 {@link TaskWsSessionRegistry}，
 *       这样业务侧才知道「管子在哪」</li>
 *   <li><b>断开时注销</b>：人走了要把连接从表里删掉，否则会往一个死连接上推</li>
 *   <li><b>回应心跳</b>：客户端定期发 ping，这里回 pong</li>
 * </ol>
 *
 * <h2>为什么不在这里做业务</h2>
 * 注意推送的<b>发起方是业务代码</b>（派发任务时调 registry），
 * 不是这个 handler。这个 handler 只处理「连接的生命周期 + 客户端发来的消息」，
 * 它不主动查数据库、也不做派发。
 * 这样职责清楚：业务逻辑留在 Service 里，传输层只管送信。
 *
 * <h2>消息格式</h2>
 * 约定请求是 JSON：{@code {"type":"ping"}} 之外的类型目前都不处理（回一条 unknown）。
 * 真正的业务通知是「服务端 → 客户端」单向推的，客户端不需要发什么。
 */
@Slf4j
@Component
public class TaskWsHandler extends TextWebSocketHandler {

    private static final String TYPE_PING = "ping";

    private final TaskWsSessionRegistry registry;

    public TaskWsHandler(TaskWsSessionRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        Long userId = currentUserId(session);
        if (userId == null) {
            // 走到这里说明握手拦截器放行了但属性没放上 —— 属于代码 bug，直接关掉更安全
            log.error("WebSocket 连接缺少用户身份，主动关闭，sessionId={}", session.getId());
            registry.closeQuietly(session, CloseStatus.POLICY_VIOLATION);
            return;
        }

        registry.register(userId, session);
        // 回一条 ready，让客户端知道「连上了」而不是「还在连」
        send(session, "{\"type\":\"ready\"}");
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        registry.unregister(currentUserId(session), session);
        log.debug("WebSocket 连接结束，sessionId={}，status={}", session.getId(), status);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        String payload = message.getPayload();

        // 心跳：客户端长时间没消息时定期发 ping，服务端回 pong。
        // 有了它，中间的网络设备（NAT、网关）不会把「长时间没动静」的连接当成死连接掐掉
        if (payload != null && payload.contains(TYPE_PING)) {
            send(session, "{\"type\":\"pong\"}");
            return;
        }

        log.debug("收到未处理的 WebSocket 消息，sessionId={}，payload={}", session.getId(), payload);
        send(session, "{\"type\":\"unknown\"}");
    }

    /**
     * 传输层出错（比如对方网络断了）。
     *
     * <p>这里只记日志并关连接：注销动作由 {@link #afterConnectionClosed} 统一负责，
     * 不用在这里重复写一遍。
     */
    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.warn("WebSocket 传输异常，sessionId={}，原因={}", session.getId(), exception.getMessage());
        registry.closeQuietly(session, CloseStatus.SERVER_ERROR);
    }

    /** 从 session 属性里取用户 id —— 这是握手拦截器校验通过后放进去的 */
    private Long currentUserId(WebSocketSession session) {
        Object value = session.getAttributes().get(TaskWsHandshakeInterceptor.ATTR_USER_ID);
        return (value instanceof Long id) ? id : null;
    }

    private void send(WebSocketSession session, String payload) {
        try {
            if (session.isOpen()) {
                session.sendMessage(new TextMessage(payload));
            }
        } catch (Exception e) {
            log.debug("WebSocket 回包失败（可忽略）：{}", e.getMessage());
        }
    }
}
