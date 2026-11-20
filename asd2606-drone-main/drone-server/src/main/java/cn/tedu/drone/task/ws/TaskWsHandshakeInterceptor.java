package cn.tedu.drone.task.ws;

import cn.tedu.drone.common.JwtUtil;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Map;

/**
 * WebSocket 握手时的登录校验。
 *
 * <h2>为什么不能沿用 {@code AuthInterceptor}</h2>
 * {@code AuthInterceptor} 是 Spring MVC 的拦截器，管的是「Controller 方法」。
 * WebSocket 握手虽然也是一次 HTTP 请求，但它走的是 WebSocket 的处理链，
 * <b>不经过 MVC 的拦截器</b> —— 所以必须在这里单独校验一次。
 *
 * <p>反过来说，MVC 拦截器拦不到它，就意味着 {@code /ws/**} 必须放进白名单，
 * 否则握手请求会先被 MVC 拦下返回 401，根本轮不到这里。两件事是配套的。
 *
 * <h2>Token 从哪来</h2>
 * 按顺序找两个地方，谁先有就用谁：
 * <ol>
 *   <li><b>请求头</b> {@code Authorization: Bearer xxx} ——
 *       微信小程序的 {@code wx.connectSocket} 支持自定义 header，走这条最规范</li>
 *   <li><b>URL 查询参数</b> {@code ?token=xxx} ——
 *       <b>浏览器原生的 WebSocket 不能自定义请求头</b>，只能把 token 放在地址里</li>
 * </ol>
 * 两种都支持，是因为同一个后端要同时服务小程序和 H5。要注意第二种会让 token
 * 出现在访问日志里，生产环境建议把日志里的 token 参数打码（本项目是学习环境，暂不处理）。
 *
 * <h2>校验失败怎么办</h2>
 * 返回 false 拒绝握手，并显式把状态码设成 401（默认会是 403）——
 * 让前端拿到的语义和调 REST 接口时保持一致：401 = 你没登录/登录失效。
 */
@Slf4j
@Component
public class TaskWsHandshakeInterceptor implements HandshakeInterceptor {

    /** 校验通过后往 session 里放的键名：当前登录用户 id */
    public static final String ATTR_USER_ID = "userId";

    private static final String AUTH_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";
    private static final String TOKEN_PARAM = "token";

    private final JwtUtil jwtUtil;

    public TaskWsHandshakeInterceptor(JwtUtil jwtUtil) {
        this.jwtUtil = jwtUtil;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        String token = resolveToken(request);
        if (!StringUtils.hasText(token)) {
            log.warn("WebSocket 握手被拒：没有携带 token，uri={}", request.getURI().getPath());
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }

        try {
            Claims claims = jwtUtil.parseToken(token).getPayload();
            // subject 里存的是用户 id（见 JwtUtil.generateToken）
            Long userId = Long.valueOf(claims.getSubject());

            // 把身份放进 session 属性：连上之后的每条消息都能取到「这是谁」
            attributes.put(ATTR_USER_ID, userId);

            log.info("WebSocket 握手通过，userId={}", userId);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            log.warn("WebSocket 握手被拒：token 无效（{}），uri={}", e.getMessage(), request.getURI().getPath());
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        // 握手之后没有额外要做的：连接的登记在 handler 的 afterConnectionEstablished 里
    }

    /** 先看请求头，再看查询参数 */
    private String resolveToken(ServerHttpRequest request) {
        String header = request.getHeaders().getFirst(AUTH_HEADER);
        if (StringUtils.hasText(header) && header.startsWith(BEARER_PREFIX)) {
            return header.substring(BEARER_PREFIX.length()).trim();
        }

        String fromQuery = UriComponentsBuilder.fromUri(request.getURI())
                .build()
                .getQueryParams()
                .getFirst(TOKEN_PARAM);
        return StringUtils.hasText(fromQuery) ? fromQuery.trim() : null;
    }
}
