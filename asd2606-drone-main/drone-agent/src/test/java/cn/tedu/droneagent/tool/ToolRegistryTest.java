package cn.tedu.droneagent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ToolRegistry} 的单元测试。
 *
 * <p>对应验收条件：`docs/sdd/specs/REQ-AGENT-0005-工具框架增强.md` 的
 * <b>AC-1</b>（行为与元数据对不上 / 重名 → 启动失败）、<b>AC-6</b>（加工具=行为+配置）、
 * <b>AC-7</b>（描述与参数取自配置，而非代码）。
 *
 * <p>不依赖 Spring、不联网：直接手工拼「行为列表 + 配置列表」构造注册处。
 */
class ToolRegistryTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static ToolDescriptor descriptor(String name, String description) {
        return new ToolDescriptor(name, description,
                "{\"type\":\"object\",\"properties\":{\"place\":{\"type\":\"string\"}},\"required\":[\"place\"]}", true, true);
    }

    /** 一个只有名字与「空行为」的桩工具 */
    private static Tool handlerOf(String name) {
        return new Tool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public ToolResult invoke(String argumentsJson) {
                return ToolResult.ok("ok");
            }
        };
    }

    @Test
    @DisplayName("AC-7 定义来自配置：description / 参数取自 descriptor，而不是代码")
    void definitionsComeFromConfig() {
        ToolRegistry registry = new ToolRegistry(
                List.of(handlerOf("demo_tool")),
                List.of(descriptor("demo_tool", "配置里的描述")),
                MAPPER);

        List<ToolDefinition> definitions = registry.definitions();
        assertEquals(1, definitions.size());
        assertEquals("demo_tool", definitions.get(0).name());
        assertEquals("配置里的描述", definitions.get(0).description());
        assertTrue(definitions.get(0).parametersJson().contains("\"place\""));
    }

    @Test
    @DisplayName("AC-1 有行为、没元数据 → 启动失败")
    void handlerWithoutDescriptorFails() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new ToolRegistry(List.of(handlerOf("a")), List.of(), MAPPER));
        assertTrue(e.getMessage().contains("没有它的元数据"));
    }

    @Test
    @DisplayName("AC-1 有元数据、没行为 → 启动失败")
    void descriptorWithoutHandlerFails() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new ToolRegistry(List.of(), List.of(descriptor("a", "d")), MAPPER));
        assertTrue(e.getMessage().contains("没有对应的行为"));
    }

    @Test
    @DisplayName("AC-1 行为重名 → 启动失败")
    void duplicateHandlerNameFails() {
        assertThrows(IllegalStateException.class,
                () -> new ToolRegistry(List.of(handlerOf("a"), handlerOf("a")),
                        List.of(descriptor("a", "d")), MAPPER));
    }

    @Test
    @DisplayName("AC-1 配置重名 → 启动失败")
    void duplicateDescriptorNameFails() {
        assertThrows(IllegalStateException.class,
                () -> new ToolRegistry(List.of(handlerOf("a")),
                        List.of(descriptor("a", "d"), descriptor("a", "d2")), MAPPER));
    }

    @Test
    @DisplayName("AC-6 加工具：一个行为 + 一条配置即可注册，且能按名字取到")
    void pairsHandlerAndDescriptor() {
        ToolRegistry registry = new ToolRegistry(
                List.of(handlerOf("demo_tool"), handlerOf("other_tool")),
                List.of(descriptor("demo_tool", "天气"), descriptor("other_tool", "别的")),
                MAPPER);

        assertEquals(2, registry.definitions().size());
        assertNotNull(registry.handler("other_tool"));
        assertNull(registry.handler("不存在的工具"));
        assertTrue(registry.isParallelSafe("other_tool"));
    }
}
