package cn.tedu.droneagent.mcp;

import cn.tedu.droneagent.config.McpProperties;
import cn.tedu.droneagent.tool.ToolDescriptor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link McpToolset} 的测试：多服务器聚合、前缀、白名单、降级。
 *
 * <p>成功路径用 JDK 的 {@code HttpServer} 模拟一个最小的 MCP 服务器
 * （认识 initialize / initialized / tools/list），失败路径直接指一个关闭的端口。
 */
class McpToolsetTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final List<HttpServer> servers = new ArrayList<>();

    @AfterEach
    void stopServers() {
        servers.forEach(server -> server.stop(0));
        servers.clear();
    }

    @Test
    @DisplayName("总开关关闭：不连网络，返回空集合")
    void disabledReturnsEmpty() {
        McpProperties properties = new McpProperties(false, false, Duration.ofSeconds(1), 1, Duration.ZERO, false,
                List.of(server("amap", "http://127.0.0.1:1/mcp?key=x", List.of())));

        McpToolset toolset = McpToolset.discover(properties, MAPPER);

        assertTrue(toolset.tools().isEmpty());
        assertTrue(toolset.descriptors().isEmpty());
    }

    @Test
    @DisplayName("没配密钥：跳过该服务器（不算错误）")
    void blankKeyIsSkipped() {
        McpProperties properties = new McpProperties(true, false, Duration.ofSeconds(1), 1, Duration.ZERO, false,
                List.of(server("amap", "https://mcp.amap.com/mcp?key=", List.of())));

        McpToolset toolset = McpToolset.discover(properties, MAPPER);

        assertTrue(toolset.tools().isEmpty());
    }

    @Test
    @DisplayName("成功发现：工具名加别名前缀，行为与元数据一一对应")
    void discoversAndPrefixes() throws Exception {
        String url = startServer(3);
        McpProperties properties = props(false, server("amap", url, List.of()));

        McpToolset toolset = McpToolset.discover(properties, MAPPER);

        assertEquals(3, toolset.tools().size());
        assertEquals(3, toolset.descriptors().size());
        // REQ-AGENT-0022：注册顺序固定按名字排序，保证前缀稳定
        List<String> names = toolset.descriptors().stream().map(ToolDescriptor::name).toList();
        assertEquals(List.of("amap__hidden_tool", "amap__maps_geo", "amap__maps_weather"), names);
        assertEquals(names, toolset.tools().stream().map(t -> t.name()).toList(),
                "行为顺序必须与元数据一致");
        assertTrue(toolset.descriptors().get(0).parallelSafe());
        assertTrue(toolset.descriptors().get(0).parametersJson().contains("\"type\":\"object\""));
    }

    @Test
    @DisplayName("白名单过滤：只保留 include 里列出的工具")
    void filtersByInclude() throws Exception {
        String url = startServer(3);
        McpProperties properties = props(false,
                server("amap", url, List.of("maps_weather", "maps_geo")));

        McpToolset toolset = McpToolset.discover(properties, MAPPER);

        assertEquals(List.of("amap__maps_geo", "amap__maps_weather"),
                toolset.tools().stream().map(t -> t.name()).toList());
    }

    @Test
    @DisplayName("别名重复：配置错误，直接启动失败（与网络无关）")
    void duplicateAliasFailsFast() throws Exception {
        String url = startServer(1);
        McpProperties properties = props(false,
                server("amap", url, List.of()), server("amap", url, List.of()));

        assertThrows(IllegalStateException.class, () -> McpToolset.discover(properties, MAPPER));
    }

    @Test
    @DisplayName("服务器不可达：默认降级，不抛异常")
    void unreachableServerDegrades() {
        McpProperties properties = props(false,
                server("amap", "http://127.0.0.1:1/mcp?key=x", List.of()));

        McpToolset toolset = McpToolset.discover(properties, MAPPER);

        assertTrue(toolset.tools().isEmpty());
    }

    @Test
    @DisplayName("服务器不可达且 fail-fast=true：抛出，让启动失败")
    void unreachableServerFailsWhenConfigured() {
        McpProperties properties = props(true,
                server("amap", "http://127.0.0.1:1/mcp?key=x", List.of()));

        assertThrows(IllegalStateException.class, () -> McpToolset.discover(properties, MAPPER));
    }

    // ---------- 桩服务器 ----------

    private static McpProperties props(boolean failFast, McpProperties.Server... servers) {
        return new McpProperties(true, failFast, Duration.ofSeconds(3),
                1, Duration.ZERO, false, List.of(servers));
    }

    private static McpProperties.Server server(String alias, String url, List<String> include) {
        return new McpProperties.Server(alias, url, true,
                Duration.ofMillis(800), Duration.ofMillis(800),
                null, include, List.of(), true, false);
    }

    /** 一个最小可用的 MCP 服务器：initialize / initialized / tools/list */
    private String startServer(int toolCount) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", toolsHandler(toolCount));
        server.start();
        servers.add(server);
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp?key=secret";
    }

    private static HttpHandler toolsHandler(int toolCount) {
        return exchange -> {
            JsonNode rpc = MAPPER.readTree(readBody(exchange));
            String method = rpc.path("method").asText();
            switch (method) {
                case "initialize" -> send(exchange, 200, "application/json",
                        "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"protocolVersion\":\"2025-03-26\"}}");
                case "notifications/initialized" -> send(exchange, 202, "text/plain", "");
                case "tools/list" -> send(exchange, 200, "application/json", toolsPage(toolCount));
                default -> send(exchange, 404, "text/plain", "unknown method");
            }
        };
    }

    private static String toolsPage(int count) {
        String[] names = {"maps_weather", "maps_geo", "hidden_tool"};
        StringBuilder tools = new StringBuilder();
        for (int i = 0; i < Math.min(count, names.length); i++) {
            if (i > 0) {
                tools.append(',');
            }
            tools.append("{\"name\":\"").append(names[i])
                    .append("\",\"description\":\"工具 ").append(i).append("\",")
                    .append("\"inputSchema\":{\"type\":\"object\",\"properties\":{}},\"annotations\":{\"readOnlyHint\":true}}");
        }
        return "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"tools\":[" + tools + "]}}";
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static void send(HttpExchange exchange, int status, String contentType, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", contentType);
        if (bytes.length == 0) {
            exchange.sendResponseHeaders(status, -1);
        } else {
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        }
        exchange.close();
    }
}
