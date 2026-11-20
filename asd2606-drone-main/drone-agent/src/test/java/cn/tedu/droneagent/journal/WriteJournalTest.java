package cn.tedu.droneagent.journal;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 写操作台账（REQ-AGENT-0029）：一行一条、只记元数据。 */
class WriteJournalTest {

    @TempDir
    Path root;

    @Test
    @DisplayName("写台账：追加一行，含 operationId/状态/对象 ID，不含参数原文")
    void appendsMetadataOnly() throws Exception {
        WriteJournal journal = new WriteJournal(root, new ObjectMapper());

        journal.record("op-1", "trace-1", 5L, "conv-1", "drone__user_add",
                "{\"phone\":\"13800001009\"}", "succeeded", "123");

        List<String> lines = Files.readAllLines(root.resolve("operations-" + java.time.LocalDate.now() + ".jsonl"),
                StandardCharsets.UTF_8);
        assertEquals(1, lines.size());
        assertTrue(lines.get(0).contains("\"operationId\":\"op-1\""));
        assertTrue(lines.get(0).contains("\"status\":\"succeeded\""));
        assertTrue(lines.get(0).contains("\"objectId\":\"123\""));
        assertTrue(!lines.get(0).contains("13800001009"), "参数原文不能进台账");
    }

    @Test
    @DisplayName("关闭时 noop：不创建目录、不报错")
    void noopDoesNothing() {
        WriteJournal.noop().record("op", "t", 1L, "c", "tool", "{}", "started", null);
        assertTrue(Files.notExists(root.resolve("operations-" + java.time.LocalDate.now() + ".jsonl")));
    }
}
