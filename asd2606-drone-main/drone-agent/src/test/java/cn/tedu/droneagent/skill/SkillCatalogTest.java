package cn.tedu.droneagent.skill;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Skill 目录的解析、校验与 L1 过滤测试。
 *
 * <p>重点不是「能读文件」，而是**坏文件必须让启动失败**、**L1 必须按权限裁剪**。
 */
class SkillCatalogTest {

    @Test
    @DisplayName("两条流程：L1 只含元数据；按权限过滤（全部具备才可见）")
    void loadsAndFiltersByPermissions() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("daily/SKILL.md", skill("daily", "task:list, alert:list", "日报步骤"));
        files.put("handover/SKILL.md", skill("handover", "task:execute", "交接步骤"));

        SkillCatalog catalog = SkillCatalog.fromContent(files, Map.of(), 8000);

        assertEquals(2, catalog.all().size());

        Set<String> admin = Set.of("task:list", "alert:list", "task:execute");
        assertEquals(2, catalog.visibleTo(admin).size());

        Set<String> onlyTaskList = Set.of("task:list");
        assertTrue(catalog.visibleTo(onlyTaskList).isEmpty(), "日报需要两个权限，只有其一不可见");
        assertFalse(catalog.findVisible("daily", onlyTaskList).isPresent());

        Set<String> pilot = Set.of("task:execute");
        assertEquals(List.of("handover"),
                catalog.visibleTo(pilot).stream().map(SkillDefinition::name).toList());

        String pilotL1 = catalog.l1Text(pilot);
        assertTrue(pilotL1.contains("handover"));
        assertFalse(pilotL1.contains("daily"), "无权流程不能出现在 L1");
    }

    @Test
    @DisplayName("没有任何可见流程时，L1 明确写「暂无」")
    void emptyL1IsExplicit() {
        SkillCatalog catalog = SkillCatalog.fromContent(Map.of(), Map.of(), 8000);

        assertTrue(catalog.l1Text(Set.of()).contains("没有可用"));
        assertTrue(catalog.visibleTo(Set.of()).isEmpty());
    }

    @Test
    @DisplayName("下划线开头的目录（模板）不参与加载")
    void skipsTemplateDirectory() {
        Map<String, String> files = Map.of(
                "_template/SKILL.md", skill("x", "a", "模板"),
                "real/SKILL.md", skill("real", "a", "正文"));

        SkillCatalog catalog = SkillCatalog.fromContent(files, Map.of(), 8000);

        assertEquals(1, catalog.all().size());
        assertEquals("real", catalog.all().get(0).name());
    }

    @Test
    @DisplayName("缺 permissions：启动失败（否则谁可见无法判定）")
    void permissionsAreMandatory() {
        String content = "---\nname: x\nversion: 1\ndescription: d\n---\n正文";

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> SkillCatalog.fromContent(Map.of("x/SKILL.md", content), Map.of(), 8000));
        assertTrue(error.getMessage().contains("permissions"), error.getMessage());
    }

    @Test
    @DisplayName("name 必须等于目录名")
    void nameMustMatchDirectory() {
        String content = skill("other", "a", "正文");

        assertThrows(IllegalStateException.class,
                () -> SkillCatalog.fromContent(Map.of("x/SKILL.md", content), Map.of(), 8000));
    }

    @Test
    @DisplayName("正文为空 / 超长都启动失败")
    void bodyMustBeNonEmptyAndBounded() {
        String empty = "---\nname: x\nversion: 1\ndescription: d\npermissions: [a]\n---\n";
        assertThrows(IllegalStateException.class,
                () -> SkillCatalog.fromContent(Map.of("x/SKILL.md", empty), Map.of(), 8000));

        String longBody = "---\nname: x\nversion: 1\ndescription: d\npermissions: [a]\n---\n" + "啊".repeat(50);
        assertThrows(IllegalStateException.class,
                () -> SkillCatalog.fromContent(Map.of("x/SKILL.md", longBody), Map.of(), 10));
    }

    @Test
    @DisplayName("参考资料按目录归属；只允许文本扩展名")
    void pairsResources() {
        Map<String, String> files = Map.of("daily/SKILL.md", skill("daily", "a", "正文"));
        Map<String, String> resources = Map.of(
                "daily/references/metrics.md", "口径",
                "daily/assets/report.html", "<html></html>",
                "daily/references/other.png", "不是文本",
                "other/references/x.md", "别的流程的资料");

        SkillCatalog catalog = SkillCatalog.fromContent(files, resources, 8000);
        SkillDefinition daily = catalog.findAny("daily").orElseThrow();

        assertEquals(Set.of("references/metrics.md", "references/other.png", "assets/report.html"),
                daily.resources().keySet());
        assertTrue(SkillCatalog.isAllowedResource("assets/report.html"));
        assertTrue(SkillCatalog.isAllowedResource("references/metrics.md"));
        assertFalse(SkillCatalog.isAllowedResource("references/other.png"));
    }

    private static String skill(String name, String permissions, String body) {
        return "---\n"
                + "name: " + name + "\n"
                + "version: 1\n"
                + "owner: 测试\n"
                + "description: 测试流程\n"
                + "when_to_use: 测试触发\n"
                + "permissions: [" + permissions + "]\n"
                + "tools: []\n"
                + "---\n"
                + body;
    }
}
