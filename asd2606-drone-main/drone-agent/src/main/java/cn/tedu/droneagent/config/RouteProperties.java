package cn.tedu.droneagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 任务路由配置，对应 {@code agent.route.*}（`REQ-AGENT-0033`）。 */
@ConfigurationProperties(prefix = "agent.route")
public record RouteProperties(boolean enabled) {
}
