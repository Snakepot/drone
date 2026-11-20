package cn.tedu.droneagent.skill;

import cn.tedu.droneagent.tool.Tool;
import cn.tedu.droneagent.tool.ToolDescriptor;
import cn.tedu.droneagent.tool.ToolRegistry;
import cn.tedu.droneagent.tool.ToolResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 启动期工具引用校验：本地工具写错要失败，MCP 工具缺失只告警。
 */
class SkillToolValidatorTest {

    @Test
    @DisplayName("引用不存在的本地工具 → 启动失败")
    void unknownLocalToolFails() {
        SkillCatalog catalog = catalogWithTools("no_such_local");
        ToolRegistry registry = registryOf("search_knowledge");

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new SkillToolValidator(catalog, registry));
        assertTrue(error.getMessage().contains("no_such_local"), error.getMessage());
    }

    @Test
    @DisplayName("引用未发现的 MCP 工具 → 只告警，不阻断")
    void missingMcpToolOnlyWarns() {
        SkillCatalog catalog = catalogWithTools("drone__task_list");

        assertDoesNotThrow(() -> new SkillToolValidator(catalog, registryOf("search_knowledge")));
    }

    @Test
    @DisplayName("引用的工具都存在 → 通过")
    void knownToolsPass() {
        SkillCatalog catalog = catalogWithTools("search_knowledge");

        assertDoesNotThrow(() -> new SkillToolValidator(catalog, registryOf("search_knowledge")));
    }

    private static SkillCatalog catalogWithTools(String tool) {
        String content = "---\nname: s\nversion: 1\ndescription: d\npermissions: [a]\n"
                + "tools: [" + tool + "]\n---\n正文";
        return SkillCatalog.fromContent(Map.of("s/SKILL.md", content), Map.of(), 8000);
    }

    private static ToolRegistry registryOf(String toolName) {
        Tool tool = new Tool() {
            @Override
            public String name() {
                return toolName;
            }

            @Override
            public ToolResult invoke(String argumentsJson) {
                return ToolResult.ok("ok");
            }
        };
        return new ToolRegistry(List.of(tool),
                List.of(new ToolDescriptor(toolName, "测试", "{}", true, true)),
                new ObjectMapper());
    }
}
