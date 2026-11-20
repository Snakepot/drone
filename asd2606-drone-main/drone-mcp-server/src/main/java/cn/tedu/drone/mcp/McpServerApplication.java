package cn.tedu.drone.mcp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * drone-mcp-server 启动类。
 *
 * <h2>这个服务只做三件事</h2>
 * <ol>
 *   <li>启动时从 classpath 的 OpenAPI 快照生成工具目录（不联网）；</li>
 *   <li>对外提供 MCP 的 JSON-RPC 端点（`POST /mcp`）；</li>
 *   <li>执行工具时把请求转发给 {@code drone-server} 的公开业务接口，并带上用户的 JWT。</li>
 * </ol>
 *
 * <p>它**不选边**：不查库、不懂业务规则、不做权限判断——权限由 {@code drone-server} 判定。
 */
@SpringBootApplication
@ConfigurationPropertiesScan("cn.tedu.drone.mcp.config")
public class McpServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(McpServerApplication.class, args);
    }
}
