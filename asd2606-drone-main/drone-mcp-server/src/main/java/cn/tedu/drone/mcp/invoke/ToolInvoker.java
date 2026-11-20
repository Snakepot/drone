package cn.tedu.drone.mcp.invoke;

import cn.tedu.drone.mcp.catalog.ToolSpec;
import cn.tedu.drone.mcp.config.McpServerProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 把一个 MCP 工具调用**翻译**成对 `drone-server` 的一次 HTTP 请求。
 *
 * <h2>参数怎么分流</h2>
 * 工具入参是一整包 JSON，但上游要三份东西：
 * <ul>
 *   <li>路径参数 → 替换 `{id}`（按 Schema 类型校验，只允许字母数字与 `_-`，防路径穿越）；</li>
 *   <li>查询参数 → 拼到 URL；</li>
 *   <li>请求体 → 只有 OpenAPI 声明了 requestBody 才发。
 *       `/route/delete?id=` 这类「POST + 纯 query」不能多发一个 `{}`。</li>
 * </ul>
 *
 * <h2>身份</h2>
 * 把调用方传来的 `Authorization` **原样**转发。没有它就直接失败、**不发上游请求**——
 * 这样不会出现「以服务身份匿名执行」的路径。
 *
 * <h2>响应语义</h2>
 * `drone-server` 的成功是 HTTP 200 + `state=200`；鉴权失败才是 401/403。
 * 三种情况必须给模型不同的文案，否则「登录过期」会被误报成「没权限」。
 */
@Slf4j
@Component
public class ToolInvoker {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 路径参数只允许这些字符：既够用，又能挡住 `../` 之类的注入 */
    private static final String SAFE_PATH_VALUE = "[A-Za-z0-9_-]{1,64}";

    private final UpstreamClient upstream;
    private final McpServerProperties properties;
    /** 写工具幂等（REQ-AGENT-0027） */
    private final IdempotencyStore idempotency;

    @Autowired
    public ToolInvoker(UpstreamClient upstream, McpServerProperties properties) {
        this(upstream, properties, new IdempotencyStore());
    }

    ToolInvoker(UpstreamClient upstream, McpServerProperties properties, IdempotencyStore idempotency) {
        this.upstream = upstream;
        this.properties = properties;
        this.idempotency = idempotency;
    }

    /** 兼容既有调用：不带 traceId */
    public ToolCallResult call(ToolSpec spec, JsonNode arguments, String authorization) {
        return call(spec, arguments, authorization, null);
    }

    public ToolCallResult call(ToolSpec spec, JsonNode arguments, String authorization, String traceId) {
        return call(spec, arguments, authorization, traceId, null);
    }

    /**
     * 带写操作 id 的调用（REQ-AGENT-0029）：续跑/重试复用同一个 operationId 时，
     * MCP 幂等层直接复用结果，不再打上游。
     */
    public ToolCallResult call(ToolSpec spec, JsonNode arguments, String authorization, String traceId,
                               String operationId) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            log.warn("【MCP-Server】拒绝调用：tool={}，原因=缺少 Authorization；未向上游发送任何请求", spec.name());
            return ToolCallResult.error("当前请求缺少用户身份，无法执行平台操作。请重新登录后再试。");
        }

        JsonNode args = arguments == null || arguments.isNull() ? MAPPER.createObjectNode() : arguments;

        // 只有「写工具 + 有 traceId」才做幂等：读工具重复无害，旧调用没有可关联的键
        if (spec.readOnly() || traceId == null || traceId.isBlank()) {
            return invoke(spec, args, authorization, traceId).result();
        }
        String scope = operationId != null && !operationId.isBlank() ? operationId : traceId;
        String key = idempotencyKey(scope, authorization, spec, args);
        return idempotency.execute(key, () -> invoke(spec, args, authorization, traceId));
    }

    /**
     * 真正执行一次上游调用，并给出「结果是否可缓存」。
     *
     * <p>写工具的超时 / 5xx / 网络异常属于**结果未知**：缓存一句「请先查询确认」，
     * 让重试不再打上游——盲目重试比不重试更危险。
     */
    private IdempotencyStore.Invocation invoke(ToolSpec spec, JsonNode args, String authorization,
                                               String traceId) {
        boolean write = !spec.readOnly();

        String path;
        try {
            path = renderPath(spec, args);
        } catch (IllegalArgumentException e) {
            return fail(e.getMessage());
        }

        String url = buildUrl(spec, args, path);
        HttpRequest request;
        try {
            request = buildRequest(spec, args, url, authorization, traceId);
        } catch (IllegalArgumentException e) {
            return fail("参数无法序列化：" + e.getMessage());
        }

        long start = System.nanoTime();
        log.info("【MCP-Server】调用上游：tool={}，user={}，traceId={}，{} {}，线程={}",
                spec.name(), userIdOf(authorization), traceId, spec.httpMethod(), path,
                Thread.currentThread().getName());

        try {
            HttpResponse<java.io.InputStream> response = upstream.execute(request);
            int status = response.statusCode();
            String body = upstream.readCapped(response.body());
            long cost = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            if (properties.logRawPayload()) {
                log.info("【MCP-RAW】上游响应 ← HTTP {}，body={}", status, body);
            }

            if (status == 401) {
                log.warn("【MCP-Server】上游 401：tool={}，耗时={}ms；将提示用户重新登录", spec.name(), cost);
                return fail("登录已失效，请重新登录后再试。");
            }
            if (status == 403) {
                log.warn("【MCP-Server】上游 403：tool={}，耗时={}ms；当前账号无此权限", spec.name(), cost);
                return fail("当前账号没有执行该操作的权限。");
            }
            if (status < 200 || status >= 300) {
                log.warn("【MCP-Server】上游异常状态：tool={}，HTTP {}，耗时={}ms", spec.name(), status, cost);
                if (write && status >= 500) {
                    return unknown("平台接口暂时不可用（HTTP " + status
                            + "），写操作结果未知。请先查询确认状态，不要直接重试。");
                }
                return fail("平台接口返回 HTTP " + status + "：" + snippet(body));
            }

            ToolCallResult result = unwrap(body);
            log.info("【MCP-Server】上游返回：tool={}，traceId={}，HTTP {}，耗时={}ms，isError={}，结果 {} 字符",
                    spec.name(), traceId, status, cost, result.isError(), result.text().length());
            return result.isError() ? fail(result.text()) : success(result.text());
        } catch (HttpTimeoutException e) {
            log.warn("【MCP-Server】上游超时：tool={}，超时={}ms", spec.name(),
                    properties.upstreamTimeout() == null ? -1 : properties.upstreamTimeout().toMillis());
            if (write) {
                return unknown("平台接口超时，写操作结果未知。请先用查询工具确认状态，不要直接重试。");
            }
            return fail("调用平台接口超时，请稍后重试或缩小查询范围。");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("【MCP-Server】上游调用被中断：tool={}", spec.name());
            if (write) {
                return unknown("平台接口调用被中断，写操作结果未知。请先查询确认状态，不要直接重试。");
            }
            return fail("调用平台接口被中断，请重试。");
        } catch (Exception e) {
            log.warn("【MCP-Server】上游调用失败：tool={}，原因={}", spec.name(), e.getMessage());
            if (write) {
                return unknown("平台接口调用失败（" + e.getMessage()
                        + "），写操作结果未知。请先查询确认状态，不要直接重试。");
            }
            return fail("调用平台接口失败：" + e.getMessage());
        }
    }

    private static IdempotencyStore.Invocation success(String text) {
        return new IdempotencyStore.Invocation(ToolCallResult.ok(text),
                IdempotencyStore.CachePolicy.SUCCESS);
    }

    private static IdempotencyStore.Invocation fail(String text) {
        return new IdempotencyStore.Invocation(ToolCallResult.error(text),
                IdempotencyStore.CachePolicy.NONE);
    }

    private static IdempotencyStore.Invocation unknown(String text) {
        return new IdempotencyStore.Invocation(ToolCallResult.error(text),
                IdempotencyStore.CachePolicy.UNKNOWN);
    }

    /**
     * 幂等键：trace + 用户 + 工具 + 规范化参数。
     *
     * <p>参数对象递归排序后再序列化，避免模型重试时字段顺序不同被当成两个操作。
     * 键里只有用户 id 的派生值，不含 Token。
     */
    private static String idempotencyKey(String traceId, String authorization, ToolSpec spec,
                                         JsonNode args) {
        String raw = traceId + "|" + userIdOf(authorization) + "|" + spec.name()
                + "|" + canonical(args);
        return sha256(raw);
    }

    private static String canonical(JsonNode node) {
        return canonicalNode(node).toString();
    }

    private static JsonNode canonicalNode(JsonNode node) {
        if (node.isObject()) {
            ObjectNode sorted = MAPPER.createObjectNode();
            List<String> names = new ArrayList<>();
            node.fieldNames().forEachRemaining(names::add);
            names.sort(String::compareTo);
            for (String name : names) {
                sorted.set(name, canonicalNode(node.get(name)));
            }
            return sorted;
        }
        if (node.isArray()) {
            ArrayNode array = MAPPER.createArrayNode();
            for (JsonNode child : node) {
                array.add(canonicalNode(child));
            }
            return array;
        }
        return node;
    }

    private static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 不支持 SHA-256", e);
        }
    }

    /** 替换路径里的 `{param}`，同时做取值校验 */
    private static String renderPath(ToolSpec spec, JsonNode args) {
        String path = spec.pathTemplate();
        for (String param : spec.pathParams()) {
            JsonNode value = args.path(param);
            if (value.isMissingNode() || value.isNull() || value.asText().isBlank()) {
                throw new IllegalArgumentException("缺少路径参数：" + param);
            }
            String text = value.asText();
            if (!text.matches(SAFE_PATH_VALUE)) {
                throw new IllegalArgumentException("路径参数 " + param + " 不合法");
            }
            path = path.replace("{" + param + "}", urlEncode(text));
        }
        return path;
    }

    private String buildUrl(ToolSpec spec, JsonNode args, String path) {
        String base = properties.upstreamUrl();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        List<String> query = new ArrayList<>();
        for (String param : spec.queryParams()) {
            JsonNode value = args.path(param);
            if (value.isMissingNode() || value.isNull()) {
                continue;
            }
            query.add(urlEncode(param) + "=" + urlEncode(value.asText()));
        }
        return query.isEmpty() ? base + path : base + path + "?" + String.join("&", query);
    }

    private HttpRequest buildRequest(ToolSpec spec, JsonNode args, String url, String authorization,
                                     String traceId) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(properties.upstreamTimeout() == null
                        ? java.time.Duration.ofSeconds(10) : properties.upstreamTimeout())
                .header("Authorization", authorization)
                .header("Accept", "application/json");
        if (traceId != null && !traceId.isBlank()) {
            builder.header("X-Trace-Id", traceId);
        }

        String bodyText = null;
        if (spec.hasRequestBody()) {
            ObjectNode body = MAPPER.createObjectNode();
            for (String field : spec.bodyParams()) {
                JsonNode value = args.path(field);
                if (!value.isMissingNode() && !value.isNull()) {
                    body.set(field, value);
                }
            }
            bodyText = body.toString();
            builder.header("Content-Type", "application/json; charset=utf-8")
                    .method(spec.httpMethod(),
                            HttpRequest.BodyPublishers.ofString(bodyText, StandardCharsets.UTF_8));
        } else {
            builder.method(spec.httpMethod(), HttpRequest.BodyPublishers.noBody());
        }

        if (properties.logRawPayload()) {
            log.info("【MCP-RAW】上游请求 → {} {}，body={}",
                    spec.httpMethod(), url, bodyText == null ? "(无请求体)" : bodyText);
        }
        return builder.build();
    }

    /**
     * 解析统一响应体。
     *
     * <p>`data` 为空时返回 `message`（删除类接口的 `data` 就是 null）；
     * `data` 存在时返回紧凑 JSON，省 token。
     */
    private static ToolCallResult unwrap(String body) {
        JsonNode envelope;
        try {
            envelope = MAPPER.readTree(body);
        } catch (Exception e) {
            return ToolCallResult.error("平台返回了无法解析的内容：" + snippet(body));
        }
        int state = envelope.path("state").asInt(-1);
        String message = envelope.path("message").asText("");
        if (state == 200 || state == 0) {
            JsonNode data = envelope.path("data");
            if (data.isMissingNode() || data.isNull()) {
                return ToolCallResult.ok(message.isBlank() ? "操作成功" : message);
            }
            return ToolCallResult.ok(data.toString());
        }
        return ToolCallResult.error(message.isBlank() ? "业务处理失败（state=" + state + "）" : message);
    }

    /** 只取前 200 字符作为线索，避免把整页错误信息塞进上下文 */
    private static String snippet(String text) {
        if (text == null) {
            return "(空)";
        }
        return text.length() <= 200 ? text : text.substring(0, 200) + "…";
    }

    /**
     * 从 JWT 的 payload 里取 `sub`，**仅用于日志关联**。
     *
     * <p>刻意不验签：验签是 `drone-server` 的事；这里只想知道「大概是谁」，
     * 即便被伪造也只会影响日志，不会影响权限判定。
     */
    static String userIdOf(String authorization) {
        try {
            String token = authorization.substring("Bearer ".length());
            String[] parts = token.split("\\.");
            if (parts.length < 2) {
                return "unknown";
            }
            String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
            JsonNode node = MAPPER.readTree(payload);
            String sub = node.path("sub").asText("");
            return sub.isBlank() ? "unknown" : sub + "(未验签)";
        } catch (Exception e) {
            return "unknown";
        }
    }

    private static String urlEncode(String value) {
        // URLEncoder 会把空格编成 +，路径里不合法，统一换成 %20
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
