package cn.tedu.droneagent.tool;

import cn.tedu.droneagent.config.ToolVisibilityRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 可见性权限门：先匹配先用、任一权限满足、未匹配默认可见。 */
class ToolVisibilityTest {

    private static ToolVisibility of(ToolVisibilityRule... rules) {
        return new ToolVisibility(List.of(rules));
    }

    @Test
    @DisplayName("没写规则的工具默认可见（地图 / 知识检索 / Skill 元工具）")
    void unmatchedIsVisible() {
        ToolVisibility visibility = of(new ToolVisibilityRule("drone__user_*", List.of("staff:list")));

        assertTrue(visibility.allows("amap__maps_weather", Set.of()));
        assertTrue(visibility.allows("search_knowledge", Set.of()));
    }

    @Test
    @DisplayName("先匹配先用：user_me 排在 user_* 前面，所以谁都能看")
    void firstMatchWins() {
        ToolVisibility visibility = of(
                new ToolVisibilityRule("drone__user_me", List.of()),
                new ToolVisibilityRule("drone__user_*", List.of("staff:list")));

        assertTrue(visibility.allows("drone__user_me", Set.of()));
        assertFalse(visibility.allows("drone__user_list", Set.of()));
        assertTrue(visibility.allows("drone__user_list", Set.of("staff:list")));
    }

    @Test
    @DisplayName("any-of：有其中任意一个权限即可见")
    void anyPermissionIsEnough() {
        ToolVisibility visibility = of(
                new ToolVisibilityRule("drone__task_*", List.of("task:list", "task:execute")));

        assertTrue(visibility.allows("drone__task_list", Set.of("task:list")));
        assertTrue(visibility.allows("drone__task_start", Set.of("task:execute")));
        assertFalse(visibility.allows("drone__task_list", Set.of("device:list")));
    }

    @Test
    @DisplayName("通配匹配整串：task_my* 能命中 task_my_map")
    void wildcardMatchesSuffix() {
        ToolVisibility visibility = of(
                new ToolVisibilityRule("drone__task_my*", List.of("task:execute")));

        assertTrue(visibility.allows("drone__task_my", Set.of("task:execute")));
        assertTrue(visibility.allows("drone__task_my_map", Set.of("task:execute")));
        // 未命中该规则的工具仍默认可见（这里没有别的规则）
        assertTrue(visibility.allows("drone__task_list", Set.of("task:execute")));
        // 命中规则但没权限 → 隐藏，证明通配确实生效
        assertFalse(visibility.allows("drone__task_my_map", Set.of()));
    }

    @Test
    @DisplayName("权限为空集合时，受限工具一律不可见（fail-closed）")
    void emptyPermissionsHideRestrictedTools() {
        ToolVisibility visibility = of(
                new ToolVisibilityRule("drone__device_*", List.of("device:list")));

        assertFalse(visibility.allows("drone__device_list", Set.of()));
        assertFalse(visibility.allows("drone__device_list", null));
    }

    @Test
    @DisplayName("规则没写 pattern 时启动即失败，避免静默全放行")
    void blankPatternFailsFast() {
        assertThrows(IllegalStateException.class,
                () -> new ToolVisibility(List.of(new ToolVisibilityRule(" ", List.of("task:list")))));
    }
}
