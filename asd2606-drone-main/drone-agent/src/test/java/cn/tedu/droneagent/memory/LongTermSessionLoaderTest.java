package cn.tedu.droneagent.memory;

import cn.tedu.droneagent.config.MemoryProperties;
import cn.tedu.droneagent.dto.ChatMessage;
import com.github.benmanes.caffeine.cache.Ticker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.unit.DataSize;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link LongTermSessionLoader} 的单元测试，对应验收条件 <b>AC-5</b>
 * （服务重启后该用户再次对话时，能读到重启前的记忆内容）。
 */
class LongTermSessionLoaderTest {

    private static final long USER = 5005L;

    private static final String STARTED_AT = "2026-10-12T04:00:00Z";

    @TempDir
    Path root;

    private ExecutorService archivingExecutor;

    private LongTermMemoryStore body;

    private MemoryIndex index;

    private MemoryArchiver archiver;

    private LongTermSessionLoader loader;

    @BeforeEach
    void setUp() {
        archivingExecutor = Executors.newSingleThreadExecutor(r -> new Thread(r, "test-archiver"));
        body = new LongTermMemoryStore(root, DataSize.ofMegabytes(2));
        index = new MemoryIndex(root);
        archiver = new MemoryArchiver(body, index);
        loader = new LongTermSessionLoader(index, body);
    }

    @AfterEach
    void tearDown() {
        archivingExecutor.shutdownNow();
    }

    private static ChatMessage user(String content) {
        return new ChatMessage("user", content);
    }

    private static ChatMessage assistant(String content) {
        return new ChatMessage("assistant", content);
    }

    private static List<String> contentsOf(Session session) {
        return session.messages().stream().map(ChatMessage::content).toList();
    }

    /** 造一个会话对象，但不经过内存存储——模拟「上一次运行留下的、已归档的东西」 */
    private Session archivedSession(String conversationId, ChatMessage... messages) {
        Session session = new Session(new SessionKey(USER, conversationId), Instant.parse(STARTED_AT));
        for (ChatMessage message : messages) {
            session.append(message);
        }
        archiver.archive(session, SessionEvictionReason.IDLE_TIMEOUT);
        return session;
    }

    private SessionStore newSessionStore() {
        MemoryProperties props = new MemoryProperties(String.valueOf(root), 100, Duration.ofSeconds(1800),
                200, DataSize.ofMegabytes(2), 10, 8000, true, 30, 10, 1500, 12000);
        return new SessionStore(props, loader, archiver, archivingExecutor, Ticker.systemTicker());
    }

    @Test
    @DisplayName("AC-5 能从长期记忆把会话恢复回来，消息与开始时间都对得上")
    void restoresSessionFromDisk() {
        archivedSession("conv-restore", user("重启前说过的话"), assistant("以及这句话的回"));

        Session restored = loader.load(new SessionKey(USER, "conv-restore"));

        assertNotNull(restored);
        assertEquals(List.of("重启前说过的话", "以及这句话的回"), contentsOf(restored));
        assertEquals(Instant.parse(STARTED_AT).getEpochSecond(), restored.startedAt().getEpochSecond());
    }

    @Test
    @DisplayName("AC-5 闭环：重启后的全新内存里，会话经懒加载从磁盘恢复（端到端）")
    void sessionStoreRestoresThroughLoaderAfterRestart() {
        archivedSession("conv-restart", user("重启前说过的话"));

        // 「重启后」的内存是空的，只能靠 loader 兜住
        SessionStore afterRestart = newSessionStore();
        assertNull(afterRestart.findInMemory(new SessionKey(USER, "conv-restart")), "重启后内存里不该有它");

        Session recovered = afterRestart.getOrCreate(new SessionKey(USER, "conv-restart"));

        assertEquals(List.of("重启前说过的话"), contentsOf(recovered));
        assertTrue(afterRestart.findInMemory(new SessionKey(USER, "conv-restart")) != null, "恢复后应驻留内存");
    }

    @Test
    @DisplayName("恢复出来的内容也是脱敏后的（落盘脱敏的必然结果，不是缺陷）")
    void restoredContentIsAlreadyMasked() {
        archivedSession("conv-masked", user("我的手机号是 13800138000"));

        Session restored = loader.load(new SessionKey(USER, "conv-masked"));

        assertEquals(List.of("我的手机号是 138****8000"), contentsOf(restored));
    }

    @Test
    @DisplayName("恢复后的会话可以继续追加消息，接得上")
    void restoredSessionAcceptsMoreMessages() {
        archivedSession("conv-continue", user("第一句"));

        Session restored = loader.load(new SessionKey(USER, "conv-continue"));
        restored.append(user("接着说的第二句"));

        assertEquals(List.of("第一句", "接着说的第二句"), contentsOf(restored));
    }

    @Test
    @DisplayName("长期记忆里没有该会话时返回 null（新会话是常态，不该报错）")
    void returnsNullWhenNothingOnDisk() {
        assertNull(loader.load(new SessionKey(USER, "never-existed")));
    }

    @Test
    @DisplayName("会话被反复归档时，恢复的是**最新**那一段，不是最旧的")
    void restoresLatestSectionWhenSameConversationArchivedTwice() {
        archivedSession("conv-twice", user("第一轮的话"));
        archivedSession("conv-twice", user("第二轮的话"));

        Session restored = loader.load(new SessionKey(USER, "conv-twice"));

        assertEquals(List.of("第二轮的话"), contentsOf(restored), "恢复的应是最新一段");
        assertEquals(1, index.list(USER).size(), "索引里同名会话应收敛成一条");
    }

    @Test
    @DisplayName("索引里有、正文里没有时返回 null 并告警，不让它静默")
    void returnsNullWhenIndexHasEntryButBodyMissing() throws IOException {
        archivedSession("conv-orphan", user("正文待会儿会被删掉"));

        // 把正文文件手工删掉，模拟「索引与正文不同步」
        Path directory = root.resolve(String.valueOf(USER));
        try (var stream = Files.list(directory)) {
            for (Path file : stream.filter(Files::isRegularFile).toList()) {
                assertFalse(Files.readString(file, StandardCharsets.UTF_8).isEmpty());
                Files.delete(file);
            }
        }

        assertNull(loader.load(new SessionKey(USER, "conv-orphan")));
    }
}
