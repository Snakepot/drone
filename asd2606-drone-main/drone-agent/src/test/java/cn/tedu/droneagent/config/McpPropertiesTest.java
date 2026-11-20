package cn.tedu.droneagent.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link McpProperties} 的绑定测试。
 *
 * <p>与 {@code ToolPropertiesTest} 同理：record + 构造绑定，键名写错时**不会报错、只会绑成默认值**。
 * 这里断言 yml 里的实际取值，顺带验证测试环境把总开关关掉了（否则单测会去连高德）。
 */
@SpringBootTest
class McpPropertiesTest {

    @Autowired
    private McpProperties mcp;

    @Test
    @DisplayName("agent.mcp.* 能绑定；测试环境总开关为关")
    void bindsAllKeys() {
        // src/test/resources/application.properties 把它关掉了——单测不许联网
        assertFalse(mcp.enabled(), "测试环境必须关闭 MCP，否则会去连真实服务器");

        assertFalse(mcp.failFast(), "默认降级：发现失败不阻断启动");
        assertEquals(Duration.ofSeconds(8), mcp.startupBudget());
        assertEquals(3, mcp.discoveryAttempts(), "发现阶段有界重试，容忍 MCP 服务晚就绪");
        assertEquals(Duration.ofSeconds(2), mcp.discoveryRetryDelay());
        assertEquals(2, mcp.servers().size(), "高德 + drone-mcp-server");

        McpProperties.Server amap = server("amap");
        assertEquals("amap", amap.alias());
        assertFalse(amap.forwardAuthorization(), "第三方服务不转发用户 Token");
        assertTrue(amap.enabled());
        assertEquals(Duration.ofSeconds(5), amap.connectTimeout());
        assertEquals(Duration.ofSeconds(8), amap.requestTimeout());
        assertTrue(amap.parallelSafe());
        assertTrue(amap.include().isEmpty(), "白名单留空 = 暴露高德全部工具（需求方 2026-10-21 确认）");
        assertTrue(amap.exclude().isEmpty());
        assertTrue(amap.url().startsWith("https://mcp.amap.com/mcp"));

        McpProperties.Server drone = server("drone");
        assertTrue(drone.forwardAuthorization(), "平台业务工具必须以用户身份调用");
        assertFalse(drone.parallelSafe(), "含写操作，不允许并发");
        assertEquals(Duration.ofSeconds(12), drone.requestTimeout());
        assertEquals("http://127.0.0.1:8082/mcp", drone.url());
    }

    private McpProperties.Server server(String alias) {
        return mcp.servers().stream()
                .filter(server -> alias.equals(server.alias()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("缺少服务器：" + alias));
    }

    @Test
    @DisplayName("没有配密钥的服务器应被判为不可用（跳过而不是启动失败）")
    void serverWithoutKeyIsNotUsable() {
        McpProperties.Server noKey = new McpProperties.Server(
                "amap", "https://mcp.amap.com/mcp?key=", true,
                Duration.ofSeconds(5), Duration.ofSeconds(8), null, null, null, true, false);
        assertFalse(noKey.usable());

        McpProperties.Server withKey = new McpProperties.Server(
                "amap", "https://mcp.amap.com/mcp?key=abc", true,
                Duration.ofSeconds(5), Duration.ofSeconds(8), null, null, null, true, false);
        assertTrue(withKey.usable());

        McpProperties.Server disabled = new McpProperties.Server(
                "amap", "https://mcp.amap.com/mcp?key=abc", false,
                Duration.ofSeconds(5), Duration.ofSeconds(8), null, null, null, true, false);
        assertFalse(disabled.usable());
    }
}
