package cn.tedu.droneagent.memory;

import cn.tedu.droneagent.dto.ChatMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.unit.DataSize;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link LongTermMemoryStore} 的单元测试。
 *
 * <p>对应验收条件：<b>AC-8</b>（目录布局）、<b>AC-9</b>（单文件超限滚动）、
 * <b>AC-12</b>（删除后原文不再出现）、<b>AC-13</b>（删 A 不伤 B）、
 * <b>AC-18</b>（落盘内容已脱敏）。
 */
class LongTermMemoryStoreTest {

    private static final long USER = 2002L;

    private static final String STARTED_AT = "2026-10-12T04:00:00Z";

    @TempDir
    Path root;

    private LongTermMemoryStore store;

    private static ChatMessage user(String content) {
        return new ChatMessage("user", content);
    }

    private static ChatMessage assistant(String content) {
        return new ChatMessage("assistant", content);
    }

    private static Session session(long userId, String conversationId, String startedAt, ChatMessage... messages) {
        Session session = new Session(new SessionKey(userId, conversationId), Instant.parse(startedAt));
        for (ChatMessage message : messages) {
            session.append(message);
        }
        return session;
    }

    private static List<String> contentsOf(List<ChatMessage> messages) {
        return messages.stream().map(ChatMessage::content).toList();
    }

    /** 读该用户所有正文分片拼起来的内容，用来断言「原文是否还在盘上」 */
    private String allDiskContent(long userId) throws IOException {
        Path directory = root.resolve(String.valueOf(userId));
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
    @DisplayName("AC-8 写入后能按会话读回，文件落在该用户目录、按开始日期命名")
    void writesAndReadsBack() {
        store = new LongTermMemoryStore(root, DataSize.ofMegabytes(2));

        String fileName = store.write(session(USER, "conv-a", STARTED_AT,
                user("电池低温报警该怎么处理"), assistant("先停止充电")));

        String expectedDay = DateTimeFormatter.ofPattern("yyyy-MM-dd")
                .format(Instant.parse(STARTED_AT).atZone(ZoneId.systemDefault()));
        assertEquals("memory-" + expectedDay + ".md", fileName);

        List<ChatMessage> loaded = store.read(USER, fileName, "conv-a").orElseThrow();
        assertEquals(List.of("电池低温报警该怎么处理", "先停止充电"), contentsOf(loaded));
        assertEquals(List.of("user", "assistant"), loaded.stream().map(ChatMessage::role).toList());
    }

    @Test
    @DisplayName("AC-9 单文件超过上限即滚动到下一个分片，内容都不丢")
    void rollsToNextFileWhenExceedingLimit() {
        // 上限设成 1 字节，纯粹为了让「第二次写入必然滚动」这件事确定成立，
        // 不受具体内容字节数影响。顺带也验证了：单个会话段大于上限时仍会被写出，不会被丢弃
        store = new LongTermMemoryStore(root, DataSize.ofBytes(1));

        String first = store.write(session(USER, "conv-1", STARTED_AT, user("第一段")));
        String second = store.write(session(USER, "conv-2", STARTED_AT, user("第二段")));

        // 断言确切文件名。别用 contains("-2") 判断滚动——日期本身就含 "-2"，会把断言写歪
        String day = DateTimeFormatter.ofPattern("yyyy-MM-dd")
                .format(Instant.parse(STARTED_AT).atZone(ZoneId.systemDefault()));
        assertEquals("memory-" + day + ".md", first, "第一个分片不带后缀");
        assertEquals("memory-" + day + "-2.md", second, "超过上限应滚动到 -2");

        assertTrue(store.read(USER, first, "conv-1").isPresent());
        assertTrue(store.read(USER, second, "conv-2").isPresent());
    }

    @Test
    @DisplayName("AC-18 落盘的是脱敏后的内容，敏感字段不进磁盘")
    void masksSensitiveContentOnDisk() throws IOException {
        store = new LongTermMemoryStore(root, DataSize.ofMegabytes(2));

        String fileName = store.write(session(USER, "conv-mask", STARTED_AT,
                user("我的手机号是 13800138000，邮箱 user@example.com")));

        String disk = allDiskContent(USER);
        assertFalse(disk.contains("13800138000"), "手机号原文不该出现在磁盘上");
        assertFalse(disk.contains("user@example.com"), "邮箱原文不该出现在磁盘上");
        assertTrue(disk.contains("138****8000"), "应写入脱敏后的形态");

        List<ChatMessage> loaded = store.read(USER, fileName, "conv-mask").orElseThrow();
        assertEquals("我的手机号是 138****8000，邮箱 u***@example.com", loaded.get(0).content());
    }

    @Test
    @DisplayName("多行内容与反斜杠能原样读回（转义不能丢信息）")
    void keepsNewlinesAndBackslashesIntact() {
        store = new LongTermMemoryStore(root, DataSize.ofMegabytes(2));
        String tricky = "第一行\n第二行\nuser: 这行长得像消息头\n路径 C:\\drone\\logs";

        String fileName = store.write(session(USER, "conv-esc", STARTED_AT, assistant(tricky)));

        List<ChatMessage> loaded = store.read(USER, fileName, "conv-esc").orElseThrow();
        assertEquals(tricky, loaded.get(0).content(), "换行与反斜杠必须无损往返");
    }

    @Test
    @DisplayName("AC-12 / AC-13 删除只清目标会话：它的原文从盘上消失，邻居完好")
    void deleteRemovesOnlyTargetSession() throws IOException {
        store = new LongTermMemoryStore(root, DataSize.ofMegabytes(2));
        String fileName = store.write(session(USER, "keep", STARTED_AT, user("要留下的内容")));
        store.write(session(USER, "drop", STARTED_AT, user("要删掉的内容")));

        assertTrue(store.delete(USER, "drop"), "应报告删除成功");

        assertTrue(store.read(USER, fileName, "drop").isEmpty(), "被删的会话不该还能读出来");
        assertFalse(allDiskContent(USER).contains("要删掉的内容"), "原文不该残留在磁盘上");
        assertEquals(List.of("要留下的内容"),
                contentsOf(store.read(USER, fileName, "keep").orElseThrow()));
        assertTrue(allDiskContent(USER).contains("要留下的内容"), "邻居内容必须完好");
    }

    @Test
    @DisplayName("删掉文件里最后一个会话后，空文件也被清掉")
    void deleteRemovesFileWhenLastSessionGone() throws IOException {
        store = new LongTermMemoryStore(root, DataSize.ofMegabytes(2));
        store.write(session(USER, "only", STARTED_AT, user("只有它")));
        Path directory = root.resolve(String.valueOf(USER));
        try (var stream = Files.list(directory)) {
            assertEquals(1, stream.count(), "写完之后应有一个正文文件");
        }

        store.delete(USER, "only");

        try (var stream = Files.list(directory)) {
            assertEquals(0, stream.count(), "只剩空文件时应把它删掉，别让目录越积越多");
        }
    }

    @Test
    @DisplayName("删除不存在的会话返回 false，不报错；重复删也返回 false")
    void deleteReturnsFalseWhenAbsent() {
        store = new LongTermMemoryStore(root, DataSize.ofMegabytes(2));
        store.write(session(USER, "exists", STARTED_AT, user("在的")));

        assertFalse(store.delete(USER, "not-exist"), "删不存在的会话：返回 false");
        assertTrue(store.delete(USER, "exists"), "第一次删：应成功");
        assertFalse(store.delete(USER, "exists"), "再删一次：已不存在");
    }

    @Test
    @DisplayName("删除会跨分片找：会话不在索引指向的那个文件里也能删掉")
    void deleteFindsSessionAcrossFiles() {
        store = new LongTermMemoryStore(root, DataSize.ofBytes(1));
        store.write(session(USER, "day-old", "2026-10-11T04:00:00Z", user("前天聊的")));
        store.write(session(USER, "day-new", STARTED_AT, user("今天聊的")));

        assertTrue(store.delete(USER, "day-old"));

        assertFalse(store.delete(USER, "day-old"), "再删一次应报告已不存在");
    }

    @Test
    @DisplayName("文件不存在或会话不在文件里时返回空，不抛异常")
    void readReturnsEmptyWhenMissing() {
        store = new LongTermMemoryStore(root, DataSize.ofMegabytes(2));
        String fileName = store.write(session(USER, "present", STARTED_AT, user("在的")));

        assertTrue(store.read(USER, "memory-1999-01-01.md", "present").isEmpty(), "文件不存在");
        assertTrue(store.read(USER, fileName, "absent").isEmpty(), "会话不在文件里");
    }

    @Test
    @DisplayName("不同用户写在各自目录下，互不可见")
    void isolatesUsers() throws IOException {
        store = new LongTermMemoryStore(root, DataSize.ofMegabytes(2));
        store.write(session(1L, "same-id", STARTED_AT, user("甲的对话")));
        store.write(session(2L, "same-id", STARTED_AT, user("乙的对话")));

        assertTrue(Files.isDirectory(root.resolve("1")));
        assertTrue(Files.isDirectory(root.resolve("2")));
        assertFalse(allDiskContent(1L).contains("乙的对话"));
        assertFalse(allDiskContent(2L).contains("甲的对话"));
    }

    @Test
    @DisplayName("读回来的消息条数与写入时一致（段头里记的条数不是摆设）")
    void readsAllMessagesOfSection() {
        store = new LongTermMemoryStore(root, DataSize.ofMegabytes(2));
        String fileName = store.write(session(USER, "conv-many", STARTED_AT,
                user("1"), assistant("2"), user("3"), assistant("4")));

        Optional<List<ChatMessage>> loaded = store.read(USER, fileName, "conv-many");
        assertEquals(4, loaded.orElseThrow().size());
    }

    @Test
    @DisplayName("readAll：同一会话多次归档取最后一段，且按用户隔离")
    void readAllReturnsLatestSectionPerConversation() {
        store = new LongTermMemoryStore(root, DataSize.ofMegabytes(2));
        store.write(session(USER, "conv-1", STARTED_AT, user("旧内容")));
        store.write(session(USER, "conv-1", STARTED_AT, user("旧内容"), assistant("新回复")));
        store.write(session(USER + 1, "conv-2", STARTED_AT, user("别人的内容")));

        List<LongTermMemoryStore.ArchivedSection> sections = store.readAll(USER);

        assertEquals(1, sections.size(), "同一会话只保留最后一段");
        assertEquals("conv-1", sections.get(0).conversationId());
        assertEquals(2, sections.get(0).messages().size());
        assertTrue(store.readAll(USER + 1).get(0).messages().get(0).content().contains("别人"),
                "各用户只能读到自己的目录");
    }
}
