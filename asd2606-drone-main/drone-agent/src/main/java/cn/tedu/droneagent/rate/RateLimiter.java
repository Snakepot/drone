package cn.tedu.droneagent.rate;

import cn.tedu.droneagent.config.RateLimitProperties;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

/**
 * 请求限流（`REQ-AGENT-0030`）：**每用户 + 全局**的 QPS 与并发。
 *
 * <h2>为什么预算之外还要限流</h2>
 * 预算是「按天 token」的粗粒度控制，挡不住短时突发：一个用户开多个会话，
 * 就能在额度内把模型与下游打满。限流在入口直接拒绝，不调模型、不占会话锁。
 *
 * <h2>两道维度</h2>
 * <ul>
 *   <li><b>QPS</b>：令牌桶（容量 = burst，每秒补充 QPS 个）；</li>
 *   <li><b>并发</b>：同时处理的请求数。</li>
 * </ul>
 * 先检查并发（便宜且立即释放），再扣令牌；任一失败回滚已占用的计数。
 *
 * <h2>它不负责什么</h2>
 * 不做排队、不做分布式、不按工具/接口细分。单实例内存，多实例需网关或 Redis。
 */
@Slf4j
public class RateLimiter {

    private final boolean enabled;
    private final int userQps;
    private final int userBurst;
    private final int userConcurrency;
    private final int globalQps;
    private final int globalBurst;
    private final int globalConcurrency;

    private final LongSupplier clock;
    private final Cache<Long, UserState> users;
    private final Bucket globalBucket;
    private final AtomicInteger globalConcurrent = new AtomicInteger();

    public static RateLimiter unlimited() {
        return new RateLimiter(new RateLimitProperties(false, 1, 1, 1, 1, 1, 1),
                System::nanoTime);
    }

    public RateLimiter(RateLimitProperties properties) {
        this(properties, System::nanoTime);
    }

    /** 包可见：测试注入可控时钟 */
    RateLimiter(RateLimitProperties properties, LongSupplier clock) {
        this.enabled = properties.enabled();
        this.userQps = Math.max(1, properties.userQps());
        this.userBurst = Math.max(1, properties.userBurst());
        this.userConcurrency = Math.max(1, properties.userConcurrency());
        this.globalQps = Math.max(1, properties.globalQps());
        this.globalBurst = Math.max(1, properties.globalBurst());
        this.globalConcurrency = Math.max(1, properties.globalConcurrency());
        this.clock = clock;
        this.users = Caffeine.newBuilder()
                .maximumSize(10_000)
                .expireAfterAccess(Duration.ofMinutes(10))
                .build();
        this.globalBucket = new Bucket(globalBurst, globalQps, clock.getAsLong());
    }

    /**
     * 尝试获取一个请求许可；拒绝时 {@code permit == null}，并带原因。
     */
    public Decision tryAcquire(long userId) {
        if (!enabled) {
            return new Decision(Permit.noop(), null);
        }
        long now = clock.getAsLong();

        if (globalConcurrent.incrementAndGet() > globalConcurrency) {
            globalConcurrent.decrementAndGet();
            return reject(Reason.GLOBAL_CONCURRENCY);
        }
        UserState user = users.get(userId,
                key -> new UserState(new Bucket(userBurst, userQps, now), new AtomicInteger()));
        if (user.concurrent().incrementAndGet() > userConcurrency) {
            user.concurrent().decrementAndGet();
            globalConcurrent.decrementAndGet();
            return reject(Reason.USER_CONCURRENCY);
        }
        if (!globalBucket.tryTake(now)) {
            release(user);
            return reject(Reason.GLOBAL_QPS);
        }
        if (!user.bucket().tryTake(now)) {
            release(user);
            return reject(Reason.USER_QPS);
        }
        return new Decision(new Permit(this, user), null);
    }

    /** 拒绝原因的固定话术 */
    public static String message(Reason reason) {
        return switch (reason) {
            case USER_QPS -> "请求太频繁，请稍后再试。";
            case USER_CONCURRENCY -> "你已有请求正在处理，请等其中一个完成后再试。";
            case GLOBAL_QPS, GLOBAL_CONCURRENCY -> "当前使用人数较多，请稍后再试。";
        };
    }

    private static Decision reject(Reason reason) {
        log.info("【限流】拒绝请求：原因={}", reason);
        return new Decision(null, reason);
    }

    private void release(UserState user) {
        user.concurrent().decrementAndGet();
        globalConcurrent.decrementAndGet();
    }

    public enum Reason {
        USER_QPS, USER_CONCURRENCY, GLOBAL_QPS, GLOBAL_CONCURRENCY
    }

    /** 获取结果：permit 为 null 表示拒绝 */
    public record Decision(Permit permit, Reason reason) {
        public boolean allowed() {
            return permit != null;
        }
    }

    /** 一次请求的许可；close 幂等 */
    public static final class Permit implements AutoCloseable {

        private final RateLimiter owner;
        private final UserState user;
        private final AtomicBoolean closed = new AtomicBoolean(false);

        private Permit(RateLimiter owner, UserState user) {
            this.owner = owner;
            this.user = user;
        }

        private static Permit noop() {
            return new Permit(null, null);
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true) || owner == null) {
                return;
            }
            owner.release(user);
        }
    }

    private record UserState(Bucket bucket, AtomicInteger concurrent) {
    }

    /** 令牌桶：容量 burst，每秒补充 qps 个 */
    private static final class Bucket {

        private final int capacity;
        private final double tokensPerNano;
        private double tokens;
        private long lastNanos;

        private Bucket(int capacity, int qps, long now) {
            this.capacity = capacity;
            this.tokensPerNano = qps / 1_000_000_000.0;
            this.tokens = capacity;
            this.lastNanos = now;
        }

        private synchronized boolean tryTake(long now) {
            long effective = Math.max(now, lastNanos);
            tokens = Math.min(capacity, tokens + (effective - lastNanos) * tokensPerNano);
            lastNanos = effective;
            if (tokens >= 1.0) {
                tokens -= 1.0;
                return true;
            }
            return false;
        }
    }
}
