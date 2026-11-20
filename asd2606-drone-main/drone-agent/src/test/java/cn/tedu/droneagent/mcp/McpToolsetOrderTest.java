package cn.tedu.droneagent.mcp;

import cn.tedu.droneagent.tool.Tool;
import cn.tedu.droneagent.tool.ToolDescriptor;
import cn.tedu.droneagent.tool.ToolResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** MCP 工具注册顺序的确定性（`REQ-AGENT-0022`）：前缀缓存只认逐字节相同的前缀。 */
class McpToolsetOrderTest {

    @Test
    @DisplayName("远程工具按名字排序，行为与元数据同步重排")
    void sortsToolsAndDescriptorsTogether() {
        Tool zebra = stub("zebra");
        Tool alpha = stub("alpha");
        ToolDescriptor zebraDesc = new ToolDescriptor("zebra", "z", "{}", true, true);
        ToolDescriptor alphaDesc = new ToolDescriptor("alpha", "a", "{}", true, true);

        McpToolset sorted = McpToolset.sortedByName(
                List.of(zebra, alpha), List.of(zebraDesc, alphaDesc));

        assertEquals(List.of("alpha", "zebra"),
                sorted.descriptors().stream().map(ToolDescriptor::name).toList());
        assertEquals(List.of("alpha", "zebra"),
                sorted.tools().stream().map(Tool::name).toList(),
                "行为必须跟着元数据一起重排，否则适配器会指错描述");
    }

    private static Tool stub(String name) {
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
}
