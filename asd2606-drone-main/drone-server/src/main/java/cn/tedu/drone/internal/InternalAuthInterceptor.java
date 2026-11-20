package cn.tedu.drone.internal;

import cn.tedu.drone.common.JsonResult;
import cn.tedu.drone.config.InternalProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 内部接口的鉴权拦截器：校验请求头里的共享密钥。
 *
 * <p>与用户侧的 {@code AuthInterceptor} 分开，因为两者的凭据不是一回事：
 * 用户请求带的是 JWT，服务间请求带的是共享密钥。混在一个拦截器里会让
 * 白名单与鉴权逻辑纠缠（内部接口本来就不该要求用户登录）。
 *
 * <p>用 {@link MessageDigest#isEqual} 做比较而不是 {@code String.equals}：
 * 后者会在第一个不同的字符处提前返回，比较耗时与「猜对了几位」相关，
 * 理论上可被计时攻击逐位试探。共享密钥这种长期不变的凭据值得用恒定时间比较。
 */
@Slf4j
@Component
public class InternalAuthInterceptor implements HandlerInterceptor {

    /** 存放共享密钥的请求头名称 */
    public static final String INTERNAL_TOKEN_HEADER = "X-Internal-Token";

    private final InternalProperties properties;
    private final ObjectMapper objectMapper;

    public InternalAuthInterceptor(InternalProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        String expected = properties.getToken();
        String actual = request.getHeader(INTERNAL_TOKEN_HEADER);

        if (expected == null || expected.isBlank()) {
            log.error("内部接口密钥未配置（drone.internal.token），拒绝所有内部请求");
            reject(response, HttpServletResponse.SC_UNAUTHORIZED, "内部接口未配置密钥");
            return false;
        }

        if (actual == null || !MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8))) {
            log.warn("内部请求被拒：密钥不正确，{} {}", request.getMethod(), request.getRequestURI());
            reject(response, HttpServletResponse.SC_UNAUTHORIZED, "内部密钥不正确");
            return false;
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
