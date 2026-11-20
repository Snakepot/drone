package cn.tedu.droneagent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link McpClient} 的协议层测试。
 *
 * <p>这里用**假的传输**（记录请求、按脚本回包），不碰网络：
 * 传输本身的边界已在 {@code StreamableHttpTransportTest} 覆盖，
 * 这一层只关心「发了哪些 RPC、参数对不对、分页会不会死循环」。
 */
class McpClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("initialize：发送协议版本，完成后发 notifications/initialized")
    void sendsInitializeThenNotification() {
        FakeTransport transport = new FakeTransport();
        transport.replies.add(message(1, "result", resultWithProtocol("2025-03-26")));

        McpClient client = new McpClient("amap", transport, MAPPER);
        String version = client.initialize();

        assertEquals("2025-03-26", version);
        assertEquals("initialize", transport.requests.get(0).path("method").asText());
        assertEquals("2025-03-26",
                transport.requests.get(0).path("params").path("protocolVersion").asText());
        assertEquals("notifications/initialized", transport.notifications.get(0).path("method").asText());
    }

    @Test
    @DisplayName("initialize 失败不致命：继续 listTools（兼容无握手的 2026-07-28 服务器）")
    void toleratesInitializeFailure() {
        FakeTransport transport = new FakeTransport();
        transport.failOnNextRequest = new McpException("服务器不认识 initialize");
        transport.replies.add(message(1, "result", toolsPage("maps_weather", null)));

        McpClient client = new McpClient("amap", transport, MAPPER);
        String version = client.initialize();
        List<McpToolInfo> tools = client.listTools();

        assertEquals("unknown", version);
        assertEquals(1, tools.size());
        assertEquals("maps_weather", tools.get(0).name());
        assertTrue(transport.notifications.isEmpty(), "握手失败时不应发送 initialized 通知");
    }

    @Test
    @DisplayName("tools/list：按 nextCursor 翻页，并把 cursor 回传")
    void followsCursor() {
        FakeTransport transport = new FakeTransport();
        transport.replies.add(message(1, "result", toolsPage("first_tool", "cursor-2")));
        transport.replies.add(message(2, "result", toolsPage("second_tool", null)));

        McpClient client = new McpClient("amap", transport, MAPPER);
        List<McpToolInfo> tools = client.listTools();

        assertEquals(List.of("first_tool", "second_tool"), tools.stream().map(McpToolInfo::name).toList());
        assertEquals("cursor-2", transport.requests.get(1).path("params").path("cursor").asText());
    }

    @Test
    @DisplayName("tools/call：把别名去掉，用远程原名调用，参数原样传对象")
    void callsRemoteToolWithArguments() {
        FakeTransport transport = new FakeTransport();
        transport.replies.add(message(1, "result",
                "{\"content\":[{\"type\":\"text\",\"text\":\"晴 25 度\"}],\"isError\":false}"));

        McpClient client = new McpClient("amap", transport, MAPPER);
        JsonNode result = client.callTool("maps_weather", "{\"city\":\"北京\"}");

        JsonNode request = transport.requests.get(0);
        assertEquals("tools/call", request.path("method").asText());
        assertEquals("maps_weather", request.path("params").path("name").asText());
        assertEquals("北京", request.path("params").path("arguments").path("city").asText());
        assertEquals("晴 25 度", result.path("content").get(0).path("text").asText());
    }

    @Test
    @DisplayName("tools/list 没有 tools 字段：按空列表处理，不报错")
    void emptyToolListIsTolerated() {
        FakeTransport transport = new FakeTransport();
        transport.replies.add(message(1, "result", "{}"));

        McpClient client = new McpClient("amap", transport, MAPPER);

        assertTrue(client.listTools().isEmpty());
    }

    // ---------- 桩 ----------

    private static final class FakeTransport implements McpTransport {
        private final List<JsonNode> requests = new ArrayList<>();
        private final List<JsonNode> notifications = new ArrayList<>();
        private final Deque<JsonNode> replies = new ArrayDeque<>();
        private RuntimeException failOnNextRequest;
        private String sessionId;

        @Override
        public JsonNode request(JsonNode rpc) {
            requests.add(rpc);
            if (failOnNextRequest != null) {
                RuntimeException failure = failOnNextRequest;
                failOnNextRequest = null;
                throw failure;
            }
            return replies.removeFirst();
        }

        @Override
        public void notify(JsonNode rpc) {
            notifications.add(rpc);
        }

        @Override
        public String sessionId() {
            return sessionId;
        }
    }

    private static JsonNode message(int id, String resultField, String resultJson) {
        ObjectNode message = MAPPER.createObjectNode();
        message.put("jsonrpc", "2.0");
        message.put("id", id);
        try {
            message.set(resultField, MAPPER.readTree(resultJson));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return message;
    }

    private static String resultWithProtocol(String version) {
        return "{\"protocolVersion\":\"" + version + "\",\"capabilities\":{}}";
    }

    private static String toolsPage(String toolName, String nextCursor) {
        String cursor = nextCursor == null ? "" : ",\"nextCursor\":\"" + nextCursor + "\"";
        return "{\"tools\":[{\"name\":\"" + toolName + "\",\"description\":\"d\","
                + "\"inputSchema\":{\"type\":\"object\"}}]" + cursor + "}";
    }
}
