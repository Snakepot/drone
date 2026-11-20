package cn.tedu.droneagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 子 Agent 预算配置，对应 {@code agent.subagent.*}（`REQ-AGENT-0033`）。
 *
 * @param maxPromptTokens     单次子 Agent 运行最多消耗的 prompt token（相对启动时基线）
 * @param minRemainingTokens  主请求剩余额度低于该值时，不允许启动子 Agent
 */
@ConfigurationProperties(prefix = "agent.subagent")
public record SubAgentProperties(int maxPromptTokens, int minRemainingTokens) {
}
