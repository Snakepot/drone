package cn.tedu.droneagent.tool;

import cn.tedu.droneagent.config.ToolVisibilityRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用**仓库里真实的 `application.yml`** 验证规则，而不是在测试里另抄一份。
 *
 * <p>抄一份的话，配置改坏了测试仍然是绿的——这组用例就没意义了。
 * 这里直接解析 classpath 下的 application.yml，拿 `agent.tool.visible-by` 建匹配器，
 * 再按真实角色权限判断可见性。
 */
class ToolVisibilityConfigTest {

    /** pilot：device:list/detail + task:execute（见 drone-server data.sql） */
    private static final Set<String> PILOT_PERMISSIONS =
            Set.of("device:list", "device:detail", "task:execute");

    /** screen：只读的 6 个 list 权限 */
    private static final Set<String> SCREEN_PERMISSIONS =
            Set.of("task:list", "device:list", "alert:list", "staff:list", "route:list", "zone:list");

    /** maintainer：设备全权 + 任务/告警/点位/禁飞区（含编辑）+ 员工查看 */
    private static final Set<String> MAINTAINER_PERMISSIONS = Set.of(
            "staff:list",
            "device:list", "device:detail", "device:add", "device:edit",
            "task:list", "alert:list", "alert:handle",
            "route:list", "route:edit", "zone:list", "zone:edit");

    @Test
    @DisplayName("飞手：看不到员工 / 系统 / 告警 / 统计；能看到设备与任务执行")
    void pilotSeesOnlyOwnScope() {
        ToolVisibility visibility = fromApplicationYml();

        assertTrue(visibility.allows("drone__device_list", PILOT_PERMISSIONS));
        assertTrue(visibility.allows("drone__task_my", PILOT_PERMISSIONS));
        assertTrue(visibility.allows("drone__task_start", PILOT_PERMISSIONS));
        assertTrue(visibility.allows("drone__user_me", PILOT_PERMISSIONS), "自己的信息谁都能看");

        assertFalse(visibility.allows("drone__user_list", PILOT_PERMISSIONS));
        assertFalse(visibility.allows("drone__system_role_list", PILOT_PERMISSIONS));
        assertFalse(visibility.allows("drone__alert_list", PILOT_PERMISSIONS));
        assertFalse(visibility.allows("drone__analytics_overview", PILOT_PERMISSIONS));
        assertFalse(visibility.allows("drone__task_create", PILOT_PERMISSIONS));
        assertFalse(visibility.allows("drone__task_dispatch", PILOT_PERMISSIONS));
    }

    @Test
    @DisplayName("大屏：读工具可见，写工具一个都看不到")
    void screenSeesReadOnly() {
        ToolVisibility visibility = fromApplicationYml();

        assertTrue(visibility.allows("drone__task_list", SCREEN_PERMISSIONS));
        assertTrue(visibility.allows("drone__dashboard_summary", SCREEN_PERMISSIONS));
        assertTrue(visibility.allows("drone__zone_list", SCREEN_PERMISSIONS));

        assertFalse(visibility.allows("drone__task_create", SCREEN_PERMISSIONS));
        assertFalse(visibility.allows("drone__task_start", SCREEN_PERMISSIONS));
        assertFalse(visibility.allows("drone__device_add", SCREEN_PERMISSIONS));
        assertFalse(visibility.allows("drone__point_delete", SCREEN_PERMISSIONS));
        assertFalse(visibility.allows("drone__zone_delete", SCREEN_PERMISSIONS), "读权限不能带出删除工具");
        assertFalse(visibility.allows("drone__alert_handle", SCREEN_PERMISSIONS));
    }

    @Test
    @DisplayName("机务：设备/点位/禁飞区可编辑；员工与系统管理仍看不到")
    void maintainerSeesDeviceAndRoute() {
        ToolVisibility visibility = fromApplicationYml();

        assertTrue(visibility.allows("drone__device_add", MAINTAINER_PERMISSIONS));
        assertTrue(visibility.allows("drone__point_update", MAINTAINER_PERMISSIONS));
        assertTrue(visibility.allows("drone__zone_update", MAINTAINER_PERMISSIONS));
        assertTrue(visibility.allows("drone__user_list", MAINTAINER_PERMISSIONS));

        assertFalse(visibility.allows("drone__user_add", MAINTAINER_PERMISSIONS));
        assertFalse(visibility.allows("drone__system_role_save", MAINTAINER_PERMISSIONS));
        assertFalse(visibility.allows("drone__task_create", MAINTAINER_PERMISSIONS));
    }

    @Test
    @DisplayName("没配规则的工具（地图 / 知识检索 / Skill）对所有角色可见")
    void unmatchedToolsStayVisible() {
        ToolVisibility visibility = fromApplicationYml();

        for (Set<String> permissions : List.of(Set.<String>of(), PILOT_PERMISSIONS, SCREEN_PERMISSIONS)) {
            assertTrue(visibility.allows("amap__maps_weather", permissions));
            assertTrue(visibility.allows("search_knowledge", permissions));
            assertTrue(visibility.allows("use_skill", permissions));
            assertTrue(visibility.allows("read_skill_resource", permissions));
        }
    }

    /** 解析真实的 application.yml，构造与生产一致的匹配器 */
    @SuppressWarnings("unchecked")
    private static ToolVisibility fromApplicationYml() {
        try (InputStream in = ToolVisibilityConfigTest.class.getResourceAsStream("/application.yml")) {
            Map<String, Object> root = new Yaml().load(in);
            Map<String, Object> agent = (Map<String, Object>) root.get("agent");
            Map<String, Object> tool = (Map<String, Object>) agent.get("tool");
            List<Map<String, Object>> raw = (List<Map<String, Object>>) tool.get("visible-by");

            List<ToolVisibilityRule> rules = new ArrayList<>();
            for (Map<String, Object> item : raw) {
                List<String> anyOf = (List<String>) item.get("any-of");
                rules.add(new ToolVisibilityRule((String) item.get("pattern"), anyOf));
            }
            return new ToolVisibility(rules);
        } catch (Exception e) {
            throw new IllegalStateException("application.yml 里的 visible-by 无法解析", e);
        }
    }
}
