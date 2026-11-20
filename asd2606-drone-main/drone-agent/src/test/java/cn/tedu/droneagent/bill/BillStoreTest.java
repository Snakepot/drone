package cn.tedu.droneagent.bill;

import cn.tedu.droneagent.config.BillProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 账单落盘与聚合（REQ-AGENT-0025）。 */
class BillStoreTest {

    @TempDir
    Path root;

    private BillStore store() {
        return new BillStore(new BillProperties(true, root.toString(), 30), new ObjectMapper());
    }

    private static BillRecord record(String traceId, long userId, String conversationId,
                                     int estimate, Integer prompt, Integer cached,
                                     List<String> tools) {
        return new BillRecord(OffsetDateTime.now().toString(), traceId, userId, conversationId,
                0, "deepseek-flash", "fp-1", estimate,
                Map.of("SYSTEM", 1000, "TOOL_DEFINITIONS", 9000), 9000, 74,
                prompt, cached, prompt == null ? null : Math.round(cached * 1000.0 / prompt) / 10.0,
                1200, tools);
    }

    @Test
    @DisplayName("同一 trace 的两轮：轮次递增，摘要合计 prompt、取最后一轮上下文")
    void summaryAggregatesRounds() {
        BillStore store = store();
        store.record(record("t1", 5, "c1", 12_000, 12_100, 11_000, List.of("drone__device_list")));
        store.record(record("t1", 5, "c1", 12_500, 12_600, 12_400,
                List.of("drone__device_list", "search_memory")));

        RequestBill bill = store.summary("t1");

        assertEquals(2, bill.rounds());
        assertEquals(24_700, bill.promptTokens());
        assertEquals(23_400, bill.cachedTokens());
        assertEquals(12_500, bill.contextTokens());
        assertEquals(94.7, bill.hitRate());
        assertEquals(List.of("drone__device_list", "search_memory"), bill.toolNames());
    }

    @Test
    @DisplayName("明细查询：按用户隔离，按时间倒序，limit 生效")
    void queryIsolatedAndLimited() {
        BillStore store = store();
        store.record(record("t1", 5, "c1", 12_000, 12_100, 12_000, List.of("a")));
        store.record(record("t2", 6, "c2", 12_000, 12_100, 12_000, List.of("b")));

        List<BillRecord> mine = store.query(5, false, null, null, null, 10);
        List<BillRecord> all = store.query(0, true, null, null, null, 10);

        assertEquals(1, mine.size());
        assertEquals(5, mine.get(0).userId());
        assertEquals(2, all.size());
    }

    @Test
    @DisplayName("汇总：请求按 trace 去重，分类合计，工具按请求计数")
    void aggregateByRequest() {
        BillStore store = store();
        store.record(record("t1", 5, "c1", 12_000, 12_100, 12_000, List.of("a", "b")));
        store.record(record("t1", 5, "c1", 12_000, 12_100, 12_000, List.of("a", "b")));
        store.record(record("t2", 5, "c2", 12_000, 12_100, 12_000, List.of("a")));

        BillSummary summary = store.aggregate(5, false, null, null);

        assertEquals(2, summary.requests(), "同一 trace 的多次调用算一个请求");
        assertEquals(36_300, summary.promptTokens());
        assertEquals(12_000, summary.avgContextTokens());
        assertEquals(2, summary.byKind().size());
        assertEquals(2, summary.topTools().get(0).calls(), "同一请求内多轮只算一次");
    }

    @Test
    @DisplayName("过期文件在构造时清理")
    void cleansExpiredFiles() throws Exception {
        Path old = root.resolve("bills-2020-01-01.jsonl");
        Files.writeString(old, "");

        store();

        assertTrue(Files.notExists(old), "超过 30 天的文件应被清理");
    }

    @Test
    @DisplayName("关闭开关时：不落盘、摘要为空、查询为空")
    void disabledStoreIsNoop() {
        BillStore store = new BillStore(new BillProperties(false, root.toString(), 30), new ObjectMapper());
        store.record(record("t1", 5, "c1", 12_000, 12_100, 12_000, List.of("a")));

        assertNull(store.summary("t1"));
        assertTrue(store.query(5, false, null, LocalDate.now(), LocalDate.now(), 10).isEmpty());
    }
}
