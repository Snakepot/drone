package cn.tedu.droneagent.tool;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ToolResultCompressor} 的单元测试。
 *
 * <p>对应验收条件：`docs/sdd/specs/REQ-AGENT-0007-编排模式优化.md` 的
 * <b>AC-3</b>（超长结果被压缩到阈值内，且尽量保要点）。
 */
class ToolResultCompressorTest {

    private final ToolResultCompressor noModel = new ToolResultCompressor(null);

    @Test
    @DisplayName("够短就原样返回，不做任何处理")
    void shortContentUntouched() {
        assertEquals("短内容", noModel.compress("短内容", 100));
    }

    @Test
    @DisplayName("结构化裁剪：去链接、并连续重复行、压空行")
    void structuredTrimRemovesNoise() {
        String raw = String.join("\n",
                "标题A",
                "标题A",
                "详情 https://example.com/a/b",
                "",
                "",
                "标题B");

        String trimmed = noModel.structuredTrim(raw);

        assertEquals("标题A\n详情\n\n标题B", trimmed);
    }

    @Test
    @DisplayName("结构裁剪已够短：不调模型摘要")
    void doesNotCallModelWhenTrimEnough() {
        boolean[] called = {false};
        ToolResultCompressor compressor = new ToolResultCompressor((content, max) -> {
            called[0] = true;
            return "不该走到这里";
        });
        String raw = "重复行\n".repeat(7);   // 28 字，但只含重复的一行：裁剪后可落到 20 字内

        String result = compressor.compress(raw, 20);

        assertEquals("重复行", result);
        assertFalse(called[0], "裁剪后已够短，不该调模型摘要");
    }

    @Test
    @DisplayName("裁剪后仍超长 → 走模型摘要")
    void fallsBackToModelSummary() {
        ToolResultCompressor compressor = new ToolResultCompressor((content, max) -> "摘要要点");
        String raw = "y".repeat(500);

        assertEquals("摘要要点", compressor.compress(raw, 50));
    }

    @Test
    @DisplayName("模型摘要失败 → 退回硬截断，仍不超上限")
    void modelSummaryFailureFallsBackToTruncate() {
        ToolResultCompressor compressor = new ToolResultCompressor((content, max) -> {
            throw new IllegalStateException("模型不可用");
        });
        String raw = "z".repeat(500);

        String result = compressor.compress(raw, 50);

        assertTrue(result.length() <= 50, "硬截断也要 ≤ 上限，实际=" + result.length());
        assertTrue(result.endsWith("…"));
    }
}
