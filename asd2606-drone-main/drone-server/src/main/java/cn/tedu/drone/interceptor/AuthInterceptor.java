package cn.tedu.drone.interceptor;

import cn.tedu.drone.common.JsonResult;
import cn.tedu.drone.common.JwtUtil;
import cn.tedu.drone.common.LoginUser;
import cn.tedu.drone.common.RequirePermission;
import cn.tedu.drone.common.UserContext;
import cn.tedu.drone.user.service.UserService;
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
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 登录校验 + 权限校验拦截器 —— 所有请求进入 Controller 之前的一道「门卫」。
 *
 * <h2>一、它在请求流程中的位置</h2>
 * <pre>
 *   浏览器 → Filter 过滤器 → DispatcherServlet → 【本拦截器】 → Controller
 * </pre>
 *
 * <h2>二、它做了四件事</h2>
 * <ol>
 *   <li>从请求头 {@code Authorization} 取出 Token，格式为 {@code Bearer xxxxx.yyyy.zzzz}</li>
 *   <li>交给 {@link JwtUtil} 校验签名和有效期，不通过返回 <b>401</b></li>
 *   <li>查库取出该用户的角色与权限，放进 {@link UserContext}，业务层随时可取</li>
 *   <li>若目标方法标了 {@link RequirePermission}，核对权限，不足则返回 <b>403</b></li>
 * </ol>
 *
 * <h2>三、401 和 403 的区别（容易混）</h2>
 * <ul>
 *   <li><b>401 未认证</b>：你还没登录，或者 Token 已失效 —— 去登录</li>
 *   <li><b>403 未授权</b>：你登录了，但你的角色没有这个权限 —— 登录也没用</li>
 * </ul>
 *
 * <h2>四、为什么用拦截器而不是过滤器</h2>
 * <ul>
 *   <li>拦截器本身是 Spring Bean，可以直接注入 {@code JwtUtil}、{@code UserService}</li>
 *   <li>拦截器能拿到目标方法（{@code HandlerMethod}），这才能读到方法上的
 *       {@code @RequirePermission} 注解 —— 过滤器做不到这一点</li>
 * </ul>
 *
 * <h2>五、哪些请求不需要登录</h2>
 * 白名单在 {@link cn.tedu.drone.config.WebMvcConfig} 里配置，
 * 目前放行的是登录接口和 Knife4j 文档页面。
 */
@Slf4j
@Component
public class AuthInterceptor implements HandlerInterceptor {

    /** 存放 Token 的请求头名称 */
    private static final String AUTH_HEADER = "Authorization";

    /** Token 前缀，约定格式为 "Bearer xxx.yyy.zzz"（注意 Bearer 后面有个空格） */
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtUtil jwtUtil;

    /** 用来查用户的角色与权限 */
    private final UserService userService;

    /** 用来把 401 / 403 的提示信息手动写成 JSON 输出 */
    private final ObjectMapper objectMapper;

    public AuthInterceptor(JwtUtil jwtUtil, UserService userService, ObjectMapper objectMapper) {
        this.jwtUtil = jwtUtil;
        this.userService = userService;
        this.objectMapper = objectMapper;
    }

    /**
     * 在进入 Controller 之前执行。
     *
     * @return true 表示放行，继续走后面的流程；false 表示拦截，请求到此结束
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        String uri = request.getMethod() + " " + request.getRequestURI();

        // 跨域预检请求（OPTIONS）由浏览器自动发出，不会带 Token，必须放行
        if (HttpMethod.OPTIONS.matches(request.getMethod())) {
            log.debug("跨域预检请求放行：{}", uri);
            return true;
        }

        // 第一步：从请求头里取 Token
        String header = request.getHeader(AUTH_HEADER);
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            log.warn("拦截请求：未携带 Token 或格式不正确，{}，请求头 Authorization={}", uri, header);
            reject(response, HttpServletResponse.SC_UNAUTHORIZED, "未登录，请先登录");
            return false;
        }

        // 第二步：去掉 "Bearer " 前缀，剩下的才是 Token 本身
        String token = header.substring(BEARER_PREFIX.length()).trim();

        LoginUser loginUser;
        try {
            // 第三步：校验签名与有效期（失败会抛 JwtException，由 JwtUtil 记录详细原因）
            Claims claims = jwtUtil.parseToken(token).getPayload();
            Long userId = Long.valueOf(claims.getSubject());

            // 第四步：查库补全角色与权限。
            // 刻意不把这些塞进 Token —— 查库才能保证「改了权限下次请求就生效」。
            loginUser = new LoginUser()
                    .setId(userId)
                    .setUsername(claims.get("username", String.class))
                    .setRoles(userService.listRoles(userId))
                    .setPermissions(userService.listPermissions(userId));

            UserContext.set(loginUser);
        } catch (JwtException | IllegalArgumentException e) {
            // JwtUtil 里已经记录了具体原因（过期 / 签名不合法），这里只记录「哪个请求被拒」
            log.warn("拦截请求：Token 校验未通过，{}", uri);
            reject(response, HttpServletResponse.SC_UNAUTHORIZED, "登录状态已失效，请重新登录");
            return false;
        }

        // 第五步：核对方法上声明的权限
        if (!hasRequirePermission(handler, loginUser)) {
            reject(response, HttpServletResponse.SC_FORBIDDEN, "没有访问该功能的权限，请联系管理员");
            return false;
        }

        log.debug("放行请求：{}，当前登录人：userId={}，角色={}", uri, loginUser.getId(), loginUser.getRoles());
        return true;
    }

    /**
     * 请求处理完成后执行（无论成功还是抛异常都会走到这里）。
     *
     * <p><b>这里必须清理 ThreadLocal</b>：Tomcat 的线程是复用的，
     * 不清理的话上一个用户的身份会残留在线程上，被下一个请求读到，造成越权。
     */
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        UserContext.clear();
    }

    /**
     * 核对当前用户是否满足目标方法（或它所在 Controller 类）上声明的权限。
     *
     * <p>方法没标 {@link RequirePermission} 就表示「登录即可访问」，直接放行。
     * 标了多个权限码时要求<b>全部满足</b>。
     */
    private boolean hasRequirePermission(Object handler, LoginUser loginUser) {
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }

        // 先看方法上有没有，没有再看它所在类上有没有
        RequirePermission required = handlerMethod.getMethodAnnotation(RequirePermission.class);
        if (required == null) {
            required = handlerMethod.getBeanType().getAnnotation(RequirePermission.class);
        }
        if (required == null) {
            return true;
        }

        for (String code : required.value()) {
            if (!loginUser.hasPermission(code)) {
                log.warn("权限不足：userId={}，角色={}，缺少权限 {}",
                        loginUser.getId(), loginUser.getRoles(), code);
                return false;
            }
        }
        return true;
    }

    /**
     * 返回指定状态码并中止请求。
     *
     * <p>这里直接往响应里写 JSON，而不是抛异常 —— 因为要精确控制 HTTP 状态码，
     * 让前端能一眼分辨「401 该去登录」和「403 登录了也没用」。
     */
    private void reject(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(objectMapper.writeValueAsString(JsonResult.fail(status, message)));
    }
}
