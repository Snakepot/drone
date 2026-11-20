package cn.tedu.droneagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 预算执行配置（`REQ-AGENT-0028`），对应 {@code agent.budget.*}。
 *
 * <p>预算依赖账单数据：`agent.bill.enabled=false` 时没有用量可读，预算自动失效。
 *
 * @param enabled              总开关；关掉即回到「只度量、不拦截」
 * @param maxRequestTokens     单次问答累计 prompt token 上限
 * @param maxDailyTokensPerUser 单用户当天累计上限
 */
@ConfigurationProperties(prefix = "agent.budget")
public record BudgetProperties(
        boolean enabled,
        int maxRequestTokens,
        int maxDailyTokensPerUser
) {
}
