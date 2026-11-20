package cn.tedu.droneagent.mcp;

import cn.tedu.droneagent.tool.ToolContext;
import cn.tedu.droneagent.tool.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link McpToolAdapter} 的测试：远程结果怎么变成 {@link ToolResult}。
 *
 * <p>重点不是 JSON 解析，而是**契约**：远程失败必须变成 {@code ok=false}，
 * 且**不能抛异常**——否则一次超时会让整个对话 500。
 */
class McpToolAdapterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("正常结果：多条 text 用换行拼接")
    void flattensTextContent() {
        StubTransport transport = new StubTransport();
        transport.replies.add("{\"content\":[{\"type\":\"text\",\"text\":\"北京 晴\"},"
                + "{\"type\":\"text\",\"text\":\"25℃\"}],\"isError\":false}");

        McpToolAdapter adapter = adapter(transport);
        ToolResult result = adapter.invoke("{\"city\":\"北京\"}");

        assertTrue(result.ok());
        assertEquals("北京 晴\n25℃", result.content());
    }

    @Test
    @DisplayName("isError=true：变成失败结果，原文回填给模型")
    void mapsIsErrorToFailed() {
        StubTransport transport = new StubTransport();
        transport.replies.add("{\"content\":[{\"type\":\"text\",\"text\":\"城市不存在\"}],\"isError\":true}");

        ToolResult result = adapter(transport).invoke("{\"city\":\"不存在\"}");

        assertFalse(result.ok());
        assertEquals("城市不存在", result.content());
    }

    @Test
    @DisplayName("远程异常：变成失败结果，不向外抛")
    void wrapsExceptionAsFailedResult() {
        StubTransport transport = new StubTransport();
        transport.failure = new McpException("连接超时");

        ToolResult result = adapter(transport).invoke("{}");

        assertFalse(result.ok());
        assertTrue(result.content().contains("连接超时"), result.content());
    }

    @Test
    @DisplayName("非文本内容：留占位标记，不让模型以为结果缺失")
    void marksNonTextContent() {
        StubTransport transport = new StubTransport();
        transport.replies.add("{\"content\":[{\"type\":\"image\",\"data\":\"...\"}],\"isError\":false}");

        ToolResult result = adapter(transport).invoke("{}");

        assertTrue(result.ok());
        assertTrue(result.content().contains("非文本结果"), result.content());
    }

    @Test
    @DisplayName("forward-authorization：把用户 Token 放进本次请求头")
    void forwardsAuthorizationHeader() {
        StubTransport transport = new StubTransport();
        transport.replies.add("{\"content\":[{\"type\":\"text\",\"text\":\"ok\"}],\"isError\":false}");
        McpToolAdapter adapter = new McpToolAdapter(
                "drone__task_list", "task_list", "drone",
                new McpClient("drone", transport, MAPPER), true, false);

        ToolResult result = adapter.invoke("{}",
                new ToolContext(Set.of("admin"), Set.of(), "jwt-abc").withTraceId("trace-1234"));

        assertTrue(result.ok());
        assertEquals("Bearer jwt-abc", transport.lastHeaders.get("Authorization"));
        assertEquals("trace-1234", transport.lastHeaders.get("X-Trace-Id"));
    }

    @Test
    @DisplayName("不转发身份时也带 traceId，但不带 Authorization")
    void forwardsTraceIdWithoutAuthorization() {
        StubTransport transport = new StubTransport();
        transport.replies.add("{\"content\":[{\"type\":\"text\",\"text\":\"ok\"}],\"isError\":false}");

        ToolResult result = adapter(transport).invoke("{}",
                ToolContext.anonymous().withTraceId("trace-5678"));

        assertTrue(result.ok());
        assertEquals("trace-5678", transport.lastHeaders.get("X-Trace-Id"));
        assertFalse(transport.lastHeaders.containsKey("Authorization"));
    }

    @Test
    @DisplayName("forward-authorization 且没有 Token：失败关闭，不发请求")
    void refusesWhenTokenMissing() {
        StubTransport transport = new StubTransport();
        transport.replies.add("{\"content\":[],\"isError\":false}");
        McpToolAdapter adapter = new McpToolAdapter(
                "drone__task_list", "task_list", "drone",
                new McpClient("drone", transport, MAPPER), true, false);

        ToolResult result = adapter.invoke("{}", ToolContext.anonymous());

        assertFalse(result.ok());
        assertTrue(result.content().contains("没有携带登录凭证"), result.content());
        assertTrue(transport.lastHeaders.isEmpty(), "不应发出任何请求");
    }

    private static McpToolAdapter adapter(StubTransport transport) {
        McpClient client = new McpClient("amap", transport, MAPPER);
        return new McpToolAdapter("amap__maps_weather", "maps_weather", "amap", client, false, false);
    }

    /** 只回 tools/call 结果的极简传输 */
    private static final class StubTransport implements McpTransport {
        private final Deque<String> replies = new ArrayDeque<>();
        private RuntimeException failure;
        private Map<String, String> lastHeaders = Map.of();

        @Override
        public JsonNode request(JsonNode rpc) {
            return request(rpc, Map.of());
        }

        @Override
        public JsonNode request(JsonNode rpc, Map<String, String> headers) {
            this.lastHeaders = headers;
            if (failure != null) {
                throw failure;
            }
            ObjectNode message = MAPPER.createObjectNode();
            message.put("jsonrpc", "2.0");
            message.put("id", rpc.path("id").asInt());
            try {
                message.set("result", MAPPER.readTree(replies.removeFirst()));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            return message;
        }

        @Override
        public void notify(JsonNode rpc) {
            // 本测试不关心通知
        }

        @Override
        public String sessionId() {
            return null;
        }
    }
}
