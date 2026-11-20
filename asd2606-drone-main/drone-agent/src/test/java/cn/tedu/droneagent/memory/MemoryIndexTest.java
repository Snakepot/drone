package cn.tedu.droneagent.memory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MemoryIndex} 的单元测试。
 *
 * <p>对应验收条件：`docs/sdd/specs/REQ-AGENT-0003-会话与长期记忆.md` 的
 * <b>AC-8</b>（索引存在且能定位到文件）、<b>AC-13</b>（删 A 不伤 B）、
 * <b>AC-17</b>（只含本人）——后两条这里覆盖的是**索引侧**，端到端在 T-13 验。
 *
 * <p>时间断言刻意都用**整分钟**的时间点：索引按设计只精确到分钟，
 * 用带秒的时间点做相等断言会失败——`indexOnlyKeepsMinutePrecision` 专门把这一点钉住。
 */
class MemoryIndexTest {

    private static final long USER = 1001L;

    @TempDir
    Path root;

    private MemoryIndex index;

    private static IndexEntry entry(String conversationId, String timeIso, int count, String title, String file) {
        return new IndexEntry(conversationId, Instant.parse(timeIso), count, title, file);
    }

    private Path indexFileOf(long userId) {
        return root.resolve(String.valueOf(userId)).resolve("memory.md");
    }

    @Test
    @DisplayName("索引文件不存在时返回空列表，不抛异常（新用户本来就没有历史）")
    void listReturnsEmptyWhenNoIndexFile() {
        index = new MemoryIndex(root);

        assertTrue(index.list(USER).isEmpty());
        assertFalse(Files.exists(indexFileOf(USER)));
    }

    @Test
    @DisplayName("AC-8 写入后能解析回等价数据，且索引落在该用户自己的目录下")
    void appendThenListRoundTrips() {
        index = new MemoryIndex(root);
        IndexEntry original = entry("conv-a", "2026-10-12T06:32:00Z", 12, "电池低温报警", "memory-2026-10-12.md");

        index.append(USER, original);
        List<IndexEntry> loaded = index.list(USER);

        assertEquals(List.of(original), loaded);
        assertTrue(Files.exists(indexFileOf(USER)), "索引应写在该用户目录下");
    }

    @Test
    @DisplayName("AC-8 列表按写入顺序倒序：最近归档的在最前")
    void listReturnsNewestFirst() {
        index = new MemoryIndex(root);
        index.append(USER, entry("old", "2026-10-01T01:00:00Z", 2, "早", "f1.md"));
        index.append(USER, entry("new", "2026-10-02T01:00:00Z", 2, "晚", "f2.md"));

        assertEquals(List.of("new", "old"), index.list(USER).stream().map(IndexEntry::conversationId).toList());
    }

    @Test
    @DisplayName("AC-17 不同用户的索引互不可见")
    void isolatesUsers() {
        index = new MemoryIndex(root);
        index.append(1L, entry("mine", "2026-10-01T01:00:00Z", 1, "甲的", "f.md"));
        index.append(2L, entry("theirs", "2026-10-01T01:00:00Z", 1, "乙的", "f.md"));

        assertEquals(List.of("mine"), index.list(1L).stream().map(IndexEntry::conversationId).toList());
        assertEquals(List.of("theirs"), index.list(2L).stream().map(IndexEntry::conversationId).toList());
    }

    @Test
    @DisplayName("标题里的换行与字段分隔符被清洗，超长被截断，保证一行一条")
    void sanitizesTitle() {
        index = new MemoryIndex(root);
        index.append(USER, entry("conv-b", "2026-10-12T06:00:00Z", 1, "第一行\n第二行 | 带竖线", "f.md"));
        index.append(USER, entry("conv-c", "2026-10-12T06:01:00Z", 1, "标".repeat(100), "f.md"));

        List<IndexEntry> loaded = index.list(USER);
        assertEquals("第一行/第二行 / 带竖线", loaded.get(1).title(), "换行与竖线应被替换成 /");
        assertEquals(40, loaded.get(0).title().length(), "超长标题应被截断到 40 字符");
        // 关键：无论标题多脏，索引仍然是一行一条
        assertEquals(2, loaded.size());
    }

    @Test
    @DisplayName("索引只保留到分钟：秒会被丢掉（这是有意的精度取舍）")
    void indexOnlyKeepsMinutePrecision() throws IOException {
        index = new MemoryIndex(root);
        index.append(USER, entry("conv-d", "2026-10-12T06:32:45Z", 1, "带秒", "f.md"));

        IndexEntry loaded = index.list(USER).get(0);
        assertFalse(loaded.startedAt().equals(Instant.parse("2026-10-12T06:32:45Z")), "秒不该被保留");
        assertEquals(0, loaded.startedAt().getEpochSecond() % 60, "落库时间应落在整分钟上");
    }

    @Test
    @DisplayName("单行损坏时跳过该行，其余条目照常可读")
    void skipsMalformedLine() throws IOException {
        index = new MemoryIndex(root);
        index.append(USER, entry("good-1", "2026-10-12T06:00:00Z", 3, "好的", "f.md"));

        Path file = indexFileOf(USER);
        Files.writeString(file, "这一行完全不是索引格式\n还有 |- 这种残缺的\n",
                StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);

        List<IndexEntry> loaded = index.list(USER);
        assertEquals(1, loaded.size(), "坏行应被跳过，好行不能受影响");
        assertEquals("good-1", loaded.get(0).conversationId());
    }

    @Test
    @DisplayName("AC-13 删除某会话只影响它自己，同文件内其他条目完好")
    void removeDropsOnlyTargetConversation() {
        index = new MemoryIndex(root);
        index.append(USER, entry("keep-a", "2026-10-01T01:00:00Z", 1, "留着 A", "f1.md"));
        index.append(USER, entry("drop-me", "2026-10-02T01:00:00Z", 1, "要删的", "f2.md"));
        index.append(USER, entry("keep-b", "2026-10-03T01:00:00Z", 1, "留着 B", "f3.md"));

        index.remove(USER, "drop-me");

        assertEquals(List.of("keep-b", "keep-a"),
                index.list(USER).stream().map(IndexEntry::conversationId).toList());
    }

    @Test
    @DisplayName("删掉最后一个条目后，索引文件本身也被清掉")
    void removeDeletesIndexFileWhenLastEntryGone() {
        index = new MemoryIndex(root);
        index.append(USER, entry("only", "2026-10-12T06:00:00Z", 1, "只有它", "f.md"));

        index.remove(USER, "only");

        assertFalse(Files.exists(indexFileOf(USER)), "空了就该删文件，别留 0 字节的索引");
        assertTrue(index.list(USER).isEmpty());
    }

    @Test
    @DisplayName("删除不存在的会话是空操作，不报错也不改动文件")
    void removeIsNoOpWhenAbsent() throws IOException {
        index = new MemoryIndex(root);
        index.append(USER, entry("only", "2026-10-12T06:00:00Z", 1, "只有它", "f.md"));
        String before = Files.readString(indexFileOf(USER), StandardCharsets.UTF_8);

        index.remove(USER, "not-exist");

        assertEquals(before, Files.readString(indexFileOf(USER), StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("用户目录不存在时首次追加会自动创建")
    void appendCreatesUserDirectory() {
        Path nestedRoot = root.resolve("nested").resolve("memory-root");
        index = new MemoryIndex(nestedRoot);

        index.append(USER, entry("conv-e", "2026-10-12T06:00:00Z", 1, "首次", "f.md"));

        assertTrue(Files.exists(nestedRoot.resolve(String.valueOf(USER)).resolve("memory.md")),
                "嵌套的根目录与用户目录都应被创建出来");
    }
}
