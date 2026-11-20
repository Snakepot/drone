package cn.tedu.droneagent.cancel;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;

/**
 * 在途请求的取消注册表（`REQ-AGENT-0029`）。
 *
 * <h2>为什么用 conversationId 而不是 traceId</h2>
 * traceId 要到流结束的 {@code done} 才回传给前端，用户中途点「取消」时拿不到；
 * conversationId 前端本来就有。会话锁保证同一会话至多一个在途请求，所以它是可靠的定位键。
 *
 * <h2>为什么键里带 userId</h2>
 * 防止 A 用户凭 conversationId 取消 B 用户的请求。键 = {@code userId + ":" + conversationId}。
 *
 * <h2>它不负责什么</h2>
 * <ul>
 *   <li>不中断正在执行的工具：只置位，循环在下一个检查点停；</li>
 *   <li>不清理待确认票据：那是 PendingActionStore 的事；</li>
 *   <li>不做跨实例共享：本期单实例。</li>
 * </ul>
 */
@Slf4j
public class CancellationRegistry {

    private static final Duration TTL = Duration.ofMinutes(30);

    private final Cache<String, Entry> active = Caffeine.newBuilder()
            .expireAfterWrite(TTL)
            .maximumSize(5_000)
            .build();

    /**
     * 请求开始时登记；返回的信号交给编排层。
     *
     * <p>会话锁已保证同一会话不会并发，后登记直接覆盖旧条目即可。
     */
    public CancellationSignal register(long userId, String conversationId, String traceId) {
        CancellationSignal signal = new CancellationSignal();
        active.put(key(userId, conversationId), new Entry(traceId, signal));
        return signal;
    }

    /**
     * 置位取消；请求不在途（已完成/不存在）返回 false。
     */
    public boolean cancel(long userId, String conversationId) {
        Entry entry = active.getIfPresent(key(userId, conversationId));
        if (entry == null) {
            return false;
        }
        entry.signal().cancel();
        log.info("【取消】已置位：userId={}，conversationId={}，traceId={}",
                userId, conversationId, entry.traceId());
        return true;
    }

    /** 仅供测试观察信号是否被置位 */
    boolean isCancelled(long userId, String conversationId) {
        Entry entry = active.getIfPresent(key(userId, conversationId));
        return entry != null && entry.signal().isCancelled();
    }

    /** 请求结束时清理，避免残留到下一次请求 */
    public void clear(long userId, String conversationId) {
        active.invalidate(key(userId, conversationId));
    }

    /** 当前登记的 traceId；不存在返回 null */
    public String traceIdOf(long userId, String conversationId) {
        Entry entry = active.getIfPresent(key(userId, conversationId));
        return entry == null ? null : entry.traceId();
    }

    private static String key(long userId, String conversationId) {
        return userId + ":" + conversationId;
    }

    private record Entry(String traceId, CancellationSignal signal) {
    }
}
