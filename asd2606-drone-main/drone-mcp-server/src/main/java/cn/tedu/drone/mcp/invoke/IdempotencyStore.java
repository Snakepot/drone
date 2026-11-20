package cn.tedu.drone.mcp.invoke;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 写工具的幂等缓存（`REQ-AGENT-0027`）。
 *
 * <h2>为什么需要它</h2>
 * 编排层允许模型在工具失败后再试；如果上游**已经执行成功但响应超时**，
 * 重试就会重复派发/重复处置。这里用「traceId + 用户 + 工具 + 规范化参数」做键，
 * 让同一逻辑操作在短时间内只真正执行一次。
 *
 * <h2>三种结果，三种处理</h2>
 * <ul>
 *   <li><b>成功</b>：缓存结果，重试直接复用；</li>
 *   <li><b>结果未知</b>（超时/5xx/网络异常）：缓存一句「请先查询确认」，
 *       重试**不再执行**——盲目重试比不重试更危险；</li>
 *   <li><b>明确失败</b>（401/403/4xx/参数错）：不缓存，修复后可重试。</li>
 * </ul>
 *
 * <h2>并发重复</h2>
 * 同一键同时到达两个请求时，先到者执行，后来者等待并复用结果；
 * 不会出现「两个请求都打上游」。
 *
 * <h2>它不负责什么</h2>
 * <ul>
 *   <li>不做跨进程共享：缓存是进程内的，重启/多实例会失效；</li>
 *   <li>不替代下游幂等：`drone-server` 仍没有幂等键，这里只覆盖经过 MCP 的调用；</li>
 *   <li>不缓存读工具：重复读无害，缓存反而增加内存。</li>
 * </ul>
 */
@Slf4j
public class IdempotencyStore {

    /** 成功/未知结果保留 10 分钟：覆盖模型重试与用户短暂重问的窗口 */
    private static final Duration TTL = Duration.ofMinutes(10);

    /** 最多存 2000 条，防止长时间运行无界增长 */
    private static final int MAX_ENTRIES = 2000;

    /** 等待同键首个结果的最长时间（上游超时 + 余量） */
    private static final long WAIT_MILLIS = 15_000;

    private final Cache<String, Invocation> cache;

    private final ConcurrentHashMap<String, CompletableFuture<ToolCallResult>> inFlight =
            new ConcurrentHashMap<>();

    public IdempotencyStore() {
        this(TTL, MAX_ENTRIES);
    }

    /** 包可见：测试用短 TTL / 小容量验证过期与淘汰 */
    IdempotencyStore(Duration ttl, int maxEntries) {
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(ttl)
                .maximumSize(maxEntries)
                .build();
    }

    /**
     * 执行或复用一次写操作。
     *
     * @param key    幂等键（调用方保证稳定）
     * @param action 真正执行；返回结果与「是否可缓存」
     */
    public ToolCallResult execute(String key, Supplier<Invocation> action) {
        Invocation cached = cache.getIfPresent(key);
        if (cached != null) {
            log.info("【MCP-幂等】命中，跳过一次写操作：key={}", shortKey(key));
            return cached.result();
        }

        CompletableFuture<ToolCallResult> mine = new CompletableFuture<>();
        CompletableFuture<ToolCallResult> existing = inFlight.putIfAbsent(key, mine);
        if (existing != null) {
            log.info("【MCP-幂等】并发重复，等待首个结果：key={}", shortKey(key));
            try {
                return existing.get(WAIT_MILLIS, TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                return ToolCallResult.error("同一操作仍在执行，请稍后查询确认结果，不要立即重试。");
            }
        }

        try {
            Invocation invocation = action.get();
            if (invocation.policy() != CachePolicy.NONE) {
                cache.put(key, invocation);
                if (invocation.policy() == CachePolicy.UNKNOWN) {
                    log.warn("【MCP-幂等】结果未知，已缓存（重试将不再执行）：key={}", shortKey(key));
                }
            }
            mine.complete(invocation.result());
            return invocation.result();
        } catch (RuntimeException e) {
            mine.completeExceptionally(e);
            throw e;
        } finally {
            inFlight.remove(key, mine);
        }
    }

    /** 仅供测试观察缓存规模 */
    long estimatedSize() {
        return cache.estimatedSize();
    }

    private static String shortKey(String key) {
        return key == null ? "null" : key.substring(0, Math.min(12, key.length()));
    }

    /** 是否缓存以及如何缓存 */
    public enum CachePolicy {
        /** 明确失败：不缓存，修复后可重试 */
        NONE,
        /** 成功：缓存结果 */
        SUCCESS,
        /** 超时/5xx/网络异常：缓存「结果未知」，重试不再执行 */
        UNKNOWN
    }

    /** 一次执行的结果 + 缓存策略 */
    public record Invocation(ToolCallResult result, CachePolicy policy) {
    }
}
