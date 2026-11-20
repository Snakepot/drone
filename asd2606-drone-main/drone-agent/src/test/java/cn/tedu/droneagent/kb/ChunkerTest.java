package cn.tedu.droneagent.kb;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Chunker} 的单元测试。
 *
 * <p>对应验收条件：`REQ-AGENT-0010` 的 <b>AC-6</b> 的一半——保证切出来的块
 * **不超过上限**、**带得出处**、**相邻有重叠**、**不丢内容**。
 */
class ChunkerTest {

    @Test
    @DisplayName("不超上限的章节整段作一块，并带上出处")
    void shortSectionIsSingleChunk() {
        List<Chunk> chunks = new Chunker(100, 20)
                .chunk(List.of(new DocumentSection(List.of("手册", "保养"), "电池要保养。")));

        assertEquals(1, chunks.size());
        assertEquals("手册 › 保养", chunks.get(0).sectionPath());
        assertEquals("电池要保养。", chunks.get(0).text());
    }

    @Test
    @DisplayName("超长章节按上限切成多块，每块都不超上限，且末尾内容不丢")
    void longSectionSplitsWithinLimit() {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < 60; i++) {
            body.append("第").append(i).append("行内容，用于切块测试。\n");
        }
        body.append("末尾标记");
        String text = body.toString();

        List<Chunk> chunks = new Chunker(100, 20)
                .chunk(List.of(new DocumentSection(List.of("手册"), text)));

        assertTrue(chunks.size() > 5, "1200 字的正文按 100 字上限应切出多块");
        chunks.forEach(c -> assertTrue(c.text().length() <= 100,
                "每块都必须 ≤ 上限，实际 " + c.text().length()));
        assertTrue(chunks.get(chunks.size() - 1).text().contains("末尾标记"), "末尾内容不能丢");
        assertTrue(String.join("", chunks.stream().map(Chunk::text).toList()).contains("第0行"),
                "开头内容不能丢");
    }

    @Test
    @DisplayName("相邻块按 overlap-chars 重叠（避免关键句落在边界上两边都不完整）")
    void adjacentChunksOverlap() {
        String body = "0123456789".repeat(25); // 250 字符，无换行 → 只能按字符硬切
        List<Chunk> chunks = new Chunker(100, 30).chunk(List.of(new DocumentSection(List.of("x"), body)));

        // 250 字符、每块 100、重叠 30 → 起点依次 0 / 70 / 140 / 210，共 4 块
        assertEquals(4, chunks.size());
        assertEquals(100, chunks.get(0).text().length());
        // 第二块从第 70 个字符开始（= 100 - 30），故前 30 个字符应与第一块的尾部一致
        assertEquals(body.substring(70, 100), chunks.get(1).text().substring(0, 30),
                "相邻块应有 30 个字符的重叠");
    }

    @Test
    @DisplayName("空正文的章节不产出块")
    void blankSectionSkipped() {
        List<Chunk> chunks = new Chunker(100, 20).chunk(List.of(
                new DocumentSection(List.of("a"), "   \n  "),
                new DocumentSection(List.of("b"), "")));

        assertTrue(chunks.isEmpty());
    }

    @Test
    @DisplayName("算向量用的文本带章节路径；无路径时不加前缀")
    void embeddingTextPrefixesSectionPath() {
        assertEquals("手册 › 保养\n电池要保养。", new Chunk("手册 › 保养", "电池要保养。").embeddingText());
        assertEquals("孤零零一段", new Chunk("", "孤零零一段").embeddingText());
    }

    @Test
    @DisplayName("同输入同输出（可重复，便于回归对比）")
    void deterministic() {
        String body = "要点一。\n要点二。\n".repeat(30);
        Chunker chunker = new Chunker(80, 10);
        List<DocumentSection> input = List.of(new DocumentSection(List.of("x"), body));

        assertEquals(chunker.chunk(input), chunker.chunk(input));
    }

    @Test
    @DisplayName("重叠不该 ≥ 上限：会被夹住，且循环不卡死")
    void overlapIsClamped() {
        List<Chunk> chunks = new Chunker(10, 99)
                .chunk(List.of(new DocumentSection(List.of("x"), "0123456789".repeat(5))));

        assertFalse(chunks.isEmpty());
        chunks.forEach(c -> assertTrue(c.text().length() <= 10));
    }
}
