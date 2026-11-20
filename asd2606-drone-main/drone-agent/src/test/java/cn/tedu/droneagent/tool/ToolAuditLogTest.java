package cn.tedu.droneagent.tool;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ToolAuditLog} 的单元测试。
 *
 * <p>对应验收条件：`docs/sdd/specs/REQ-AGENT-0006-危险工具治理.md` 的 <b>AC-6</b>（危险工具执行留审计）。
 */
class ToolAuditLogTest {

    @TempDir
    Path root;

    @Test
    @DisplayName("AC-6 记一条审计：含 userId / 工具 / 结果，且参数已脱敏")
    void recordsMaskedLine() throws IOException {
        ToolAuditLog audit = new ToolAuditLog(root);

        audit.recordExecution(1001L, "delete_device", "{\"phone\":\"13800138000\"}", true);

        List<String> lines = Files.readAllLines(root.resolve("tool-audit.log"));
        assertEquals(1, lines.size());
        assertTrue(lines.get(0).contains("userId=1001"));
        assertTrue(lines.get(0).contains("tool=delete_device"));
        assertTrue(lines.get(0).contains("result=OK"));
        assertFalse(lines.get(0).contains("13800138000"), "参数必须脱敏后再落盘");
    }

    @Test
    @DisplayName("多次执行追加多行，不覆盖")
    void appendsNotOverwrites() throws IOException {
        ToolAuditLog audit = new ToolAuditLog(root);

        audit.recordExecution(1L, "delete_device", "{}", true);
        audit.recordExecution(1L, "delete_device", "{}", false);

        List<String> lines = Files.readAllLines(root.resolve("tool-audit.log"));
        assertEquals(2, lines.size());
        assertTrue(lines.get(0).contains("result=OK"));
        assertTrue(lines.get(1).contains("result=FAILED"));
    }

    @Test
    @DisplayName("no-op 实例不落盘、不抛异常")
    void noopWritesNothing() {
        ToolAuditLog.noop().recordExecution(1L, "any", "{}", false);
    }
}
