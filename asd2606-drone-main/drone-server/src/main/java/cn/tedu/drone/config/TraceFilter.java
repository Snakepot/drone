package cn.tedu.drone.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.regex.Pattern;

/**
 * 把上游传来的 {@code X-Trace-Id} 放进 MDC，让本服务日志能与 agent / MCP 的同一请求对上
 * （`REQ-AGENT-0026`）。
 *
 * <h2>为什么用 Filter 而不是在 Controller 里取</h2>
 * Controller 只覆盖业务接口；Filter 覆盖所有进入本服务的请求（含内部接口），
 * 而且能在日志真正开始输出之前设置 MDC。
 *
 * <h2>为什么校验格式</h2>
 * 请求头是外部输入，直接把任意字符串写进日志会带来日志注入风险。
 * 只接受 8～64 位的字母、数字与短横线；不合法时**不设置**，也不报错——
 * 追踪不该让业务请求失败。
 *
 * <h2>边界</h2>
 * MDC 是 ThreadLocal 的：只覆盖当前请求线程。异步线程需要业务代码显式传递；
 * 本服务目前没有跨线程处理业务的路径。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceFilter extends OncePerRequestFilter {

    private static final String HEADER = "X-Trace-Id";

    private static final String MDC_KEY = "traceId";

    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9-]{8,64}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String traceId = request.getHeader(HEADER);
        boolean valid = traceId != null && SAFE.matcher(traceId).matches();
        if (valid) {
            MDC.put(MDC_KEY, traceId);
            response.setHeader(HEADER, traceId);
        }
        try {
            chain.doFilter(request, response);
        } finally {
            if (valid) {
                MDC.remove(MDC_KEY);
            }
        }
    }
}
