package cn.tedu.droneagent.memory;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 同一会话的请求串行化。
 *
 * <h2>为什么需要它</h2>
 * 同一个会话的两条消息若并发处理，两边都会：各自读一遍历史 → 各自调模型 → 各自写回。
 * 结果是上下文顺序错乱、消息互相覆盖，用户看到的像是「助手答非所问」。
 * 一个用户在同一个会话里连发两条，多半是误操作，**串行比交错更接近他的预期**。
 *
 * <h2>为什么用信号量而不是锁</h2>
 * 流式请求的持锁时间跨越整段生成：请求线程把活交给异步线程后就返回了。
 * {@code ReentrantLock} 要求同一个线程加解锁，跨线程释放会直接报错；
 * {@code Semaphore} 的许可可以在一个线程拿、另一个线程还，正合适。
 *
 * <h2>为什么还要引用计数</h2>
 * 信号量是「每个会话一个」。若只增不减，长期运行下来会为每个历史会话留一个对象。
 * 但**不能一释放就从表里删掉**——那样两个线程可能各自拿到新旧两个不同的信号量，
 * 互斥就失效了。所以按「正在使用或等待的线程数」计数，归零才移除。
 *
 * <h2>它在链路里的位置，以及它不负责什么</h2>
 * <p><b>位置</b>：由 {@code ConversationService} 在「读—调—写」整段前后加锁解锁，
 * 是请求编排层的并发控制手段。</p>
 *
 * <ul>
 *   <li><b>不感知业务</b>：它只知道「这个键被占用了」，不知道占用者在聊天还是在删会话。</li>
 *   <li><b>不做鉴权</b>：谁能操作哪条会话不由它判断（键里带 userId 已经隔绝了越权）。</li>
 *   <li><b>只在单进程内有效</b>：它是内存里的信号量表；多实例部署时各锁各的，
 *       而多实例本来就被 ADR-0003 排除了。</li>
 * </ul>
 */
public class SessionLocks {

    /** 等待超时：超过就告诉调用方「这条会话正忙」，而不是无限挂着线程 */
    public static final Duration DEFAULT_WAIT = Duration.ofSeconds(30);

    private final Map<SessionKey, Entry> entries = new ConcurrentHashMap<>();

    /**
     * 尝试独占该会话。
     *
     * @return 拿到返回 {@code true}；等待超时返回 {@code false}（调用方应据此拒绝本次请求）
     */
    public boolean tryAcquire(SessionKey key, Duration timeout) throws InterruptedException {
        Entry entry = entries.compute(key, (k, existing) -> {
            Entry target = existing != null ? existing : new Entry();
            // 先把「我在这条会话上」记上，再排队。顺序不能反：
            // 先排队后计数的话，这一瞬间表里可能被判定为「无人使用」而被移除
            target.holders.incrementAndGet();
            return target;
        });

        if (entry.permits.tryAcquire(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
            return true;
        }
        dropReference(key, entry);
        return false;
    }

    /**
     * 释放该会话。必须与 {@link #tryAcquire} 成对，放在 {@code finally} 里。
     */
    public void release(SessionKey key) {
        Entry entry = entries.get(key);
        if (entry == null) {
            // 正常流程走不到这里；出现了说明有地方没配对，但不该因此影响业务
            return;
        }
        entry.permits.release();
        dropReference(key, entry);
    }

    /** 无人使用且无人等待时把表项去掉，避免为每个历史会话长期留一个信号量 */
    private void dropReference(SessionKey key, Entry entry) {
        if (entry.holders.decrementAndGet() == 0) {
            entries.remove(key, entry);
        }
    }

    /** 当前表里还留着多少个会话的锁（供观测与测试用） */
    int trackedSessions() {
        return entries.size();
    }

    private static final class Entry {

        private final Semaphore permits = new Semaphore(1);

        /** 正在使用或正在等待的线程数；归零才可以移除表项 */
        private final AtomicInteger holders = new AtomicInteger();
    }
}
