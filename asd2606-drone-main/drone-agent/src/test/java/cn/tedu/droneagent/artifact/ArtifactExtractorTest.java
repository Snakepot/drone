package cn.tedu.droneagent.artifact;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 抽取规则：只认「完整 HTML 文档」，围栏与裸文档都支持，超限不抽。
 */
class ArtifactExtractorTest {

    private static final String DOC = "<!DOCTYPE html><html><head><title>运营日报 · 2026-10-22</title></head>"
            + "<body>report</body></html>";

    @Test
    @DisplayName("代码围栏：抽出 HTML，回复只留摘要，文件名取 title")
    void extractsFencedDocument() {
        ArtifactExtractor extractor = new ArtifactExtractor(4096);
        String reply = "今日完成 8 条，完成率 88.9%。\n\n```html\n" + DOC + "\n```\n";

        Optional<ArtifactExtractor.Extracted> result = extractor.extract(reply);

        assertTrue(result.isPresent());
        assertTrue(result.get().html().startsWith("<!DOCTYPE html>"));
        assertEquals("今日完成 8 条，完成率 88.9%。", result.get().cleanedReply());
        assertEquals("运营日报 · 2026-10-22.html", result.get().fileName());
    }

    @Test
    @DisplayName("裸文档（模型没加围栏）也能抽")
    void extractsRawDocument() {
        ArtifactExtractor extractor = new ArtifactExtractor(4096);
        String reply = "结论如下。\n" + DOC;

        Optional<ArtifactExtractor.Extracted> result = extractor.extract(reply);

        assertTrue(result.isPresent());
        assertEquals("结论如下。", result.get().cleanedReply());
    }

    @Test
    @DisplayName("普通 HTML 片段不是产物")
    void ignoresFragments() {
        ArtifactExtractor extractor = new ArtifactExtractor(4096);

        assertFalse(extractor.extract("可以这样写：<div>hi</div>").isPresent());
        assertFalse(extractor.extract("纯文本回答").isPresent());
    }

    @Test
    @DisplayName("超过上限不抽（回复原样返回）")
    void ignoresOversized() {
        ArtifactExtractor extractor = new ArtifactExtractor(10);

        assertFalse(extractor.extract(DOC).isPresent());
    }

    @Test
    @DisplayName("没有 title 时用时间戳兜底，且总是 .html")
    void fallsBackToTimestampName() {
        ArtifactExtractor extractor = new ArtifactExtractor(4096);
        String doc = "<html><body>no title</body></html>";

        String name = extractor.fileName(doc);

        assertTrue(name.startsWith("report-"), name);
        assertTrue(name.endsWith(".html"), name);
    }

    @Test
    @DisplayName("回复里只有 HTML 时，清洗后给一行提示，不留空")
    void cleanedReplyNeverBlank() {
        ArtifactExtractor extractor = new ArtifactExtractor(4096);

        String cleaned = extractor.extract("```html\n" + DOC + "\n```").orElseThrow().cleanedReply();

        assertFalse(cleaned.isBlank());
        assertTrue(cleaned.contains("HTML 报告已生成"));
    }
}
