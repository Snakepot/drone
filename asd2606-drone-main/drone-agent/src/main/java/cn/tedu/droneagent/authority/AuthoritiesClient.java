package cn.tedu.droneagent.authority;

import cn.tedu.droneagent.common.AuthoritiesUnavailableException;
import cn.tedu.droneagent.common.LoginUser;
import cn.tedu.droneagent.common.TtlCache;
import cn.tedu.droneagent.config.InternalClientProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;

/**
 * 向 drone-server 取用户的角色与权限，并做短期缓存。
 *
 * <p>权限不在 Token 里（见 drone-server 的 docs/RBAC权限设计.md），所以每个受保护请求
 * 都要知道「这个人有哪些权限码」。数据源是 drone-server 的内部接口，
 * 而不是本服务直连数据库 —— 本服务因此不依赖 drone-server 的表结构。
 *
 * <p>缓存的作用是挡住「同一用户短时间内多次请求」时的重复查询。
 * 代价是权限变更最多延迟 {@code agent.internal.cache-seconds} 秒生效。
 */
@Component
public class AuthoritiesClient {

    /** 与 drone-server 的 InternalAuthInterceptor.INTERNAL_TOKEN_HEADER 必须一致 */
    private static final String INTERNAL_TOKEN_HEADER = "X-Internal-Token";

    /** 缓存容量上限，防止用户数增长导致内存无界增长 */
    private static final int CACHE_MAX_SIZE = 10_000;

    private final InternalClientProperties props;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final TtlCache<LoginUser> cache;

    public AuthoritiesClient(InternalClientProperties props, ObjectMapper objectMapper) {
        this.props = props;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(props.getTimeoutMs()))
                .build();
        this.cache = new TtlCache<>(props.getCacheSeconds() * 1000, CACHE_MAX_SIZE);
    }

    /**
     * 取用户的角色与权限。
     *
     * @param userId   Token 里的用户 ID
     * @param username Token 里的用户名，仅用于回填展示，不参与鉴权
     * @throws AuthoritiesUnavailableException 取不到数据（drone-server 不可用或返回异常）
     */
    public LoginUser load(Long userId, String username) {
        LoginUser cached = cache.get(userId);
        if (cached != null) {
            return cached;
        }
        LoginUser fresh = fetch(userId, username);
        cache.put(userId, fresh);
        return fresh;
    }

    private LoginUser fetch(Long userId, String username) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(props.getBaseUrl() + "/internal/user/" + userId + "/authorities"))
                .timeout(Duration.ofMillis(props.getTimeoutMs()))
                .header(INTERNAL_TOKEN_HEADER, props.getToken() == null ? "" : props.getToken())
                .GET()
                .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new AuthoritiesUnavailableException("无法访问 drone-server 内部接口：" + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AuthoritiesUnavailableException("调用 drone-server 被中断", e);
        }

        if (response.statusCode() != 200) {
            throw new AuthoritiesUnavailableException(
                    "drone-server 内部接口返回 HTTP " + response.statusCode() + "：" + response.body());
        }

        try {
            JsonNode root = objectMapper.readTree(response.body());
            if (root.path("state").asInt() != 200) {
                throw new AuthoritiesUnavailableException(
                        "drone-server 内部接口返回业务失败：" + root.path("message").asText());
            }
            JsonNode data = root.path("data");
            return new LoginUser()
                    .setId(userId)
                    .setUsername(username)
                    .setRoles(toSet(data.path("roles")))
                    .setPermissions(toSet(data.path("permissions")));
        } catch (JsonProcessingException e) {
            throw new AuthoritiesUnavailableException("解析 drone-server 返回失败：" + response.body(), e);
        }
    }

    private Set<String> toSet(JsonNode arrayNode) {
        Set<String> set = new HashSet<>();
        if (arrayNode.isArray()) {
            arrayNode.forEach(node -> set.add(node.asText()));
        }
        return set;
    }
}
