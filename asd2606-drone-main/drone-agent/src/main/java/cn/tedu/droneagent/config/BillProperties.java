package cn.tedu.droneagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 账单落盘配置（`REQ-AGENT-0025`），对应 {@code agent.bill.*}。
 *
 * @param enabled       总开关；关掉即回到「只打日志」，不落盘
 * @param root          JSONL 根目录；部署应挂卷
 * @param retentionDays 保留天数，超期文件在启动/跨天时清理
 */
@ConfigurationProperties(prefix = "agent.bill")
public record BillProperties(
        boolean enabled,
        String root,
        int retentionDays
) {
}
