package cn.tedu.droneagent.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ToolProperties} 的绑定测试。
 *
 * <p>理由同 {@code MemoryPropertiesTest}：按构造方法绑定的 record，**yml 里漏键或拼错键名时
 * 绑定会悄悄失效**，服务照常启动、运行时才露出诡异表现。这里逐个断言取值，
 * 顺带充当「yml 键名写错了」的探测器。
 */
@SpringBootTest
class ToolPropertiesTest {

    @Autowired
    private ToolProperties tool;

    @Test
    @DisplayName("agent.tool.* 全部能绑定，且与 yml 中的默认值一致")
    void bindsAllKeysFromYml() {
        assertTrue(tool.enabled());
        assertEquals(8, tool.maxRounds(), "日报等流程实测需要 7–8 轮工具调用");
        assertTrue(tool.parallelEnabled());
        assertEquals(4, tool.concurrency());
        assertEquals(Duration.ofSeconds(15), tool.invokeTimeout());
        assertTrue(tool.optimizeEnabled());
        assertEquals(2000, tool.maxResultChars());
        assertEquals(6000, tool.maxTotalResultChars());
        assertEquals(1, tool.maxRetries());
        assertEquals(32768, tool.maxSkillResultChars());
        assertEquals(30000, tool.maxTotalSkillChars());
        // 本地工具元数据（catalog）也要能绑定。
        // MCP 远程工具不写在这里，由 McpToolset 启动时发现。
        assertEquals(4, tool.catalog().size(),
                "本地工具：资料检索 + 记忆检索 + 两个 Skill 工具");
        assertEquals("search_knowledge", tool.catalog().get(0).name());
        assertTrue(tool.catalog().get(0).parallelSafe());
        assertTrue(tool.catalog().get(0).parametersJson().contains("\"properties\""));
        assertEquals("search_memory", tool.catalog().get(1).name(), "REQ-AGENT-0021 新增");
        assertEquals("use_skill", tool.catalog().get(2).name());
        assertEquals("read_skill_resource", tool.catalog().get(3).name());
    }

    @Test
    @DisplayName("回归：catalog 的参数 Schema 绑定后 required 必须是**数组**（曾被绑成下标对象 → 模型 400）")
    void catalogSchemaKeepsRequiredAsArray() throws Exception {
        JsonNode schema = new ObjectMapper().readTree(tool.catalog().get(0).parametersJson());

        assertTrue(schema.path("properties").has("query"));
        assertTrue(schema.path("required").isArray(), "required 必须是数组，否则模型回 400 Invalid schema");
        assertEquals("query", schema.path("required").get(0).asText());
    }
}
