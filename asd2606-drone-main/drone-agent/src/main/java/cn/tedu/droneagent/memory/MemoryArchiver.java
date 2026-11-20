package cn.tedu.droneagent.memory;

import cn.tedu.droneagent.dto.ChatMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 归档编排：把离开内存的会话真正写进长期记忆（正文 + 索引），并负责失败重试。
 *
 * <h2>为什么写盘顺序必须是「先正文、后索引」</h2>
 * 反过来的话，进程在两步之间死掉会留下**索引里有、正文里没有**的条目——
 * 会话列表上看得见，点进去空的。这是最难排查的一类不一致。所以宁可出现
 * 「正文有、索引没有」（内容还在盘上，只是列表不显示，且重试能把索引补上）。
 *
 * <h2>重试为什么不写成「无限重试」</h2>
 * 队列是**有界**的。磁盘满、权限错这类故障会持续失败，无限排队等于把内存吃光——
 * 用一个故障去换另一个更糟的故障。队列满时**丢弃并打 error 日志**：
 * 这条会话的内容就此丢失，必须让它显式可见，而不是安静地烂在队列里。
 *
 * <h2>停机刷盘为什么不在这里</h2>
 * 刷盘需要「内存里还有哪些会话」，那要读 {@link SessionStore}；
 * 而 {@code SessionStore} 构造时又需要一个 {@link SessionArchiver}——两者互相依赖，
 * 构造函数注入会直接形成环。因此刷盘交给 {@link MemoryShutdownFlusher}：
 * 由它同时持有两者，依赖方向变成单向。
 *
 * <p>本类只负责调度器自身的启停；{@link #getPhase} 用默认值（0），
 * 而刷盘那位的阶段更大、停机时**先**执行，顺序正好。
 *
 * <h2>它在链路里的位置，以及它不负责什么</h2>
 * <p><b>位置</b>：短期记忆与长期记忆之间的**搬运工**——它被 {@link SessionStore}
 * 用作归档出口（会话离开内存时回调），负责把正文与索引都写出去、失败的重试。
 *
 * <ul>
 *   <li><b>不决定什么时候归档</b>：触发时机在缓存那边（空闲过期、容量淘汰、用户删除），
 *       本类只是被告知「这一条要归档了」。</li>
 *   <li><b>不读内存里的会话</b>：它只接收递过来的那一个会话对象；
 *       「内存里还剩哪些没落盘」是 {@link MemoryShutdownFlusher} 的事。</li>
 *   <li><b>不做停机刷盘</b>：原因见上一节，那件事在刷盘类里。</li>
 * </ul>
 */
@Slf4j
public class MemoryArchiver implements SessionArchiver, SmartLifecycle {

    /** 重试队列默认容量；满了就丢弃并告警 */
    private static final int DEFAULT_RETRY_CAPACITY = 200;

    /** 重试间隔 */
    private static final long RETRY_INTERVAL_SECONDS = 30L;

    /** 一轮重试最多处理多少条，避免长时间占着线程 */
    private static final int RETRY_BATCH_SIZE = 50;

    private final LongTermMemoryStore longTermMemory;

    private final MemoryIndex index;

    private final BlockingQueue<Pending> pendingRetries;

    private final int retryCapacity;

    private final ScheduledExecutorService retryScheduler;

    private volatile boolean running;

    public MemoryArchiver(LongTermMemoryStore longTermMemory, MemoryIndex index) {
        this(longTermMemory, index, DEFAULT_RETRY_CAPACITY);
    }

    /**
     * 包可见：让测试用很小的队列容量构造「队列满」的场景，不必真造两百条失败。
     */
    MemoryArchiver(LongTermMemoryStore longTermMemory, MemoryIndex index, int retryCapacity) {
        this.longTermMemory = longTermMemory;
        this.index = index;
        this.retryCapacity = retryCapacity;
        this.pendingRetries = new ArrayBlockingQueue<>(retryCapacity);
        this.retryScheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "memory-archiver-retry");
            // 守护线程：重试是后台活，不该拦住 JVM 退出
            thread.setDaemon(true);
            return thread;
        });
    }

    @Override
    public void archive(Session session, SessionEvictionReason reason) {
        // INFO 而不是 debug：这是「内存 → 文件」真正发生的那一刻，也是数据唯一会丢的地方，
        // 值得留在常规日志里。方向用箭头写明，配合「归档完成」成对出现
        log.info("【文件←内存】开始归档：userId={}，conversationId={}，原因={}，消息数={}",
                session.key().userId(), session.key().conversationId(), reason, session.size());
        enqueueIfFailed(new Pending(session, null));
    }

    /**
     * 同步写出若干会话，用于停机刷盘。<b>不走异步执行器</b>——
     * 停机正是最不该依赖「等队列把活干完」的时刻。
     *
     * @return 写出失败的条数
     */
    public int flushSynchronously(List<Session> sessions) {
        int failed = 0;
        for (Session session : sessions) {
            if (!write(new Pending(session, null)).ok()) {
                log.error("停机刷盘时写出失败，会话 {} 的内容将丢失", session.key());
                failed++;
            }
        }
        return failed;
    }

    /** 停机时把之前失败的尽力补一遍；补不上只能丢，但要吵 */
    public void flushPendingRetriesSynchronously() {
        Pending pending;
        while ((pending = pendingRetries.poll()) != null) {
            if (!write(pending).ok()) {
                log.error("停机刷盘时补写失败，会话 {} 的内容将丢失", pending.session().key());
            }
        }
    }

    @Override
    public void start() {
        running = true;
        retryScheduler.scheduleWithFixedDelay(this::retryPendingNow,
                RETRY_INTERVAL_SECONDS, RETRY_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    @Override
    public void stop() {
        running = false;
        retryScheduler.shutdown();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /**
     * 把队列里的失败项重试一轮。
     *
     * <p>先把本轮要处理的一次性取出来（快照），失败的再放回队尾。
     * <b>不能边取边放</b>——那样失败项会立刻被本轮再取到，同一轮里被反复重试，
     * 等于持续猛敲一个已经出故障的磁盘。
     *
     * <p>包可见：生产由 {@link #start()} 里的调度器定时调用；
     * 测试直接调它，就不必等 30 秒、也不依赖时钟。
     */
    void retryPendingNow() {
        List<Pending> batch = new ArrayList<>(RETRY_BATCH_SIZE);
        pendingRetries.drainTo(batch, RETRY_BATCH_SIZE);
        for (Pending pending : batch) {
            // 失败会重新入队（队尾），本轮不会再拿到它
            enqueueIfFailed(pending);
        }
    }

    /** 队列里还有多少条待重试（供测试与观测用） */
    int pendingRetryCount() {
        return pendingRetries.size();
    }

    /** 写失败就排队等重试；这是**唯一**的入队路径，避免漏掉某条分支 */
    private void enqueueIfFailed(Pending pending) {
        WriteResult result = write(pending);
        if (result.ok()) {
            return;
        }
        // 正文若已写成功，重试时只补索引——不再写一遍正文，否则同一文件里会出现两个同名会话段
        Pending retry = new Pending(pending.session(), result.bodyFileName());
        if (!pendingRetries.offer(retry)) {
            log.error("重试队列已满（容量 {}），放弃归档会话 {}，该会话内容将丢失",
                    retryCapacity, pending.session().key());
        }
    }

    private WriteResult write(Pending pending) {
        Session session = pending.session();
        String bodyFileName = pending.bodyFileName();

        if (bodyFileName == null) {
            try {
                bodyFileName = longTermMemory.write(session);
            } catch (RuntimeException e) {
                log.warn("写入会话正文失败，将重试：{}，原因：{}", session.key(), e.getMessage());
                return new WriteResult(false, null);
            }
        }

        try {
            index.append(session.key().userId(), new IndexEntry(
                    session.key().conversationId(),
                    session.startedAt(),
                    session.size(),
                    titleOf(session),
                    bodyFileName));
            log.info("【文件←内存】归档完成：userId={}，conversationId={}，正文文件={}",
                    session.key().userId(), session.key().conversationId(), bodyFileName);
            return new WriteResult(true, bodyFileName);
        } catch (RuntimeException e) {
            log.warn("写入会话索引失败，将重试（正文已落盘，只补索引）：{}，原因：{}",
                    session.key(), e.getMessage());
            return new WriteResult(false, bodyFileName);
        }
    }

    /** 标题取首条**用户**提问；没有用户消息时退回首条消息 */
    private static String titleOf(Session session) {
        List<ChatMessage> messages = session.messages();
        return messages.stream()
                .filter(message -> "user".equals(message.role()))
                .map(ChatMessage::content)
                .findFirst()
                .orElseGet(() -> messages.stream().map(ChatMessage::content).findFirst().orElse(""));
    }

    /**
     * 一条待落盘的会话。
     *
     * @param bodyFileName 正文已写入的文件名；{@code null} 表示正文还没写
     */
    private record Pending(Session session, String bodyFileName) {
    }

    /**
     * 写出结果。
     *
     * @param ok           是否整条成功（正文与索引都写了）
     * @param bodyFileName 正文所在文件名；失败时给「只补索引」提供依据
     */
    private record WriteResult(boolean ok, String bodyFileName) {
    }
}
