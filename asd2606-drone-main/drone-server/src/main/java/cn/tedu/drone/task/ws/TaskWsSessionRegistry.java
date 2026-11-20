package cn.tedu.drone.task.ws;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebSocket 会话注册表：记住「哪个用户在哪些连接上」，推送时靠它找人。
 *
 * <h2>为什么需要这张表</h2>
 * HTTP 是无状态的、一问一答；WebSocket 反过来 —— 连接建立之后，
 * 服务端手里握着一条「通往某个用户的管子」。要主动推消息，就得先知道管子在哪。
 * 这张表就是那本通讯录。
 *
 * <h2>为什么要用并发容器</h2>
 * 连接会随时建立与断开，而且是在 <b>WebSocket 自己的线程</b>里发生的，
 * 同时业务线程（比如管理员派发任务的请求线程）还在遍历这张表发消息。
 * 一边改一边遍历，用普通 HashMap 会直接崩，所以用 {@link ConcurrentHashMap}
 * 与并发集合。
 *
 * <h2>为什么一个用户对应<b>一组</b>连接</h2>
 * 同一个人可能同时开着手机和电脑，或者开着两个页面 —— 每条连接都要收到，
 * 所以是 Map&lt;用户id, Set&lt;连接&gt;&gt; 而不是一对一。
 *
 * <h2>单机版，多实例要换</h2>
 * 这张表只在<b>当前这个进程</b>里有效。部署多台机器时，连接可能挂在实例 B，
 * 而派发请求落在实例 A —— A 的表里没有那个人，消息就发不出去。
 * 届时要引入 Redis 发布订阅之类的广播总线做扇出，
 * 详见 {@code docs/任务通知.md} 的「多实例部署」一节。
 */
@Slf4j
@Component
public class TaskWsSessionRegistry {

    /**
     * 单条连接发送消息的超时（毫秒）。
     *
     * <p>客户端网络卡住时，{@code sendMessage} 可能长时间阻塞 ——
     * 而调用它的是<b>业务线程</b>（管理员点派发的那个请求线程）。
     * 不设上限的话，一个卡住的手机就能把派发接口拖慢。
     */
    private static final int SEND_TIME_LIMIT = 5000;

    /**
     * 单条连接允许积压的字节数。超过就说明这个客户端收不过来（或已经假死），
     * 由 Spring 直接关掉它，避免把服务端内存吃掉。
     */
    private static final int BUFFER_SIZE_LIMIT = 512 * 1024;

    /** 用户 id → 这个用户的全部连接 */
    private final Map<Long, Set<WebSocketSession>> sessions = new ConcurrentHashMap<>();

    /**
     * 登记一条新连接。
     *
     * <p>注意这里把原始 session 包了一层 {@link ConcurrentWebSocketSessionDecorator}：
     * <b>WebSocketSession 的 sendMessage 本身不是线程安全的</b>，
     * 两条线程同时往一条连接里写会导致消息交错甚至抛异常。
     * 包一层之后，Spring 会帮你把同一条连接上的发送串行化。
     */
    public void register(Long userId, WebSocketSession session) {
        WebSocketSession safeSession =
                new ConcurrentWebSocketSessionDecorator(session, SEND_TIME_LIMIT, BUFFER_SIZE_LIMIT);

        sessions.computeIfAbsent(userId, key -> ConcurrentHashMap.newKeySet()).add(safeSession);
        log.info("WebSocket 连接建立，userId={}，连接数={}", userId, countByUser(userId));
    }

    /** 注销一条连接（断开时调用）。顺手清掉空集合，免得表里留下空壳 */
    public void unregister(Long userId, WebSocketSession session) {
        if (userId == null) {
            return;
        }

        Set<WebSocketSession> userSessions = sessions.get(userId);
        if (userSessions == null) {
            return;
        }

        // 注册时包了一层装饰器，断开回调里拿到的还是原始 session，
        // 所以要按 id 比对，不能直接 remove(session)
        userSessions.removeIf(item -> item.getId().equals(session.getId()));

        if (userSessions.isEmpty()) {
            sessions.remove(userId);
        }
        log.info("WebSocket 连接关闭，userId={}", userId);
    }

    /**
     * 给某个用户的所有连接推一条文本消息。
     *
     * <p>返回「发出去了几条」，方便调用方记日志。
     *
     * <p><b>刻意不抛异常</b>：推送失败不该让业务失败。
     * 管理员点「派发」，任务该派出去就得派出去 ——
     * 某个飞手机没网收不到推送，不该导致整个派发回滚。
     * 所以这里的失败只记日志，返回 0 或更少的条数。
     */
    public int sendToUser(Long userId, String payload) {
        Set<WebSocketSession> userSessions = sessions.get(userId);
        if (userSessions == null || userSessions.isEmpty()) {
            // 很常见：人不在线。他下次进页面会从接口拉到最新数据（见文档第 5.2 节）
            log.debug("用户当前没有活跃的 WebSocket 连接，跳过推送，userId={}", userId);
            return 0;
        }

        int sent = 0;
        for (WebSocketSession session : userSessions) {
            if (!session.isOpen()) {
                // 连接已经关了但还没被清掉（断开回调稍后才到），顺手跳过
                continue;
            }
            try {
                session.sendMessage(new TextMessage(payload));
                sent++;
            } catch (IOException e) {
                log.warn("WebSocket 推送失败，userId={}，sessionId={}，原因={}",
                        userId, session.getId(), e.getMessage());
            }
        }
        return sent;
    }

    /** 某个用户当前的连接数，用于日志与排查 */
    public int countByUser(Long userId) {
        Set<WebSocketSession> userSessions = sessions.get(userId);
        return userSessions == null ? 0 : userSessions.size();
    }

    /** 当前在线（有连接）的用户数 */
    public int countOnlineUsers() {
        return sessions.size();
    }

    /** 当前连接总数 */
    public int countConnections() {
        return sessions.values().stream().mapToInt(Set::size).sum();
    }

    /**
     * 关掉一条连接。
     *
     * <p>用在「握手通过但后续校验不通过」之类的场景；正常断开由 WebSocket 协议自己处理。
     */
    public void closeQuietly(WebSocketSession session, CloseStatus status) {
        try {
            session.close(status);
        } catch (IOException e) {
            log.debug("关闭 WebSocket 连接时出错（可忽略）：{}", e.getMessage());
        }
    }
}
