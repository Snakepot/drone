package cn.tedu.droneagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 链路追踪配置，对应 yml 的 {@code agent.trace.*}（`REQ-AGENT-0026` P1）。
 *
 * @param enabled 总开关；关闭时所有记录方法都是空操作
 * @param root    落盘根目录；按 {@code {root}/{userId}/{traceId}.jsonl} 存放
 * @param ttl     保留时长；启动与每小时最多清理一次
 */
@ConfigurationProperties(prefix = "agent.trace")
public record TraceProperties(
        boolean enabled,
        String root,
        Duration ttl
) {
}
