package cn.tedu.droneagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 限流配置（`REQ-AGENT-0030`），对应 {@code agent.rate-limit.*}。
 *
 * @param enabled           总开关
 * @param userQps           每用户每秒补充的令牌数
 * @param userBurst         每用户桶容量（允许的瞬时突发）
 * @param userConcurrency   每用户同时处理的请求上限
 * @param globalQps         全局每秒补充令牌数
 * @param globalBurst       全局桶容量
 * @param globalConcurrency 全局同时处理请求上限
 */
@ConfigurationProperties(prefix = "agent.rate-limit")
public record RateLimitProperties(
        boolean enabled,
        int userQps,
        int userBurst,
        int userConcurrency,
        int globalQps,
        int globalBurst,
        int globalConcurrency
) {
}
