package cn.tedu.droneagent.memory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link MemoryAuditLog} 的单元测试：删除要留下可长期查证的痕迹 */
class MemoryAuditLogTest {

    private static final long USER = 8008L;

    @TempDir
    Path root;

    @Test
    @DisplayName("每删一次追加一行，含时间、动作、用户与会话")
    void appendsOneLinePerDelete() throws IOException {
        MemoryAuditLog audit = new MemoryAuditLog(root);

        audit.recordDelete(USER, "conv-a");
        audit.recordDelete(USER, "conv-b");

        Path file = root.resolve("audit.log");
        assertTrue(Files.exists(file));
        var lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        assertEquals(2, lines.size());
        assertTrue(lines.get(0).contains("DELETE"), "应标出动作");
        assertTrue(lines.get(0).contains("userId=" + USER));
        assertTrue(lines.get(0).contains("conv=conv-a"));
        assertTrue(lines.get(1).contains("conv=conv-b"));
    }

    @Test
    @DisplayName("根目录不存在时自动创建")
    void createsRootWhenMissing() {
        MemoryAuditLog audit = new MemoryAuditLog(root.resolve("nested"));

        audit.recordDelete(USER, "conv-a");

        assertTrue(Files.exists(root.resolve("nested").resolve("audit.log")));
    }
}
