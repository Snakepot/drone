package cn.tedu.droneagent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 流式分片拼接：content / reasoning / tool_calls（按 index 分片）与思考用时。
 */
class StreamAssemblerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode delta(String deltaJson) {
        try {
            return MAPPER.readTree("{\"choices\":[{\"delta\":" + deltaJson + "}]}");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("正文与思考分别累积，增量按到达顺序返回")
    void accumulatesContentAndReasoning() {
        StreamAssembler assembler = new StreamAssembler();

        StreamAssembler.Increment first = assembler.accept(delta(
                "{\"reasoning_content\":\"先看\",\"content\":\"今日\"}"), 10);
        StreamAssembler.Increment second = assembler.accept(delta(
                "{\"reasoning_content\":\"任务\",\"content\":\"完成\"}"), 20);

        assertEquals("先看", first.reasoningDelta());
        assertEquals("今日", first.contentDelta());
        assertEquals("任务", second.reasoningDelta());
        assertEquals("今日完成", assembler.content());
        assertEquals("先看任务", assembler.reasoning());
    }

    @Test
    @DisplayName("tool_calls 按 index 合并：id/name 首片给，arguments 分片拼接")
    void mergesToolCallFragments() {
        StreamAssembler assembler = new StreamAssembler();

        assembler.accept(delta("{\"tool_calls\":[{\"index\":0,\"id\":\"call_1\","
                + "\"function\":{\"name\":\"task_list\",\"arguments\":\"{\\\"page\\\":\"}}]}"), 1);
        assembler.accept(delta("{\"tool_calls\":[{\"index\":0,"
                + "\"function\":{\"arguments\":\"2}\"}}]}"), 2);

        List<cn.tedu.droneagent.tool.ToolCall> calls = assembler.toolCalls();
        assertEquals(1, calls.size());
        assertEquals("call_1", calls.get(0).id());
        assertEquals("task_list", calls.get(0).name());
        assertEquals("{\"page\":2}", calls.get(0).argumentsJson());
    }

    @Test
    @DisplayName("只调工具、没有正文：content 为 null，用时为 0")
    void toolOnlyRoundHasNoContent() {
        StreamAssembler assembler = new StreamAssembler();
        assembler.accept(delta("{\"tool_calls\":[{\"index\":0,\"id\":\"c1\","
                + "\"function\":{\"name\":\"f\",\"arguments\":\"{}\"}}]}"), 999);

        assertNull(assembler.content());
        assertEquals(0, assembler.reasoningMs());
    }

    @Test
    @DisplayName("思考用时取首个正文增量，后续增量不再改变")
    void reasoningTimeUsesFirstContent() {
        StreamAssembler assembler = new StreamAssembler();

        assembler.accept(delta("{\"reasoning_content\":\"想\"}"), 500_000_000L);
        assembler.accept(delta("{\"content\":\"答\"}"), 1_500_000_000L);
        assembler.accept(delta("{\"content\":\"案\"}"), 3_000_000_000L);

        assertEquals(1500, assembler.reasoningMs());
        assertEquals("答案", assembler.content());
    }
}
