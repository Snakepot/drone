package cn.tedu.droneagent.memory;

import cn.tedu.droneagent.config.MemoryProperties;
import cn.tedu.droneagent.dto.ChatMessage;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import com.github.benmanes.caffeine.cache.Ticker;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * 会话级短期记忆：内存中的会话存储。
 *
 * <h2>两个方向：什么时候「从文件进内存」、什么时候「从内存写到文件」</h2>
 * <p>这是本类最需要先讲清的一件事 —— 它同时是「进」和「出」的现场。
 * 会话在内存与文件之间只有这五条路径：
 *
 * <pre>
 *  方向         触发时机                        入口                         谁实际读写文件
 *  ──────────  ──────────────────────────────  ───────────────────────────  ──────────────────────────
 *  文件 → 内存   请求的会话不在内存里（未命中）   SessionStore.getOrCreate     SessionLoader（读索引 + 正文）
 *  内存 → 文件   空闲时间超过 idle-timeout       缓存的过期维护               SessionArchiver（写正文 + 索引）
 *  内存 → 文件   内存里的会话数超过上限          缓存的容量淘汰               SessionArchiver
 *  内存 → 文件   单会话的消息数超过上限          SessionStore.append          SessionArchiver
 *  内存 → 文件   进程优雅停机                    MemoryShutdownFlusher        SessionArchiver（同步写出）
 * </pre>
 *
 * <p>五个入口里，只有第一个是「进来」，其余四个都是「出去」。
 * <b>唯一不属于任一边的是用户主动删除</b>（{@link #remove}）——那是「内存 → 丢弃」，
 * 刻意不写文件，也正是下一节要防的事。
 *
 * <h2>三种「会话离开内存」的情形，只有两种该归档</h2>
 * <ul>
 *   <li><b>空闲过期</b>（{@code EXPIRED}）：用户离开太久，会话从内存退场，但内容必须留下 —— 归档。</li>
 *   <li><b>会话数超上限</b>（{@code SIZE}）：腾位置给新会话，同理必须留下 —— 归档。</li>
 *   <li><b>用户主动删除</b>（{@code EXPLICIT}）：用户就是要它消失。</li>
 * </ul>
 *
 * <p>⚠️ 第三条是关键：缓存的移除监听器**把「删除」和「过期」都算作移除**，
 * 如果不看原因一律归档，用户刚删掉的会话会被监听器立刻写回长期记忆 ——
 * <b>删除等于白删，而且用户下次还会看到它</b>（懒加载把它读回来）。
 * 所以 {@link #toArchivableReason} 明确只放行两种原因。
 *
 * <h2>归档是<b>异步</b>的，不是「淘汰前落盘」</h2>
 * 移除监听器由缓存的维护任务触发，**跑在另一个线程上**，且调用方方法返回时它可能还没执行
 * （实测：调用方是 main，回调跑在 {@code ForkJoinPool.commonPool-worker-1}，
 * {@code cleanUp()} 返回时尚未触发）。由此得到三条硬性约束：
 * <ol>
 *   <li>{@link SessionArchiver} 的实现必须**线程安全**；</li>
 *   <li>实现里**不能依赖 ThreadLocal**（例如请求上下文），因为回调线程上没有请求；</li>
 *   <li>所以「淘汰前已落盘成功」这种保证**给不出来**——被淘汰与会话落盘之间存在时间窗，
 *       其间进程若终止，该会话内容就丢了。这是本设计已知的取舍。</li>
 * </ol>
 *
 * <p>正因为回调线程不可控，这里给缓存显式指定了 {@code archivingExecutor}：
 * 归档一律跑在调用方提供的执行器上，既不占用 JVM 共享的 {@code commonPool}
 * （在里面做文件 IO 会拖累整个进程），也让归档的并发度可被显式限制。
 *
 * <h2>它在链路里的位置，以及它不负责什么</h2>
 * <p><b>位置</b>：整条链路的**底座**。一次对话的开始（取会话）、中间（追加消息）、
 * 结束（淘汰并归档）都经过它；上层的 {@code ConversationService} 与
 * {@code ContextAssembler} 都建立在它之上。
 *
 * <ul>
 *   <li><b>不写文件</b>：会话该归档时它只负责「判断该归档了、把会话递出去」，
 *       真去写正文与索引的是 {@link SessionArchiver} 的实现。</li>
 *   <li><b>不管落盘成不成功</b>：归档失败了是重试还是丢弃，由归档那边决定；
 *       本类不阻塞、也不感知。</li>
 *   <li><b>不组装上下文</b>：它只提供「这个会话有哪些消息」，
 *       取哪几条、要不要带长期记忆是 {@link ContextAssembler} 的事。</li>
 * </ul>
 */
@Slf4j
public class SessionStore {

    private final Cache<SessionKey, Session> cache;

    private final SessionLoader loader;

    private final SessionArchiver archiver;

    private final Executor archivingExecutor;

    private final int maxMessagesPerSession;

    public SessionStore(MemoryProperties properties, SessionLoader loader,
                        SessionArchiver archiver, Executor archivingExecutor) {
        this(properties, loader, archiver, archivingExecutor, Ticker.systemTicker());
    }

    /**
     * 包可见：让测试注入假时钟，从而确定性地驱动「空闲过期」，不必靠 sleep 等真实时间。
     */
    SessionStore(MemoryProperties properties, SessionLoader loader, SessionArchiver archiver,
                 Executor archivingExecutor, Ticker ticker) {
        this.loader = loader;
        this.archiver = archiver;
        this.archivingExecutor = archivingExecutor;
        this.maxMessagesPerSession = properties.maxMessagesPerSession();
        this.cache = Caffeine.newBuilder()
                .maximumSize(properties.sessionMaxCount())
                .expireAfterAccess(properties.idleTimeout())
                .ticker(ticker)
                .executor(archivingExecutor)
                .removalListener(this::onRemoved)
                .build();
    }

    /**
     * 取会话。内存里没有时经 {@link SessionLoader} 从长期记忆取回；取不到就建一个空的。
     *
     * <p>这是「文件 → 内存」**唯一**的入口：走到缓存未命中那一步才去读文件，
     * 正常热会话根本不会碰磁盘。
     *
     * @return 该会话，永不返回 {@code null}
     */
    public Session getOrCreate(SessionKey key) {
        return cache.get(key, k -> {
            // 能进这个回调，就说明内存里没有这条会话 —— 记一条，让「什么时候去读文件」在日志里看得见
            log.debug("【内存←文件】内存里没有这条会话，去长期记忆找：userId={}，conversationId={}",
                    k.userId(), k.conversationId());
            Session loaded = loader.load(k);
            return loaded != null ? loaded : new Session(k, Instant.now());
        });
    }

    /** 只看内存，不触发懒加载。用于判断会话当前是否驻留在内存里 */
    public Session findInMemory(SessionKey key) {
        return cache.getIfPresent(key);
    }

    /**
     * 追加一条消息；超过单会话上限时把它移出内存。
     *
     * <p>移出前会把会话**提交给归档执行器**（会话对象本身仍被这次调用的局部变量持有，
     * 所以内容不会因为移出内存而消失）。注意提交是异步的：与缓存淘汰路径一样，
     * 这里也不保证「落盘完成后才移出」。
     */
    public void append(SessionKey key, ChatMessage message) {
        Session session = getOrCreate(key);
        session.append(message);
        if (session.size() > maxMessagesPerSession) {
            // 不能只移出：invalidate 产生的原因也是 EXPLICIT，监听器按规则不会出手，
            // 只驱逐就等于把这段对话丢了
            archiveAsync(session, SessionEvictionReason.MESSAGE_CAPACITY);
            cache.invalidate(key);
        }
    }

    /**
     * 用户主动删除会话。
     *
     * <p>走 {@code invalidate} → 原因 {@code EXPLICIT} → 监听器不归档，
     * 因此删掉的内容不会又出现在长期记忆里。
     *
     * @return 该会话原本是否在内存中
     */
    public boolean remove(SessionKey key) {
        return cache.asMap().remove(key) != null;
    }

    /**
     * 强制做一次维护，驱动过期与容量淘汰。
     *
     * <p>⚠️ 它只保证「移除」这件事发生；随后的归档是**异步**的，本方法返回时未必已完成。
     */
    public void cleanUp() {
        cache.cleanUp();
    }

    /** 当前驻留内存的会话数（估算值，供观测用） */
    public long size() {
        return cache.estimatedSize();
    }

    /**
     * 列出当前驻留内存的所有会话（快照）。
     *
     * <p>停机刷盘要用它：进程要没了，内存里的会话必须逐个写出，
     * 而不是只写那些「恰好被判过期」的。
     */
    public List<Session> allSessions() {
        return List.copyOf(cache.asMap().values());
    }

    /**
     * 清空内存中的会话，**不触发归档**。
     *
     * <p>走 {@code invalidateAll} → 原因 {@code EXPLICIT} → 监听器不出手。
     * 停机刷盘之后调用它，避免刚写完又通过监听器写第二遍（会产生重复的会话段）。
     */
    public void removeAll() {
        cache.invalidateAll();
    }

    private void onRemoved(SessionKey key, Session session, RemovalCause cause) {
        SessionEvictionReason reason = toArchivableReason(cause);
        // reason 为 null 表示这次移除不该归档；session 为 null 说明值已被回收，没内容可写
        if (reason == null || session == null) {
            return;
        }
        archiver.archive(session, reason);
    }

    private void archiveAsync(Session session, SessionEvictionReason reason) {
        archivingExecutor.execute(() -> archiver.archive(session, reason));
    }

    /**
     * 把缓存的移除原因映射成「需要归档的原因」。
     *
     * @return 需要归档时返回对应原因；**不该归档时返回 {@code null}**
     */
    private static SessionEvictionReason toArchivableReason(RemovalCause cause) {
        return switch (cause) {
            case EXPIRED -> SessionEvictionReason.IDLE_TIMEOUT;
            case SIZE -> SessionEvictionReason.SESSION_CAPACITY;
            // EXPLICIT：用户主动删除。归档就等于把刚删的内容写回去。
            // REPLACED：值被替换，新值还在缓存里，归档旧值毫无意义。
            // COLLECTED：值已被 GC 回收，此时也拿不到内容了。
            default -> null;
        };
    }
}
