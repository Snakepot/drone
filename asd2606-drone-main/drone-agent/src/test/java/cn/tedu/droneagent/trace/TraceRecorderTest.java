package cn.tedu.droneagent.trace;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TraceRecorder} 的落盘、归属与非法输入。
 */
class TraceRecorderTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("记录根与子 span：能按 userId 读回，父子关系正确")
    void recordsAndFindsSpans() {
        TraceRecorder recorder = new TraceRecorder(tempDir, Duration.ofDays(7), new ObjectMapper(), true);
        String traceId = "trace-1234";
        String root = recorder.beginRoot(5L, traceId);
        String start = Instant.now().toString();

        recorder.record(5L, traceId, root, "tool.call", start, 12, "ok",
                Map.of("tool", "drone__task_list", "resultChars", 1350));
        recorder.finishRoot(5L, traceId, root, start, 20, "ok", Map.of("replyChars", 10));

        List<TraceSpan> spans = recorder.find(5L, traceId);

        assertEquals(2, spans.size());
        assertTrue(spans.stream().anyMatch(span -> "tool.call".equals(span.name())
                && root.equals(span.parentId())
                && "drone__task_list".equals(span.attrs().get("tool"))));
        assertTrue(spans.stream().anyMatch(span -> "chat.request".equals(span.name())
                && span.parentId() == null));
    }

    @Test
    @DisplayName("归属隔离：换个 userId 读不到；非法 traceId 直接拒绝")
    void isolatesByUserAndRejectsBadId() {
        TraceRecorder recorder = new TraceRecorder(tempDir, Duration.ofDays(7), new ObjectMapper(), true);
        String traceId = "trace-5678";
        String root = recorder.beginRoot(5L, traceId);
        recorder.finishRoot(5L, traceId, root, Instant.now().toString(), 1, "ok", Map.of());

        assertTrue(recorder.find(6L, traceId).isEmpty());
        assertTrue(recorder.find(5L, "../../etc/passwd").isEmpty());
        assertTrue(recorder.find(5L, "short").isEmpty());
    }

    @Test
    @DisplayName("关闭时全部是空操作：不落盘、读不到、不报错")
    void noopDoesNothing() {
        TraceRecorder recorder = TraceRecorder.noop();
        recorder.record(5L, "trace-9999", null, "tool.call",
                Instant.now().toString(), 1, "ok", Map.of());

        assertFalse(recorder.enabled());
        assertTrue(recorder.find(5L, "trace-9999").isEmpty());
    }
}
