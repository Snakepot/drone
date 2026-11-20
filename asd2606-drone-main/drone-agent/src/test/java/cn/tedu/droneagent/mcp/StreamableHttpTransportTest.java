package cn.tedu.droneagent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link StreamableHttpTransport} 的单元测试。
 *
 * <h2>为什么用 JDK 自带的 {@code HttpServer} 当桩</h2>
 * 宪法 §5 要求单测不依赖真实外部环境。{@code HttpServer} 是 JDK 自带的，
 * **不需要新依赖**，又能真实验证「HTTP 头、状态码、SSE 分帧」这些只在网络上才出现的问题。
 * 用假对象直接返回 JSON 是测不出「SSE 流不关闭」这类坑的。
 *
 * <h2>覆盖的边界</h2>
 * <ol>
 *   <li>普通 JSON 响应；</li>
 *   <li>SSE 响应（中间夹着通知事件）；</li>
 *   <li>通知 → 202 空体，不解析；</li>
 *   <li>非 2xx；</li>
 *   <li>超时；</li>
 *   <li>会话头回带；</li>
 *   <li>URL 里的密钥脱敏。</li>
 * </ol>
 */
class StreamableHttpTransportTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final List<HttpServer> servers = new ArrayList<>();

    @AfterEach
    void stopServers() {
        servers.forEach(server -> server.stop(0));
        servers.clear();
    }

    @Test
    @DisplayName("普通 JSON 响应：直接解出 result")
    void readsJsonResponse() throws Exception {
        String url = startServer(exchange -> send(exchange, 200, "application/json",
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"ok\":true}}"));

        StreamableHttpTransport transport = transport(url, Duration.ofSeconds(2));

        JsonNode response = transport.request(request(1, "tools/list"));

        assertTrue(response.path("result").path("ok").asBoolean());
    }

    @Test
    @DisplayName("SSE 响应：跳过通知事件，只取 id 匹配的那条")
    void readsSseResponseAndSkipsNotifications() throws Exception {
        String body = """
                : ping

                data: {"jsonrpc":"2.0","method":"notifications/progress","params":{"p":1}}

                data: {"jsonrpc":"2.0","id":7,"result":{"ok":true,"from":"sse"}}

                """;
        String url = startServer(exchange -> send(exchange, 200, "text/event-stream", body));

        StreamableHttpTransport transport = transport(url, Duration.ofSeconds(2));

        JsonNode response = transport.request(request(7, "tools/list"));

        assertEquals("sse", response.path("result").path("from").asText());
    }

    @Test
    @DisplayName("通知：202 空体也算成功，不能按 JSON 解析")
    void notificationAcceptsEmptyBody() throws Exception {
        String url = startServer(exchange -> send(exchange, 202, "text/plain", ""));

        StreamableHttpTransport transport = transport(url, Duration.ofSeconds(2));

        // 不抛异常即通过
        transport.notify(notification("notifications/initialized"));
    }

    @Test
    @DisplayName("非 2xx：抛 McpException，并带状态码")
    void throwsOnHttpError() throws Exception {
        String url = startServer(exchange -> send(exchange, 500, "text/plain", "boom"));

        StreamableHttpTransport transport = transport(url, Duration.ofSeconds(2));

        McpException error = assertThrows(McpException.class,
                () -> transport.request(request(1, "tools/list")));
        assertTrue(error.getMessage().contains("HTTP 500"), error.getMessage());
    }

    @Test
    @DisplayName("超时：抛 McpException，消息里带「超时」")
    void throwsOnTimeout() throws Exception {
        String url = startServer(exchange -> {
            try {
                Thread.sleep(800);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            send(exchange, 200, "application/json", "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}");
        });

        StreamableHttpTransport transport = transport(url, Duration.ofMillis(200));

        McpException error = assertThrows(McpException.class,
                () -> transport.request(request(1, "tools/list")));
        assertTrue(error.getMessage().contains("超时"), error.getMessage());
    }

    @Test
    @DisplayName("会话头：服务器下发后，后续请求必须回带")
    void resendsSessionId() throws Exception {
        AtomicBoolean sawSession = new AtomicBoolean(false);
        String url = startServer(exchange -> {
            String session = exchange.getRequestHeaders().getFirst("Mcp-Session-Id");
            if (session != null) {
                sawSession.set(true);
            }
            exchange.getResponseHeaders().add("Mcp-Session-Id", "s-1");
            send(exchange, 200, "application/json",
                    "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}");
        });

        StreamableHttpTransport transport = transport(url, Duration.ofSeconds(2));
        transport.request(request(1, "initialize"));

        assertEquals("s-1", transport.sessionId());
        transport.request(request(2, "tools/list"));

        assertTrue(sawSession.get(), "第二次请求必须带上 Mcp-Session-Id");
    }

    @Test
    @DisplayName("每调用请求头：只作用于这一次请求（用户身份转发依赖它）")
    void sendsPerCallHeaders() throws Exception {
        AtomicReference<String> seenAuth = new AtomicReference<>();
        String url = startServer(exchange -> {
            seenAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            send(exchange, 200, "application/json",
                    "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}");
        });

        StreamableHttpTransport transport = transport(url, Duration.ofSeconds(2));
        transport.request(request(1, "tools/call"), Map.of("Authorization", "Bearer user-1"));
        assertEquals("Bearer user-1", seenAuth.get());

        // 第二次不带：确认它不是被存成了字段
        transport.request(request(2, "tools/call"), Map.of());
        assertNull(seenAuth.get());
    }

    @Test
    @DisplayName("脱敏：URL 里的 key 必须被遮掉")
    void masksSecretInUrl() {
        assertEquals("https://mcp.amap.com/mcp?key=***",
                McpSecrets.maskUrl("https://mcp.amap.com/mcp?key=fc5116254a5ab8e2"));
        assertEquals("https://x/mcp?key=***&page=2",
                McpSecrets.maskUrl("https://x/mcp?key=abc&page=2"));
        assertEquals("no-secret", McpSecrets.maskUrl("no-secret"));
    }

    // ---------- 桩服务器工具方法 ----------

    private StreamableHttpTransport transport(String url, Duration requestTimeout) {
        return new StreamableHttpTransport(url, Duration.ofSeconds(2), requestTimeout, null, MAPPER, false);
    }

    private String startServer(HttpHandler handler) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", handler);
        server.start();
        servers.add(server);
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp?key=secret";
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

    private static JsonNode request(int id, String method) {
        ObjectNode rpc = MAPPER.createObjectNode();
        rpc.put("jsonrpc", "2.0");
        rpc.put("id", id);
        rpc.put("method", method);
        rpc.set("params", MAPPER.createObjectNode());
        return rpc;
    }

    private static JsonNode notification(String method) {
        ObjectNode rpc = MAPPER.createObjectNode();
        rpc.put("jsonrpc", "2.0");
        rpc.put("method", method);
        return rpc;
    }
}
