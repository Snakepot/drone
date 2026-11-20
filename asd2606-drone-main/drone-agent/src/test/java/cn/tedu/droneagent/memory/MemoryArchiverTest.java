package cn.tedu.droneagent.memory;

import cn.tedu.droneagent.dto.ChatMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.unit.DataSize;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MemoryArchiver} 的单元测试。
 *
 * <p>对应验收条件：<b>AC-12</b>（删除后原文不再出现——这里验的是「写入不产生重复段」的反面）、
 * <b>AC-18</b>（落盘前脱敏，含索引标题）。
 *
 * <p>两条行为守卫：{@code retriesIndexWriteAndDoesNotDuplicateBody}（正文只写一次）
 * 与 {@code dropsWhenRetryQueueFull}（队列满时丢弃而非无限排队）。
 */
class MemoryArchiverTest {

    private static final long USER = 3003L;

    @TempDir
    Path root;

    /**
     * 可以「先失败几次再成功」的索引，用来构造重试场景。
     * 失败发生在索引这一步，正好验证「正文已写成功时重试只补索引」。
     */
    private static final class FlakyMemoryIndex extends MemoryIndex {

        private int failuresLeft;

        FlakyMemoryIndex(Path root, int failuresLeft) {
            super(root);
            this.failuresLeft = failuresLeft;
        }

        @Override
        public void append(long userId, IndexEntry entry) {
            if (failuresLeft-- > 0) {
                throw new UncheckedIOException(new IOException("模拟索引写入失败"));
            }
            super.append(userId, entry);
        }
    }

    private static ChatMessage user(String content) {
        return new ChatMessage("user", content);
    }

    private static ChatMessage assistant(String content) {
        return new ChatMessage("assistant", content);
    }

    private static Session session(String conversationId, ChatMessage... messages) {
        Session session = new Session(new SessionKey(USER, conversationId), Instant.parse("2026-10-12T04:00:00Z"));
        for (ChatMessage message : messages) {
            session.append(message);
        }
        return session;
    }

    /** 该用户所有分片拼起来的内容 */
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

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        int from = 0;
        while ((from = text.indexOf(needle, from)) >= 0) {
            count++;
            from += needle.length();
        }
        return count;
    }

    @Test
    @DisplayName("归档一次就同时写出正文与索引，且两者能对上")
    void archivesBodyAndIndexInOneCall() {
        LongTermMemoryStore body = new LongTermMemoryStore(root, DataSize.ofMegabytes(2));
        MemoryIndex index = new MemoryIndex(root);
        MemoryArchiver archiver = new MemoryArchiver(body, index);

        archiver.archive(session("conv-a", user("电池低温报警怎么处理"), assistant("先停止充电")),
                SessionEvictionReason.IDLE_TIMEOUT);

        List<IndexEntry> entries = index.list(USER);
        assertEquals(1, entries.size());
        assertEquals("conv-a", entries.get(0).conversationId());
        assertEquals(2, entries.get(0).messageCount());

        List<ChatMessage> loaded = body.read(USER, entries.get(0).fileName(), "conv-a").orElseThrow();
        assertEquals(List.of("电池低温报警怎么处理", "先停止充电"),
                loaded.stream().map(ChatMessage::content).toList());
    }

    @Test
    @DisplayName("索引标题取首条用户提问，而不是首条消息")
    void indexTitleUsesFirstUserMessage() {
        MemoryIndex index = new MemoryIndex(root);
        MemoryArchiver archiver = new MemoryArchiver(new LongTermMemoryStore(root, DataSize.ofMegabytes(2)), index);

        // 首条是 assistant，用来确认取的是「首条用户提问」
        archiver.archive(session("conv-title", assistant("你好"), user("我的机型和责任区是什么")),
                SessionEvictionReason.IDLE_TIMEOUT);

        assertEquals("我的机型和责任区是什么", index.list(USER).get(0).title());
    }

    @Test
    @DisplayName("AC-18 索引标题在落盘前也已脱敏")
    void indexTitleIsMasked() throws IOException {
        MemoryIndex index = new MemoryIndex(root);
        MemoryArchiver archiver = new MemoryArchiver(new LongTermMemoryStore(root, DataSize.ofMegabytes(2)), index);

        archiver.archive(session("conv-mask-title", user("我的手机号是 13800138000，帮我记一下")),
                SessionEvictionReason.IDLE_TIMEOUT);

        assertFalse(allDiskContent().contains("13800138000"), "标题里的手机号不该出现在磁盘上");
        assertTrue(index.list(USER).get(0).title().contains("138****8000"));
    }

    @Test
    @DisplayName("行为守卫：索引写失败会进重试队列；重试只补索引，正文不写第二遍")
    void retriesIndexWriteAndDoesNotDuplicateBody() throws IOException {
        LongTermMemoryStore body = new LongTermMemoryStore(root, DataSize.ofMegabytes(2));
        MemoryIndex flaky = new FlakyMemoryIndex(root, 1);
        MemoryArchiver archiver = new MemoryArchiver(body, flaky);

        archiver.archive(session("conv-retry", user("会重试的内容")), SessionEvictionReason.IDLE_TIMEOUT);
        assertEquals(1, archiver.pendingRetryCount(), "索引失败应进队列");
        assertTrue(flaky.list(USER).isEmpty(), "这一次索引确实没写成");

        archiver.retryPendingNow();

        assertEquals(0, archiver.pendingRetryCount(), "重试成功后队列应清空");
        assertEquals(1, flaky.list(USER).size(), "重试应把索引补上");
        // 正文只应有一段：重试若把正文也重写一遍，这里会变成 2
        assertEquals(1, countOccurrences(allDiskContent(), "## conv=conv-retry"), "正文不该被写第二遍");
    }

    @Test
    @DisplayName("行为守卫：重试队列满时丢弃并继续，不阻塞也不抛异常")
    void dropsWhenRetryQueueFull() {
        MemoryIndex alwaysFails = new FlakyMemoryIndex(root, Integer.MAX_VALUE);
        MemoryArchiver archiver = new MemoryArchiver(
                new LongTermMemoryStore(root, DataSize.ofMegabytes(2)), alwaysFails, 1);

        archiver.archive(session("conv-1", user("第一条")), SessionEvictionReason.IDLE_TIMEOUT);
        archiver.archive(session("conv-2", user("第二条")), SessionEvictionReason.IDLE_TIMEOUT);

        assertEquals(1, archiver.pendingRetryCount(), "队列有界：第二条只能被丢弃");
    }

    @Test
    @DisplayName("重试仍然失败时留在队列里等下一轮，不会丢")
    void keepsPendingWhenRetryStillFails() {
        MemoryIndex alwaysFails = new FlakyMemoryIndex(root, Integer.MAX_VALUE);
        MemoryArchiver archiver = new MemoryArchiver(
                new LongTermMemoryStore(root, DataSize.ofMegabytes(2)), alwaysFails);

        archiver.archive(session("conv-x", user("还会失败")), SessionEvictionReason.IDLE_TIMEOUT);
        archiver.retryPendingNow();

        assertEquals(1, archiver.pendingRetryCount(), "重试失败应放回队列，不是直接丢掉");
    }

    @Test
    @DisplayName("空会话不报错，照常写出（用户可能刚开一个会话就被淘汰）")
    void handlesEmptySession() {
        MemoryIndex index = new MemoryIndex(root);
        MemoryArchiver archiver = new MemoryArchiver(new LongTermMemoryStore(root, DataSize.ofMegabytes(2)), index);

        archiver.archive(session("conv-empty"), SessionEvictionReason.SESSION_CAPACITY);

        assertEquals(1, index.list(USER).size());
        assertEquals(0, index.list(USER).get(0).messageCount());
        assertEquals("", index.list(USER).get(0).title());
    }
}
