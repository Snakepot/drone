package cn.tedu.droneagent.interceptor;

import cn.tedu.droneagent.authority.AuthoritiesClient;
import cn.tedu.droneagent.common.AuthoritiesUnavailableException;
import cn.tedu.droneagent.common.JsonResult;
import cn.tedu.droneagent.common.JwtUtil;
import cn.tedu.droneagent.common.LoginUser;
import cn.tedu.droneagent.common.RequirePermission;
import cn.tedu.droneagent.common.UserContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.AsyncHandlerInterceptor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 登录校验 + 权限校验拦截器，与 drone-server 的 {@code AuthInterceptor} 同款做法。
 *
 * <h2>鉴权用的数据从哪来</h2>
 * <ul>
 *   <li><b>身份证</b>（你是谁）：本地用共享密钥验签，不需要问 drone-server</li>
 *   <li><b>权限表</b>（你能做什么）：问 drone-server 的内部接口要，见 {@link AuthoritiesClient}</li>
 * </ul>
 *
 * <h2>状态码约定（与 drone-server 保持一致，前端可复用同一套处理）</h2>
 * <ul>
 *   <li><b>401</b> 未认证：没带 Token 或 Token 失效 —— 该去登录</li>
 *   <li><b>403</b> 未授权：登录了但缺权限 —— 登录也没用</li>
 *   <li><b>503</b> 鉴权服务不可用：本服务拿不到权限数据（drone-server 挂了）——
 *       与用户无关，不该误导他重新登录，所以单独一个码</li>
 * </ul>
 */
@Slf4j
@Component
public class AuthInterceptor implements AsyncHandlerInterceptor {

    private static final String AUTH_HEADER = "Authorization";

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtUtil jwtUtil;

    private final AuthoritiesClient authoritiesClient;

    private final ObjectMapper objectMapper;

    public AuthInterceptor(JwtUtil jwtUtil, AuthoritiesClient authoritiesClient, ObjectMapper objectMapper) {
        this.jwtUtil = jwtUtil;
        this.authoritiesClient = authoritiesClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        String uri = request.getMethod() + " " + request.getRequestURI();

        // 跨域预检不带 Token，必须放行
        if (HttpMethod.OPTIONS.matches(request.getMethod())) {
            return true;
        }

        String header = request.getHeader(AUTH_HEADER);
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            log.warn("拦截请求：未携带 Token 或格式不正确，{}", uri);
            reject(response, HttpServletResponse.SC_UNAUTHORIZED, "未登录，请先登录");
            return false;
        }

        String token = header.substring(BEARER_PREFIX.length()).trim();

        LoginUser loginUser;
        try {
            Claims claims = jwtUtil.parseToken(token);
            Long userId = Long.valueOf(claims.getSubject());
            String username = claims.get("username", String.class);
            // 角色与权限从 drone-server 取（带缓存），不放进 Token。
            // 注意：缓存里那份 LoginUser 是**共享实例**，绝不能往上写 token——
            // 否则凭据会留在缓存里，还可能被别的请求读到。这里复制一份请求级对象。
            LoginUser cached = authoritiesClient.load(userId, username);
            loginUser = new LoginUser()
                    .setId(cached.getId())
                    .setUsername(cached.getUsername())
                    .setRoles(cached.getRoles())
                    .setPermissions(cached.getPermissions())
                    .setBearerToken(token);
            UserContext.set(loginUser);
        } catch (JwtException | IllegalArgumentException e) {
            log.warn("拦截请求：Token 校验未通过，{}", uri);
            reject(response, HttpServletResponse.SC_UNAUTHORIZED, "登录状态已失效，请重新登录");
            return false;
        } catch (AuthoritiesUnavailableException e) {
            log.error("拦截请求：取用户权限失败，{}，原因：{}", uri, e.getMessage());
            reject(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "鉴权服务暂时不可用，请稍后重试");
            return false;
        }

        if (!hasRequirePermission(handler, loginUser)) {
            reject(response, HttpServletResponse.SC_FORBIDDEN, "没有访问该功能的权限，请联系管理员");
            return false;
        }

        log.debug("放行请求：{}，userId={}，角色={}", uri, loginUser.getId(), loginUser.getRoles());
        return true;
    }

    /**
     * 请求处理完成后清理 ThreadLocal。Tomcat 线程复用，不清理会造成越权。
     */
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        UserContext.clear();
    }

    /**
     * 异步请求（{@code /chat/stream} 那条 SSE 流）进入异步处理时调用。
     *
     * <p>为什么单独处理这件事：异步请求下 {@link #afterCompletion} 是在异步处理
     * <b>结束之后、另一个线程上</b> 被调用的，而 {@code preHandle} 当初是在请求线程上
     * 把用户放进口袋的。请求线程一进异步就还回线程池了 ——
     * 如果这时候不清理，它的 ThreadLocal 会一直残留，被下一个恰好复用到该线程的请求读到。
     * 这正是 {@link UserContext} 里反复强调的那个隐蔽越权场景。
     */
    @Override
    public void afterConcurrentHandlingStarted(HttpServletRequest request, HttpServletResponse response,
                                               Object handler) {
        UserContext.clear();
    }

    /**
     * 核对目标方法（或其所在类）上声明的权限。没标注解表示「登录即可访问」。
     */
    private boolean hasRequirePermission(Object handler, LoginUser loginUser) {
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }

        RequirePermission required = handlerMethod.getMethodAnnotation(RequirePermission.class);
        if (required == null) {
            required = handlerMethod.getBeanType().getAnnotation(RequirePermission.class);
        }
        if (required == null) {
            return true;
        }

        for (String code : required.value()) {
            if (!loginUser.hasPermission(code)) {
                log.warn("权限不足：userId={}，缺少权限 {}", loginUser.getId(), code);
                return false;
            }
        }
        return true;
    }

    private void reject(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(objectMapper.writeValueAsString(JsonResult.fail(status, message)));
    }
}
