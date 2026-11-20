package cn.tedu.droneagent.memory;

import cn.tedu.droneagent.config.MemoryProperties;
import cn.tedu.droneagent.dto.ChatMessage;
import com.github.benmanes.caffeine.cache.Ticker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.unit.DataSize;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MemoryShutdownFlusher} 的单元测试，对应验收条件 <b>AC-7</b>
 * （服务优雅停机时，内存中未落盘的会话被写出，无丢失）。
 *
 * <p>这里刻意不启动重试调度器、也不走异步执行器：停机刷盘本就该是**同步**的，
 * 测试若依赖线程调度就说不清「到底是谁保证写完了」。
 */
class MemoryShutdownFlusherTest {

    private static final long USER = 4004L;

    @TempDir
    Path root;

    private ExecutorService archivingExecutor;

    private LongTermMemoryStore body;

    private MemoryIndex index;

    private MemoryArchiver archiver;

    private SessionStore sessionStore;

    @AfterEach
    void tearDown() {
        archivingExecutor.shutdownNow();
    }

    /** 建一套互相接上的组件：内存存储的归档出口就是归档器，归档器写正文与索引 */
    private void wireUp(int sessionMaxCount) {
        archivingExecutor = Executors.newSingleThreadExecutor(r -> new Thread(r, "test-archiver"));
        body = new LongTermMemoryStore(root, DataSize.ofMegabytes(2));
        index = new MemoryIndex(root);
        archiver = new MemoryArchiver(body, index);
        MemoryProperties props = new MemoryProperties(String.valueOf(root), sessionMaxCount,
                Duration.ofSeconds(1800), 200, DataSize.ofMegabytes(2), 10, 8000, true, 30, 10, 1500, 12000);
        sessionStore = new SessionStore(props, k -> null, archiver, archivingExecutor, Ticker.systemTicker());
    }

    private static ChatMessage user(String content) {
        return new ChatMessage("user", content);
    }

    private String allDiskContent() throws IOException {
        Path directory = root.resolve(String.valueOf(USER));
        if (!Files.isDirectory(directory)) {
            return "";
        }
        StringBuilder all = new StringBuilder();
        try (var stream = Files.list(directory)) {
            for (Path file : stream.filter(Files::isRegularFile).sorted().toList()) {
                all.append(Files.readString(file, StandardCharsets.UTF_8));
            }
        }
        return all.toString();
    }

    @Test
    @DisplayName("AC-7 停机时内存里的会话全部被写出，且内存被清空")
    void shutdownFlushesAllInMemorySessions() throws IOException {
        wireUp(100);
        SessionKey first = new SessionKey(USER, "conv-1");
        SessionKey second = new SessionKey(USER, "conv-2");
        sessionStore.append(first, user("停机前聊的第一件事"));
        sessionStore.append(second, user("停机前聊的第二件事"));
        assertEquals(2, sessionStore.allSessions().size(), "两个会话都应还在内存里");

        new MemoryShutdownFlusher(sessionStore, archiver).stop();

        String disk = allDiskContent();
        assertTrue(disk.contains("停机前聊的第一件事"), "第一个会话应已落盘");
        assertTrue(disk.contains("停机前聊的第二件事"), "第二个会话应已落盘");
        assertEquals(2, index.list(USER).size(), "两个会话的索引都应写好");
        assertTrue(sessionStore.allSessions().isEmpty(), "刷盘后内存应被清空");
    }

    @Test
    @DisplayName("AC-7 停机刷盘不会写出重复的会话段（清空走 EXPLICIT，不再触发一遍归档）")
    void shutdownDoesNotWriteDuplicateSections() throws IOException {
        wireUp(100);
        sessionStore.append(new SessionKey(USER, "conv-once"), user("只该出现一次"));

        new MemoryShutdownFlusher(sessionStore, archiver).stop();

        String disk = allDiskContent();
        assertEquals(1, disk.split("## conv=conv-once", -1).length - 1, "同一会话段只应出现一次");
    }

    @Test
    @DisplayName("停机时把之前写失败的补上（队列里的重试不被丢掉）")
    void shutdownFlushesPendingRetries() {
        archivingExecutor = Executors.newSingleThreadExecutor(r -> new Thread(r, "test-archiver"));
        body = new LongTermMemoryStore(root, DataSize.ofMegabytes(2));
        index = new FailingOnceMemoryIndex(root);
        archiver = new MemoryArchiver(body, index);
        MemoryProperties props = new MemoryProperties(String.valueOf(root), 100,
                Duration.ofSeconds(1800), 200, DataSize.ofMegabytes(2), 10, 8000, true, 30, 10, 1500, 12000);
        sessionStore = new SessionStore(props, k -> null, archiver, archivingExecutor, Ticker.systemTicker());

        Session session = new Session(new SessionKey(USER, "conv-late"), java.time.Instant.parse("2026-10-12T04:00:00Z"));
        session.append(user("第一次索引没写成功"));
        archiver.archive(session, SessionEvictionReason.IDLE_TIMEOUT);
        assertEquals(1, archiver.pendingRetryCount(), "应先落在重试队列里");
        assertTrue(index.list(USER).isEmpty());

        new MemoryShutdownFlusher(sessionStore, archiver).stop();

        assertEquals(1, index.list(USER).size(), "停机时该把这次重试补上");
        assertEquals(0, archiver.pendingRetryCount());
    }

    @Test
    @DisplayName("内存为空时停机不产生任何文件")
    void shutdownWithEmptyMemoryWritesNothing() throws IOException {
        wireUp(100);

        new MemoryShutdownFlusher(sessionStore, archiver).stop();

        assertFalse(Files.exists(root.resolve(String.valueOf(USER))), "没有会话就不该建目录");
    }

    /** 前一次失败、之后成功，用来验证停机把重试补上 */
    private static final class FailingOnceMemoryIndex extends MemoryIndex {

        private boolean failed;

        FailingOnceMemoryIndex(Path root) {
            super(root);
        }

        @Override
        public void append(long userId, IndexEntry entry) {
            if (!failed) {
                failed = true;
                throw new UncheckedIOException(new IOException("模拟第一次索引写入失败"));
            }
            super.append(userId, entry);
        }
    }
}
