package cn.tedu.drone.mcp.invoke;

import cn.tedu.drone.mcp.catalog.ToolSpec;
import cn.tedu.drone.mcp.config.McpServerProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工具执行器测试：把 MCP 调用翻译成上游 HTTP 请求。
 *
 * <p>上游用 JDK 自带的 {@code HttpServer} 做桩，真实检查「路径、query、body、Authorization」
 * 四件事发对了没有——这些只在网络上才看得见，用假对象测不出来。
 */
class ToolInvokerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpServer server;
    private ToolInvoker invoker;
    private final AtomicInteger requests = new AtomicInteger();

    private volatile int upstreamStatus = 200;
    private volatile String upstreamBody = "{\"state\":200,\"message\":\"ok\",\"data\":null}";

    private volatile String seenMethod;
    private volatile String seenPath;
    private volatile String seenQuery;
    private volatile String seenBody;
    private volatile String seenAuth;
    private volatile String seenTraceId;

    /** 模拟慢上游（毫秒） */
    private volatile long delayMs = 0;

    @BeforeEach
    void startUpstream() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", handler());
        server.start();

        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        McpServerProperties properties = new McpServerProperties(
                base, Duration.ofSeconds(2), 4096, false, null);
        invoker = new ToolInvoker(new UpstreamClient(properties), properties);
    }

    @AfterEach
    void stopUpstream() {
        server.stop(0);
    }

    @Test
    @DisplayName("请求体接口：字段进 body，Authorization 原样转发")
    void sendsBodyAndForwardsAuthorization() {
        upstreamBody = "{\"state\":200,\"message\":\"成功\",\"data\":{\"id\":9}}";

        ToolSpec spec = spec("POST", "/task/update", true, List.of(), Set.of(), Set.of("id", "name"));
        ToolCallResult result = invoker.call(spec, json("{\"id\":9,\"name\":\"巡检\"}"), "Bearer abc");

        assertFalse(result.isError());
        assertEquals("POST", seenMethod);
        assertEquals("/task/update", seenPath);
        assertEquals("Bearer abc", seenAuth);
        assertEquals(9, json(seenBody).path("id").asInt());
        assertEquals("巡检", json(seenBody).path("name").asText());
        assertTrue(seenQuery == null || seenQuery.isEmpty(), "不应有 query");
    }

    @Test
    @DisplayName("POST + 纯 query（如 /route/delete?id=）：不发 JSON body")
    void sendsQueryWithoutBody() {
        ToolSpec spec = spec("POST", "/route/delete", false, List.of(), Set.of("id"), Set.of());

        invoker.call(spec, json("{\"id\":5}"), "Bearer abc");

        assertEquals("POST", seenMethod);
        assertEquals("id=5", seenQuery);
        assertTrue(seenBody == null || seenBody.isEmpty(), "不应发送请求体");
    }

    @Test
    @DisplayName("路径参数：替换 {id}，并拦住路径穿越")
    void rendersPathAndRejectsInjection() {
        ToolSpec spec = spec("GET", "/zone/{id}", false, List.of("id"), Set.of(), Set.of());

        invoker.call(spec, json("{\"id\":12}"), "Bearer abc");
        assertEquals("/zone/12", seenPath);

        int before = requests.get();
        ToolCallResult bad = invoker.call(spec, json("{\"id\":\"../admin\"}"), "Bearer abc");
        assertTrue(bad.isError());
        assertEquals(before, requests.get(), "非法路径参数不应发请求");
    }

    @Test
    @DisplayName("401 / 403：文案必须能区分「登录失效」与「无权限」")
    void distinguishesAuthErrors() {
        ToolSpec spec = spec("GET", "/task/list", false, List.of(), Set.of(), Set.of());

        upstreamStatus = 401;
        assertTrue(invoker.call(spec, json("{}"), "Bearer abc").text().contains("登录已失效"));

        upstreamStatus = 403;
        assertTrue(invoker.call(spec, json("{}"), "Bearer abc").text().contains("没有执行该操作的权限"));
    }

    @Test
    @DisplayName("业务失败：HTTP 200 + state=500 → isError，并回传 message")
    void mapsBusinessFailure() {
        upstreamBody = "{\"state\":500,\"message\":\"任务状态不允许派发\",\"data\":null}";

        ToolCallResult result = invoker.call(
                spec("POST", "/task/dispatch", true, List.of(), Set.of(), Set.of("taskId")),
                json("{\"taskId\":1}"), "Bearer abc");

        assertTrue(result.isError());
        assertEquals("任务状态不允许派发", result.text());
    }

    @Test
    @DisplayName("成功但 data 为空：回退到 message（删除类接口就是这样）")
    void fallsBackToMessageWhenDataIsNull() {
        upstreamBody = "{\"state\":200,\"message\":\"删除成功\",\"data\":null}";

        ToolCallResult result = invoker.call(spec("POST", "/zone/delete", false, List.of(), Set.of("id"), Set.of()),
                json("{\"id\":1}"), "Bearer abc");

        assertFalse(result.isError());
        assertEquals("删除成功", result.text());
    }

    @Test
    @DisplayName("traceId：原样转发到上游请求头")
    void forwardsTraceId() {
        upstreamBody = "{\"state\":200,\"message\":\"ok\",\"data\":null}";

        invoker.call(spec("GET", "/task/list", false, List.of(), Set.of(), Set.of()),
                json("{}"), "Bearer abc", "trace-1234");

        assertEquals("trace-1234", seenTraceId);
    }

    @Test
    @DisplayName("没有 Authorization：直接失败，不向上游发任何请求")
    void refusesWithoutAuthorization() {
        int before = requests.get();

        ToolCallResult result = invoker.call(spec("GET", "/task/list", false, List.of(), Set.of(), Set.of()),
                json("{}"), null);

        assertTrue(result.isError());
        assertTrue(result.text().contains("缺少用户身份"));
        assertEquals(before, requests.get());
    }

    @Test
    @DisplayName("幂等：同 trace 的写操作只执行一次，第二次复用结果")
    void deduplicatesWriteWithinTrace() {
        ToolSpec spec = spec("POST", "/task/dispatch", true, List.of(), Set.of(), Set.of("taskId"));
        upstreamBody = "{\"state\":200,\"message\":\"派发成功\",\"data\":null}";

        ToolCallResult first = invoker.call(spec, json("{\"taskId\":1}"), "Bearer abc", "trace-dup");
        ToolCallResult second = invoker.call(spec, json("{\"taskId\":1}"), "Bearer abc", "trace-dup");

        assertEquals(first.text(), second.text());
        assertEquals(1, requests.get(), "同一 trace 的写操作只应打一次上游");
    }

    @Test
    @DisplayName("幂等：参数顺序不同仍视为同一操作")
    void canonicalizesArgumentOrder() {
        ToolSpec spec = spec("POST", "/task/update", true, List.of(), Set.of(), Set.of("id", "name"));

        invoker.call(spec, json("{\"id\":9,\"name\":\"巡检\"}"), "Bearer abc", "trace-order");
        invoker.call(spec, json("{\"name\":\"巡检\",\"id\":9}"), "Bearer abc", "trace-order");

        assertEquals(1, requests.get(), "字段顺序不应影响幂等键");
    }

    @Test
    @DisplayName("幂等：不同 trace 但同一 operationId 仍只执行一次（续跑场景）")
    void sameOperationIdDeduplicatesAcrossTraces() {
        ToolSpec spec = spec("POST", "/task/dispatch", true, List.of(), Set.of(), Set.of("taskId"));

        invoker.call(spec, json("{\"taskId\":1}"), "Bearer abc", "trace-1", "op-1");
        invoker.call(spec, json("{\"taskId\":1}"), "Bearer abc", "trace-2", "op-1");

        assertEquals(1, requests.get(), "续跑复用 operationId 时不应重复执行");
    }

    @Test
    @DisplayName("幂等：不同 trace 的相同参数各执行一次")
    void differentTraceExecutesAgain() {
        ToolSpec spec = spec("POST", "/task/dispatch", true, List.of(), Set.of(), Set.of("taskId"));

        invoker.call(spec, json("{\"taskId\":1}"), "Bearer abc", "trace-a");
        invoker.call(spec, json("{\"taskId\":1}"), "Bearer abc", "trace-b");

        assertEquals(2, requests.get(), "不同请求不得互相吞掉");
    }

    @Test
    @DisplayName("幂等：读工具不缓存，两次都打上游")
    void readToolsAreNotCached() {
        ToolSpec spec = spec("GET", "/task/list", false, List.of(), Set.of(), Set.of());

        invoker.call(spec, json("{}"), "Bearer abc", "trace-read");
        invoker.call(spec, json("{}"), "Bearer abc", "trace-read");

        assertEquals(2, requests.get());
    }

    @Test
    @DisplayName("幂等：写操作超时后缓存「结果未知」，重试不再打上游")
    void timeoutCachesUnknownForWrites() {
        ToolSpec spec = spec("POST", "/task/dispatch", true, List.of(), Set.of(), Set.of("taskId"));
        delayMs = 3000;

        ToolCallResult first = invoker.call(spec, json("{\"taskId\":1}"), "Bearer abc", "trace-timeout");
        assertTrue(first.isError());
        assertTrue(first.text().contains("结果未知"), first.text());

        ToolCallResult second = invoker.call(spec, json("{\"taskId\":1}"), "Bearer abc", "trace-timeout");
        assertTrue(second.isError());
        assertEquals(1, requests.get(), "超时后的重试不得再次执行写操作");
    }

    // ---------- 桩与工具方法 ----------

    private HttpHandler handler() {
        return exchange -> {
            requests.incrementAndGet();
            seenMethod = exchange.getRequestMethod();
            seenPath = exchange.getRequestURI().getPath();
            seenQuery = exchange.getRequestURI().getQuery();
            seenAuth = exchange.getRequestHeaders().getFirst("Authorization");
            seenTraceId = exchange.getRequestHeaders().getFirst("X-Trace-Id");
            seenBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (delayMs > 0) {
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }

            byte[] bytes = upstreamBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(upstreamStatus, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        };
    }

    private static ToolSpec spec(String method, String path, boolean hasBody,
                                 List<String> pathParams, Set<String> queryParams, Set<String> bodyParams) {
        return new ToolSpec("demo_tool", "demo", method, path,
                MAPPER.createObjectNode(), "GET".equals(method), hasBody,
                pathParams, queryParams, bodyParams);
    }

    private static JsonNode json(String text) {
        try {
            return MAPPER.readTree(text);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
