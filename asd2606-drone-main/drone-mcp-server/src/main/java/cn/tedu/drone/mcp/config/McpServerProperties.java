package cn.tedu.drone.mcp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * MCP 服务配置，对应 yml 的 {@code drone.mcp-server.*}。
 *
 * <h2>为什么上游地址必须可配</h2>
 * 本机联调是 {@code localhost:8080}，部署后是内网地址；硬编码会让镜像只能跑在开发机。
 *
 * <h2>刷新为什么是可选项</h2>
 * 启动时的工具目录来自打包进 classpath 的 OpenAPI 快照，**不依赖 drone-server 是否在线**。
 * 后台刷新只是「发现接口漂移」的辅助手段：失败只记 WARN，绝不阻断或回滚已加载的目录。
 *
 * @param upstreamUrl        drone-server 基础地址
 * @param upstreamTimeout    单次上游调用超时；必须小于 agent 的 MCP 请求超时
 * @param maxResponseBytes   单次工具结果的最大字节数，超出截断
 * @param logRawPayload      是否打印原始报文（MCP 请求/响应 + 上游请求/响应）。
 *                           排障用；报文含业务数据，**生产建议关闭**。Authorization 永不打印。
 * @param refresh            后台刷新策略
 */
@ConfigurationProperties(prefix = "drone.mcp-server")
public record McpServerProperties(
        String upstreamUrl,
        Duration upstreamTimeout,
        int maxResponseBytes,
        boolean logRawPayload,
        Refresh refresh
) {

    /**
     * 快照刷新策略。
     *
     * @param enabled    是否在启动后后台刷新一次
     * @param openapiUrl drone-server 的 OpenAPI 地址
     * @param retries    失败重试次数（总计 attempts = retries + 1）
     * @param delay      每次重试的间隔
     */
    public record Refresh(
            boolean enabled,
            String openapiUrl,
            int retries,
            Duration delay
    ) {
    }
}
