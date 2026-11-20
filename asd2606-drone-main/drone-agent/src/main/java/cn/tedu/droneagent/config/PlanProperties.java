package cn.tedu.droneagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Plan 模式配置，对应 yml 节点 {@code agent.plan.*}（`REQ-AGENT-0031`）。
 *
 * @param enabled  总开关；关掉后仍然走原 ReAct
 * @param root     生成计划落盘目录；空表示只存内存
 * @param ttl      生成计划有效期
 * @param parallel 计划内只读步骤的并发上限
 */
@ConfigurationProperties(prefix = "agent.plan")
public record PlanProperties(
        boolean enabled,
        String root,
        Duration ttl,
        int parallel
) {
}
