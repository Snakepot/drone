package cn.tedu.droneagent.memory;

import cn.tedu.droneagent.config.MemoryProperties;
import cn.tedu.droneagent.dto.ChatMessage;
import com.github.benmanes.caffeine.cache.Ticker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SessionStore} 的单元测试。
 *
 * <p>对应验收条件：`docs/sdd/specs/REQ-AGENT-0003-会话与长期记忆.md` 的
 * <b>AC-2</b>（会话互不串味）、<b>AC-4</b>（超上限先提交归档再淘汰）、
 * <b>AC-6</b>（空闲过期即归档）。
 *
 * <h2>为什么测试里要给一个「单线程」执行器</h2>
 * 归档是**异步**的，而且默认会跑在 JVM 共享的 {@code ForkJoinPool.commonPool} 上——
 * 那样测试既无法确定性等待，也读不到稳定的线程。这里统一给它一个自己的单线程执行器：
 * 归档全部落在这一个线程上，于是
 * <ol>
 *   <li>往同一执行器投一个空任务再等它完成，就等于「排在它前面的归档都做完了」（见 {@link #awaitArchiving()}）；</li>
 *   <li>测试线程读 {@code archived} 与归档线程写它之间有了 happens-before，不用加锁也不会误报。</li>
 * </ol>
 *
 * <p>两条**行为守卫**，失败说明踩了归档语义的坑，而不是实现细节写错：
 * {@code doesNotArchiveOnExplicitRemove}（删除不得写回）、
 * {@code archivesBeforeEvictingOnMessageCapacity}（超限不得只驱逐不归档）。
 */
class SessionStoreTest {

    /** 假时钟：让空闲过期可确定性驱动，不用 sleep 等真实时间 */
    private static final class FakeTicker implements Ticker {

        private long nanos;

        @Override
        public long read() {
            return nanos;
        }

        void advance(Duration duration) {
            nanos += duration.toNanos();
        }
    }

    private record Archived(SessionKey key, SessionEvictionReason reason) {
    }

    private FakeTicker ticker;

    private List<Archived> archived;

    private ExecutorService archivingExecutor;

    @BeforeEach
    void setUp() {
        ticker = new FakeTicker();
        archived = new ArrayList<>();
        archivingExecutor = Executors.newSingleThreadExecutor(r -> new Thread(r, "test-archiver"));
    }

    @AfterEach
    void tearDown() {
        archivingExecutor.shutdownNow();
    }

    /**
     * 等归档排空。
     *
     * <p>归档与「投递」都在同一个单线程执行器上按 FIFO 执行，所以往它投一个空任务并等它完成，
     * 就说明此前投递的归档都已执行完毕。不依赖 sleep，也不靠轮询碰运气。
     */
    private void awaitArchiving() throws Exception {
        archivingExecutor.submit(() -> {
        }).get(5, TimeUnit.SECONDS);
    }

    private static MemoryProperties props(int sessionMaxCount, long idleSeconds, int maxMessagesPerSession) {
        return new MemoryProperties("/tmp/agent-memory-test", sessionMaxCount, Duration.ofSeconds(idleSeconds),
                maxMessagesPerSession, DataSize.ofMegabytes(2), 10, 8000, true, 30, 10, 1500, 12000);
    }

    private static SessionKey key(long userId, String conversationId) {
        return new SessionKey(userId, conversationId);
    }

    private static ChatMessage user(String content) {
        return new ChatMessage("user", content);
    }

    private static List<String> contentsOf(Session session) {
        return session.messages().stream().map(ChatMessage::content).toList();
    }

    /** 默认存储：加载不到任何历史，归档记到 archived 里 */
    private SessionStore newStore(int sessionMaxCount, long idleSeconds, int maxMessagesPerSession) {
        return new SessionStore(props(sessionMaxCount, idleSeconds, maxMessagesPerSession),
                k -> null,
                (session, reason) -> archived.add(new Archived(session.key(), reason)),
                archivingExecutor,
                ticker);
    }

    private List<SessionEvictionReason> archivedReasons() {
        return archived.stream().map(Archived::reason).toList();
    }

    @Test
    @DisplayName("追加后能按顺序读回")
    void appendsAndReadsBack() {
        SessionStore store = newStore(100, 1800, 200);
        SessionKey k = key(1L, "c1");

        store.append(k, user("第一条"));
        store.append(k, user("第二条"));

        assertEquals(List.of("第一条", "第二条"), contentsOf(store.getOrCreate(k)));
    }

    @Test
    @DisplayName("加载不到历史时建一个空会话，而不是返回 null")
    void createsEmptySessionWhenNothingToLoad() {
        SessionStore store = newStore(100, 1800, 200);

        Session session = store.getOrCreate(key(1L, "brand-new"));

        assertNotNull(session);
        assertEquals(0, session.size());
    }

    @Test
    @DisplayName("内存里没有该会话时，经 SessionLoader 把它取回来")
    void loadsFromLoaderWhenAbsent() {
        SessionKey k = key(1L, "restored");
        Session restored = new Session(k, Instant.parse("2026-01-01T00:00:00Z"));
        restored.append(user("重启前的对话"));

        SessionStore loadingStore = new SessionStore(props(100, 1800, 200),
                key -> key.equals(k) ? restored : null,
                (session, reason) -> archived.add(new Archived(session.key(), reason)),
                archivingExecutor, ticker);

        assertEquals(List.of("重启前的对话"), contentsOf(loadingStore.getOrCreate(k)));
    }

    @Test
    @DisplayName("AC-6 空闲过期：会话被移出内存，并异步归档，原因是 IDLE_TIMEOUT")
    void archivesOnIdleTimeout() throws Exception {
        SessionStore store = newStore(100, 1800, 200);
        SessionKey k = key(1L, "c1");
        store.append(k, user("会过期的内容"));

        ticker.advance(Duration.ofMinutes(31));
        store.cleanUp();
        awaitArchiving();

        assertEquals(List.of(SessionEvictionReason.IDLE_TIMEOUT), archivedReasons());
        assertNull(store.findInMemory(k), "过期后不应还留在内存里");
    }

    @Test
    @DisplayName("是 expireAfterAccess 而非 expireAfterWrite：中途有访问就不能判过期")
    void accessExtendsIdleWindow() throws Exception {
        SessionStore store = newStore(100, 1800, 200);
        SessionKey k = key(1L, "c1");
        store.append(k, user("a"));

        // 累计已过 40 分钟（超过 30 分钟窗口），但每 20 分钟访问一次
        ticker.advance(Duration.ofMinutes(20));
        store.getOrCreate(k);
        ticker.advance(Duration.ofMinutes(20));
        store.getOrCreate(k);
        store.cleanUp();
        awaitArchiving();

        assertTrue(archived.isEmpty(), "有访问就不该被判为空闲过期");
        assertNotNull(store.findInMemory(k));
    }

    @Test
    @DisplayName("AC-4 会话数超上限：被淘汰的会话先归档，且缓存大小被压回上限")
    void archivesOnSessionCapacity() throws Exception {
        SessionStore store = newStore(2, 1800, 200);

        // 上限 2、插入 50 个：只有持续超过上限，容量淘汰才会真正发生。
        // 只插 3 个不够——淘汰由缓存的准入策略与维护时机决定，不是「一超就踢」
        for (int i = 0; i < 50; i++) {
            store.append(key(1L, "c" + i), user("会话 " + i));
        }
        store.cleanUp();
        awaitArchiving();

        assertFalse(archived.isEmpty(), "超出上限应有会话被归档");
        assertTrue(archived.stream().allMatch(a -> a.reason() == SessionEvictionReason.SESSION_CAPACITY),
                () -> "归档原因应全部是 SESSION_CAPACITY，实际：" + archived);
        assertTrue(store.size() <= 2, () -> "缓存大小应被压回上限，实际 " + store.size());
    }

    @Test
    @DisplayName("行为守卫：用户主动删除绝不归档 —— 否则刚删的内容会被写回长期记忆")
    void doesNotArchiveOnExplicitRemove() throws Exception {
        SessionStore store = newStore(100, 1800, 200);
        SessionKey k = key(1L, "c1");
        store.append(k, user("用户不想留下的内容"));

        assertTrue(store.remove(k));
        store.cleanUp();
        awaitArchiving();

        assertTrue(archived.isEmpty(), "显式删除不得触发归档");
        assertNull(store.findInMemory(k));
    }

    @Test
    @DisplayName("行为守卫：单会话消息超上限时先提交归档再移出，不能只移出")
    void archivesBeforeEvictingOnMessageCapacity() throws Exception {
        SessionStore store = newStore(10, 1800, 2);
        SessionKey k = key(1L, "c1");

        store.append(k, user("1"));
        store.append(k, user("2"));
        store.append(k, user("3"));
        awaitArchiving();

        assertEquals(List.of(SessionEvictionReason.MESSAGE_CAPACITY), archivedReasons());
        assertNull(store.findInMemory(k), "超限后应被移出，等下次访问再懒加载");
    }

    @Test
    @DisplayName("AC-2 不同用户的同名 conversationId 互不影响")
    void isolatesUsers() {
        SessionStore store = newStore(100, 1800, 200);

        store.append(key(1L, "same-id"), user("甲的对话"));
        store.append(key(2L, "same-id"), user("乙的对话"));

        assertEquals(List.of("甲的对话"), contentsOf(store.getOrCreate(key(1L, "same-id"))));
        assertEquals(List.of("乙的对话"), contentsOf(store.getOrCreate(key(2L, "same-id"))));
    }

    @Test
    @DisplayName("会话标识不完整或含危险字符时直接拒绝，避免把坏 id 写进记忆文件格式")
    void rejectsUnsafeKey() {
        assertThrows(IllegalArgumentException.class, () -> new SessionKey(null, "c1"));
        assertThrows(IllegalArgumentException.class, () -> new SessionKey(1L, "  "));
        // 竖线与换行会破坏「一行一条记录」的格式，必须在构造处挡下
        assertThrows(IllegalArgumentException.class, () -> new SessionKey(1L, "带竖线|的id"));
        assertThrows(IllegalArgumentException.class, () -> new SessionKey(1L, "带换行\n的id"));
        assertThrows(IllegalArgumentException.class, () -> new SessionKey(1L, "a".repeat(65)));
    }
}
