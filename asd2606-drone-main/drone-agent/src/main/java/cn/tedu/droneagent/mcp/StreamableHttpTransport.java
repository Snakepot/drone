package cn.tedu.droneagent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * MCP 的 **Streamable HTTP** 传输实现。
 *
 * <h2>一次请求在网络上长什么样</h2>
 * <pre>
 * POST https://mcp.amap.com/mcp?key=***
 * Content-Type: application/json; charset=utf-8
 * Accept: application/json, text/event-stream
 * Body: {"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}
 * </pre>
 *
 * <h2>为什么 Accept 必须写两种</h2>
 * Streamable HTTP 允许服务器用两种方式回：
 * <ul>
 *   <li><b>普通 JSON</b>：一次响应就是一条 JSON-RPC 消息（高德就是这样，实测
 *       {@code Content-Type: application/json}）；</li>
 *   <li><b>SSE（text/event-stream）</b>：持续推事件，响应可能夹在中间，
 *       服务端也不一定在发完就关流。</li>
 * </ul>
 * 只写 {@code application/json} 时，一些服务器直接 406。
 *
 * <h2>为什么用 {@code ofInputStream} 而不是 {@code ofString}</h2>
 * SSE 流可能**发完响应仍不关闭**。{@code ofString} 会一直等到流结束，
 * 表现就是「请求明明成功了，却卡到超时」。改用输入流后，只要读到 id 匹配的那条事件就立刻返回。
 *
 * <h2>已知限制（如实记录）</h2>
 * 如果服务器建立了 SSE 连接后**一个字节都不发**，{@code readLine()} 仍会阻塞到超时。
 * 高德不使用 SSE，本期接受该限制；以后接的服务器若出现这种情况，再引入独立的读超时。
 */
@Slf4j
public class StreamableHttpTransport implements McpTransport {

    private final URI endpoint;
    private final HttpClient httpClient;
    private final Duration requestTimeout;
    private final Map<String, String> extraHeaders;
    private final ObjectMapper objectMapper;

    /** 是否打印原始报文；排障开关，生产建议关闭（报文含业务数据） */
    private final boolean logRawPayload;

    /** 服务器下发的会话 id；没有就是 null（高德即如此） */
    private volatile String sessionId;

    public StreamableHttpTransport(String url, Duration connectTimeout, Duration requestTimeout,
                                   Map<String, String> extraHeaders, ObjectMapper objectMapper,
                                   boolean logRawPayload) {
        this.endpoint = URI.create(url);
        this.requestTimeout = requestTimeout == null ? Duration.ofSeconds(8) : requestTimeout;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(connectTimeout == null ? Duration.ofSeconds(5) : connectTimeout)
                .build();
        this.extraHeaders = extraHeaders == null ? Map.of() : Map.copyOf(extraHeaders);
        this.objectMapper = objectMapper;
        this.logRawPayload = logRawPayload;
    }

    @Override
    public JsonNode request(JsonNode rpc) {
        return request(rpc, Map.of());
    }

    @Override
    public JsonNode request(JsonNode rpc, Map<String, String> headers) {
        HttpResponse<InputStream> response = send(rpc, headers);
        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            String body = preview(response.body());
            throw new McpException("MCP 请求失败：HTTP " + status + "，响应片段：" + body);
        }
        String contentType = response.headers().firstValue("content-type").orElse("");
        JsonNode message = contentType.toLowerCase().contains("text/event-stream")
                ? readEventStream(response.body(), rpc.path("id"))
                : readSingle(response.body(), rpc.path("id"));

        JsonNode error = message.path("error");
        if (!error.isMissingNode() && !error.isNull()) {
            throw new McpException("MCP 返回错误：code=" + error.path("code").asText()
                    + "，message=" + error.path("message").asText());
        }
        return message;
    }

    @Override
    public void notify(JsonNode rpc) {
        HttpResponse<InputStream> response = send(rpc, Map.of());
        closeQuietly(response.body());
        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            throw new McpException("MCP 通知失败：HTTP " + status);
        }
        // 202/204 是正常结果：通知**没有** JSON-RPC 响应体，这里刻意不解析
        log.debug("【MCP】通知已送达：method={}，HTTP {}", rpc.path("method").asText(), status);
    }

    @Override
    public String sessionId() {
        return sessionId;
    }

    /**
     * 真正发一次 POST。
     *
     * <p>会话头处理：上一次响应里带了 {@code Mcp-Session-Id}，这一次就回带；
     * 没有就不带——**不要自己造一个**，服务器会认为会话非法。
     */
    private HttpResponse<InputStream> send(JsonNode rpc, Map<String, String> perCallHeaders) {
        if (logRawPayload) {
            log.info("【MCP-RAW】请求 → {}：{}", McpSecrets.maskUrl(endpoint.toString()), rpc);
        }

        HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint)
                .timeout(requestTimeout)
                .header("Content-Type", "application/json; charset=utf-8")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(rpc.toString(), StandardCharsets.UTF_8));
        extraHeaders.forEach(builder::header);
        // 每调用的头放在最后：同名时以调用方为准（目前只用于 Authorization）
        if (perCallHeaders != null) {
            perCallHeaders.forEach(builder::header);
        }
        String currentSession = sessionId;
        if (currentSession != null && !currentSession.isBlank()) {
            builder.header("Mcp-Session-Id", currentSession);
        }

        try {
            HttpResponse<InputStream> response =
                    httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
            response.headers().firstValue("Mcp-Session-Id").ifPresent(value -> {
                if (!value.isBlank()) {
                    this.sessionId = value;
                }
            });
            return response;
        } catch (HttpTimeoutException e) {
            throw new McpException("MCP 请求超时（" + requestTimeout.toMillis() + "ms）："
                    + McpSecrets.maskUrl(endpoint.toString()), e);
        } catch (IOException e) {
            throw new McpException("MCP 网络失败：" + McpSecrets.maskUrl(endpoint.toString())
                    + "，原因：" + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new McpException("MCP 请求被中断", e);
        }
    }

    /** 普通 JSON 响应：读完整 body 后解析 */
    private JsonNode readSingle(InputStream body, JsonNode expectedId) {
        try (InputStream in = body) {
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
            if (logRawPayload) {
                log.info("【MCP-RAW】响应 ← {}", text);
            }
            if (text.isEmpty()) {
                throw new McpException("MCP 响应体为空（请求 id=" + expectedId.asText() + "）");
            }
            return objectMapper.readTree(text);
        } catch (IOException e) {
            throw new McpException("MCP 响应无法解析：" + e.getMessage(), e);
        }
    }

    /**
     * SSE 响应：按「空行分隔事件」逐条读取，直到拿到 id 匹配的那条。
     *
     * <p>{@code data:} 可能有多行，按规范要**拼接**；{@code :} 开头是注释/心跳，
     * {@code event:} / {@code id:} / {@code retry:} 与 JSON-RPC 响应无关，一律忽略。
     */
    private JsonNode readEventStream(InputStream body, JsonNode expectedId) {
        long deadline = System.nanoTime() + requestTimeout.toNanos();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(body, StandardCharsets.UTF_8))) {
            StringBuilder data = new StringBuilder();
            String line;
            while (System.nanoTime() < deadline && (line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    if (logRawPayload && data.length() > 0) {
                        log.info("【MCP-RAW】SSE 事件 ← {}", data);
                    }
                    JsonNode matched = takeEvent(data, expectedId);
                    data.setLength(0);
                    if (matched != null) {
                        return matched;
                    }
                    continue;
                }
                if (line.startsWith(":")) {
                    continue;
                }
                if (line.startsWith("data:")) {
                    String chunk = line.substring("data:".length());
                    data.append(chunk.startsWith(" ") ? chunk.substring(1) : chunk);
                }
            }
            JsonNode matched = takeEvent(data, expectedId);
            if (matched != null) {
                return matched;
            }
            throw new McpException("MCP 的 SSE 流结束，但没有收到 id=" + expectedId.asText() + " 的响应");
        } catch (IOException e) {
            throw new McpException("MCP 的 SSE 响应读取失败：" + e.getMessage(), e);
        }
    }

    /** 一条 SSE 事件的 data 可能不是我们要的响应（如进度通知），解析后按 id 过滤 */
    private JsonNode takeEvent(StringBuilder data, JsonNode expectedId) {
        String text = data.toString().trim();
        if (text.isEmpty() || "[DONE]".equals(text)) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(text);
            if (node.isArray()) {
                for (JsonNode child : node) {
                    if (sameId(child, expectedId)) {
                        return child;
                    }
                }
                return null;
            }
            return sameId(node, expectedId) ? node : null;
        } catch (IOException e) {
            // 单条事件坏了不该让整次调用失败，交给后面的超时/结束判断
            log.debug("【MCP】忽略无法解析的 SSE 事件（{} 字符）", text.length());
            return null;
        }
    }

    private static boolean sameId(JsonNode node, JsonNode expectedId) {
        JsonNode id = node.path("id");
        if (id.isMissingNode() || expectedId.isMissingNode()) {
            return false;
        }
        // id 可能是数字也可能是字符串，统一按文本比
        return id.asText().equals(expectedId.asText());
    }

    /** 非 2xx 时只取前 2KB 作为线索，避免把大段错误页打进日志 */
    private String preview(InputStream body) {
        try (InputStream in = body) {
            String text = new String(in.readNBytes(2048), StandardCharsets.UTF_8);
            return text.isBlank() ? "(空)" : text;
        } catch (IOException e) {
            return "(读取响应失败)";
        }
    }

    private static void closeQuietly(InputStream in) {
        try {
            in.close();
        } catch (IOException ignored) {
            // 关闭失败不影响结果
        }
    }
}
