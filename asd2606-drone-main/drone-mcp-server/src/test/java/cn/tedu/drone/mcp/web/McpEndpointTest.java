package cn.tedu.drone.mcp.web;

import cn.tedu.drone.mcp.catalog.ToolCatalogLoader;
import cn.tedu.drone.mcp.config.McpServerProperties;
import cn.tedu.drone.mcp.invoke.ToolInvoker;
import cn.tedu.drone.mcp.invoke.UpstreamClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MCP 端点的协议测试：不发真实 HTTP（直接调方法），只验证 JSON-RPC 的约定。
 *
 * <p>上游地址指向一个关闭的端口——所有本该「不需要联网」的用例（initialize / tools/list /
 * 缺身份 / 未知方法）都必须不依赖上游。
 */
class McpEndpointTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private McpEndpoint endpoint;

    @BeforeEach
    void setUp() {
        McpServerProperties properties = new McpServerProperties(
                "http://127.0.0.1:1", Duration.ofMillis(500), 4096, false, null);
        ToolCatalogLoader loader = new ToolCatalogLoader(properties, MAPPER);
        ToolInvoker invoker = new ToolInvoker(new UpstreamClient(properties), properties);
        endpoint = new McpEndpoint(loader, invoker, MAPPER, properties);
    }

    @Test
    @DisplayName("initialize：返回协议版本、tools 能力与服务名")
    void initialize() {
        JsonNode response = call("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}", null);

        assertEquals("2025-03-26", response.path("result").path("protocolVersion").asText());
        assertTrue(response.path("result").path("capabilities").has("tools"));
        assertEquals("drone-mcp-server", response.path("result").path("serverInfo").path("name").asText());
    }

    @Test
    @DisplayName("tools/list：返回 70 个工具，带只读标注")
    void listsTools() {
        JsonNode response = call("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}", null);

        JsonNode tools = response.path("result").path("tools");
        assertEquals(70, tools.size());
        boolean foundReadOnly = false;
        boolean foundWrite = false;
        for (JsonNode tool : tools) {
            String name = tool.path("name").asText();
            assertFalse(name.startsWith("drone__"), "前缀由 agent 侧添加，服务端返回原名");
            assertTrue(tool.has("inputSchema"));
            if ("task_list".equals(name)) {
                assertTrue(tool.path("annotations").path("readOnlyHint").asBoolean());
                foundReadOnly = true;
            }
            if ("task_dispatch".equals(name)) {
                assertFalse(tool.path("annotations").path("readOnlyHint").asBoolean());
                foundWrite = true;
            }
        }
        assertTrue(foundReadOnly && foundWrite);
    }

    @Test
    @DisplayName("通知：返回 202，且没有 JSON-RPC 响应体")
    void notificationReturns202() {
        ResponseEntity<String> response = endpoint.handle(
                "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}", null, null);

        assertEquals(202, response.getStatusCode().value());
        assertTrue(response.getBody() == null || response.getBody().isEmpty());
    }

    @Test
    @DisplayName("未知方法：-32601")
    void unknownMethod() {
        JsonNode response = call("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"resources/list\"}", null);

        assertEquals(-32601, response.path("error").path("code").asInt());
    }

    @Test
    @DisplayName("坏 JSON：-32700")
    void malformedJson() {
        JsonNode response = call("{not json", null);

        assertEquals(-32700, response.path("error").path("code").asInt());
    }

    @Test
    @DisplayName("tools/call 缺身份：isError，且不访问上游")
    void callWithoutAuthorization() {
        JsonNode response = call("{"
                + "\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"task_list\",\"arguments\":{}}}", null);

        assertTrue(response.path("result").path("isError").asBoolean());
        assertTrue(response.path("result").path("content").get(0).path("text").asText().contains("缺少用户身份"));
    }

    @Test
    @DisplayName("未知工具：isError，给模型可读文本")
    void callUnknownTool() {
        JsonNode response = call("{"
                + "\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"no_such_tool\",\"arguments\":{}}}", "Bearer abc");

        assertTrue(response.path("result").path("isError").asBoolean());
        assertTrue(response.path("result").path("content").get(0).path("text").asText().contains("未知工具"));
    }

    private JsonNode call(String body, String authorization) {
        ResponseEntity<String> response = endpoint.handle(body, authorization, null);
        try {
            return MAPPER.readTree(response.getBody());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
