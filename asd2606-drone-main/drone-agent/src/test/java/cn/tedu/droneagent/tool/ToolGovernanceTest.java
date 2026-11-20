package cn.tedu.droneagent.tool;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ToolGovernance} 的单元测试。
 *
 * <p>对应验收条件：`docs/sdd/specs/REQ-AGENT-0006-危险工具治理.md` 的
 * <b>AC-1</b>（角色不在允许名单 → 不可用）与 T-2 的启动期校验。
 */
class ToolGovernanceTest {

    private static ToolDescriptor descriptor(String name) {
        return new ToolDescriptor(name, "描述", "{}", false, false);
    }

    private static final List<ToolDescriptor> CATALOG =
            List.of(descriptor("demo_tool"), descriptor("delete_device"));

    @Test
    @DisplayName("T-2 清单里出现 catalog 没有的工具名 → 启动失败")
    void rejectsUnknownToolInDangerous() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new ToolGovernance(CATALOG, List.of("delete_pilot"), Map.of()));
        assertTrue(e.getMessage().contains("delete_pilot"));
    }

    @Test
    @DisplayName("T-2 role-tools 里出现 catalog 没有的工具名 → 启动失败")
    void rejectsUnknownToolInRoleTools() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new ToolGovernance(CATALOG, List.of(),
                        Map.of("admin", List.of("delete_pilot"))));
        assertTrue(e.getMessage().contains("delete_pilot"));
    }

    @Test
    @DisplayName("AC-1 角色不在允许名单 → 不授权")
    void deniesRoleNotInAllowList() {
        ToolGovernance governance = new ToolGovernance(CATALOG,
                List.of("delete_device"), Map.of("admin", List.of("delete_device")));

        assertTrue(governance.allows("delete_device", Set.of("admin")));
        assertFalse(governance.allows("delete_device", Set.of("pilot")), "飞手不该能删设备");
        assertFalse(governance.allows("delete_device", Set.of()), "没有角色也不该放行");
    }

    @Test
    @DisplayName("危险工具没配角色 → fail-closed，谁都不许用")
    void dangerousWithoutRolesIsClosed() {
        ToolGovernance governance = new ToolGovernance(CATALOG, List.of("delete_device"), Map.of());

        assertFalse(governance.allows("delete_device", Set.of("admin")));
        assertFalse(governance.allows("delete_device", Set.of("pilot")));
    }

    @Test
    @DisplayName("只读工具没配角色 → 默认放行（否则每加一个都要给所有角色登记）")
    void readonlyWithoutRolesIsOpen() {
        ToolGovernance governance = new ToolGovernance(CATALOG, List.of("delete_device"),
                Map.of("admin", List.of("delete_device")));

        assertTrue(governance.allows("demo_tool", Set.of("pilot")));
        assertTrue(governance.allows("demo_tool", Set.of()));
        assertTrue(governance.isDangerous("delete_device"));
        assertFalse(governance.isDangerous("demo_tool"));
    }
}
