package cn.tedu.drone.mcp;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import cn.tedu.drone.mcp.catalog.ToolCatalogLoader;
import cn.tedu.drone.mcp.catalog.ToolSpec;
import cn.tedu.drone.mcp.config.McpServerProperties;
import cn.tedu.drone.mcp.invoke.ToolInvoker;
import cn.tedu.drone.mcp.invoke.UpstreamClient;
import cn.tedu.drone.mcp.web.McpEndpoint;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MCP 服务侧原始报文日志的测试：端点收发与上游收发都要有原文，且 Tail 不带 Token。
 */
class McpRawLoggingTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpServer upstream;

    @AfterEach
    void stopUpstream() {
        if (upstream != null) {
            upstream.stop(0);
        }
    }

    @Test
    @DisplayName("端点：打印 MCP 原始请求体与响应体，不含 Authorization")
    void endpointLogsRawBodies() {
        McpServerProperties properties = new McpServerProperties(
                "http://127.0.0.1:1", Duration.ofMillis(300), 4096, true, null);
        McpEndpoint endpoint = new McpEndpoint(
                new ToolCatalogLoader(properties, MAPPER),
                new ToolInvoker(new UpstreamClient(properties), properties),
                MAPPER, properties);

        Logger logger = (Logger) LoggerFactory.getLogger(McpEndpoint.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            endpoint.handle("{"
                    + "\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\","
                    + "\"params\":{\"name\":\"task_list\",\"arguments\":{\"keyword\":\"raw-endpoint-arg\"}}}",
                    "Bearer secret-should-not-appear", null);
        } finally {
            logger.detachAppender(appender);
        }

        String logs = collect(appender);
        assertTrue(logs.contains("raw-endpoint-arg"), "应打印 MCP 请求体原文");
        assertTrue(logs.contains("MCP 响应体"), "应打印 MCP 响应体");
        assertFalse(logs.contains("secret-should-not-appear"), "绝不能打印 Token");
        assertFalse(logs.contains("Bearer"), "绝不能打印 Authorization");
    }

    @Test
    @DisplayName("上游调用：打印转发请求体与上游响应原文")
    void invokerLogsUpstreamBodies() throws Exception {
        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/", exchange -> {
            byte[] bytes = "{\"state\":200,\"message\":\"ok\",\"data\":{\"echo\":\"raw-upstream-response\"}}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        upstream.start();

        String base = "http://127.0.0.1:" + upstream.getAddress().getPort();
        McpServerProperties properties = new McpServerProperties(
                base, Duration.ofSeconds(2), 4096, true, null);
        ToolInvoker invoker = new ToolInvoker(new UpstreamClient(properties), properties);

        ToolSpec spec = new ToolSpec("demo", "demo", "POST", "/task/update",
                MAPPER.createObjectNode(), false, true,
                List.of(), Set.of(), Set.of("note"));

        Logger logger = (Logger) LoggerFactory.getLogger(ToolInvoker.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            invoker.call(spec, MAPPER.readTree("{\"note\":\"raw-upstream-request\"}"), "Bearer jwt-value");
        } finally {
            logger.detachAppender(appender);
        }

        String logs = collect(appender);
        assertTrue(logs.contains("raw-upstream-request"), "应打印转发给上游的请求体");
        assertTrue(logs.contains("raw-upstream-response"), "应打印上游响应原文");
        assertFalse(logs.contains("jwt-value"), "绝不能打印 Token");
        assertFalse(logs.contains("Bearer"), "绝不能打印 Authorization");
    }

    private static String collect(ListAppender<ILoggingEvent> appender) {
        return appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .collect(Collectors.joining("\n"));
    }
}
