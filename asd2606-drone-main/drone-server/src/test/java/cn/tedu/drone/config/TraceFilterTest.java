package cn.tedu.drone.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link TraceFilter} 的测试：合法头进 MDC、请求结束清理、非法头不写入。
 *
 * <p>MDC 是 ThreadLocal 的，测试直接用当前线程断言；Filter 的 `finally` 必须清掉，
 * 否则线程池复用时会串到下一个请求。
 */
class TraceFilterTest {

    private final TraceFilter filter = new TraceFilter();

    @Test
    @DisplayName("合法 X-Trace-Id：写入 MDC、回写响应头，请求结束清理")
    void putsAndClearsTraceId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/task/list");
        request.addHeader("X-Trace-Id", "trace-1234");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> inside = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) -> inside.set(MDC.get("traceId")));

        assertEquals("trace-1234", inside.get());
        assertEquals("trace-1234", response.getHeader("X-Trace-Id"));
        assertNull(MDC.get("traceId"), "请求结束后必须清理，避免线程复用串号");
    }

    @Test
    @DisplayName("非法头：不写入 MDC，也不回写响应头")
    void ignoresIllegalTraceId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/task/list");
        request.addHeader("X-Trace-Id", "bad value with spaces");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> inside = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) -> inside.set(MDC.get("traceId")));

        assertNull(inside.get());
        assertNull(response.getHeader("X-Trace-Id"));
    }
}
