package cn.tedu.droneagent.config;

import cn.tedu.droneagent.mcp.McpToolset;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MCP 接入的装配。
 *
 * <h2>为什么发现放在 Bean 构造期</h2>
 * {@code ToolRegistry} 在构造时就要校验「行为 ↔ 元数据」一一对应，
 * 所以 MCP 工具必须**在注册表创建之前**就已经发现完毕。
 * 如果改用 {@code ApplicationRunner}（知识库同步就是这么做的），
 * 会出现一个窗口：端口已经开放、请求已经能进来，但工具列表还不完整——
 * 用户问到地图问题时，模型会直接说「我没有这个工具」。
 *
 * <h2>代价（如实说明）</h2>
 * 启动会真的发起网络请求，最坏多等 {@code agent.mcp.startup-budget}（默认 8 秒）。
 * 这是有意的取舍：宁可启动慢一点，也不要「启动快但工具时有时无」。
 * 不想等就把 {@code agent.mcp.enabled} 关掉，或者把预算调小。
 */
@Configuration
public class McpConfig {

    /**
     * 按配置连接 MCP 服务器并发现工具。
     *
     * <p>失败策略见 {@link McpProperties#failFast()}：默认降级（记 WARN、跳过该服务器），
     * 打开后才会让启动失败。
     */
    @Bean
    public McpToolset mcpToolset(McpProperties properties, ObjectMapper objectMapper) {
        return McpToolset.discover(properties, objectMapper);
    }
}
