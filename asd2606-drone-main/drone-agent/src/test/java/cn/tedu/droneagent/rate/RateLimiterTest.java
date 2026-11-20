package cn.tedu.droneagent.rate;

import cn.tedu.droneagent.config.RateLimitProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 限流（REQ-AGENT-0030）：令牌桶、并发、开关、许可幂等。 */
class RateLimiterTest {

    /** 可控时钟（纳秒） */
    private final long[] now = {0L};

    private RateLimiter limiter(int userQps, int userBurst, int userConcurrency,
                                int globalConcurrency) {
        return new RateLimiter(new RateLimitProperties(true, userQps, userBurst, userConcurrency,
                100, 100, globalConcurrency), () -> now[0]);
    }

    @Test
    @DisplayName("用户 QPS：同刻第二次拒绝，时间推进后恢复")
    void userQpsRecoversOverTime() {
        RateLimiter limiter = limiter(1, 1, 10, 10);

        RateLimiter.Decision first = limiter.tryAcquire(5);
        RateLimiter.Decision second = limiter.tryAcquire(5);
        assertTrue(first.allowed());
        assertFalse(second.allowed());
        assertEquals(RateLimiter.Reason.USER_QPS, second.reason());

        now[0] += 1_000_000_000L;
        assertTrue(limiter.tryAcquire(5).allowed());
    }

    @Test
    @DisplayName("用户并发：占满后拒绝，释放后可进")
    void userConcurrencyReleases() {
        RateLimiter limiter = limiter(100, 100, 1, 10);

        RateLimiter.Decision first = limiter.tryAcquire(5);
        RateLimiter.Decision second = limiter.tryAcquire(5);
        assertTrue(first.allowed());
        assertFalse(second.allowed());
        assertEquals(RateLimiter.Reason.USER_CONCURRENCY, second.reason());

        first.permit().close();
        first.permit().close(); // 幂等
        assertTrue(limiter.tryAcquire(5).allowed());
    }

    @Test
    @DisplayName("全局并发：跨用户共享上限")
    void globalConcurrencyIsShared() {
        RateLimiter limiter = limiter(100, 100, 10, 1);

        RateLimiter.Decision first = limiter.tryAcquire(1);
        RateLimiter.Decision second = limiter.tryAcquire(2);
        assertTrue(first.allowed());
        assertFalse(second.allowed());
        assertEquals(RateLimiter.Reason.GLOBAL_CONCURRENCY, second.reason());

        first.permit().close();
        assertTrue(limiter.tryAcquire(2).allowed());
    }

    @Test
    @DisplayName("关闭开关：永不拒绝，许可为 noop")
    void disabledNeverRejects() {
        RateLimiter limiter = new RateLimiter(
                new RateLimitProperties(false, 1, 1, 1, 1, 1, 1), () -> now[0]);

        for (int i = 0; i < 10; i++) {
            RateLimiter.Decision decision = limiter.tryAcquire(5);
            assertTrue(decision.allowed());
            assertNull(decision.reason());
            decision.permit().close();
        }
    }
}
