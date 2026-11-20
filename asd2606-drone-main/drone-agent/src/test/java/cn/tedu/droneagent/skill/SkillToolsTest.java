package cn.tedu.droneagent.skill;

import cn.tedu.droneagent.tool.ToolContext;
import cn.tedu.droneagent.tool.ToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 两个 Skill 工具的权限与路径安全测试。
 */
class SkillToolsTest {

    private static final Set<String> ADMIN = Set.of("task:list", "alert:list");
    private static final Set<String> PILOT = Set.of("task:execute");

    private SkillCatalog catalog;

    @BeforeEach
    void setUp() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("daily/SKILL.md", content("daily", "task:list, alert:list", "第一步：查总览"));
        files.put("handover/SKILL.md", content("handover", "task:execute", "第一步：查我的任务"));
        Map<String, String> resources = Map.of(
                "daily/references/metrics.md", "完成率 = finished/(finished+failed)",
                "daily/assets/report.html", "<html>report</html>");
        catalog = SkillCatalog.fromContent(files, resources, 8000);
    }

    // ---------- use_skill ----------

    @Test
    @DisplayName("有权限：返回正文与参考资料清单")
    void useSkillReturnsBody() {
        ToolResult result = new UseSkillTool(catalog).invoke(
                "{\"name\":\"daily\"}", context(ADMIN));

        assertTrue(result.ok());
        assertTrue(result.content().contains("第一步：查总览"));
        assertTrue(result.content().contains("references/metrics.md"));
        assertTrue(result.content().contains("必须按顺序执行"));
    }

    @Test
    @DisplayName("无权限与不存在：返回同一句文案（不暴露存在性）")
    void unauthorizedAndUnknownLookTheSame() {
        UseSkillTool tool = new UseSkillTool(catalog);

        ToolResult unauthorized = tool.invoke("{\"name\":\"daily\"}", context(PILOT));
        ToolResult unknown = tool.invoke("{\"name\":\"no_such\"}", context(PILOT));

        assertFalse(unauthorized.ok());
        assertFalse(unknown.ok());
        assertEquals(unknown.content(), unauthorized.content());
        assertTrue(unauthorized.content().contains("没有找到该流程"));
        assertFalse(unauthorized.content().contains("daily"), "文案里不应出现流程名");
    }

    // ---------- read_skill_resource ----------

    @Test
    @DisplayName("允许读取 references/ 下的文本")
    void readsReference() {
        ToolResult result = new ReadSkillResourceTool(catalog, 4096).invoke(
                "{\"skill\":\"daily\",\"path\":\"references/metrics.md\"}", context(ADMIN));

        assertTrue(result.ok());
        assertTrue(result.content().contains("finished+failed"));
    }

    @Test
    @DisplayName("assets/ 下的模板也能读（L3 两种目录）")
    void readsAsset() {
        ToolResult result = new ReadSkillResourceTool(catalog, 4096).invoke(
                "{\"skill\":\"daily\",\"path\":\"assets/report.html\"}", context(ADMIN));

        assertTrue(result.ok());
        assertTrue(result.content().contains("report"));
    }

    @Test
    @DisplayName("拒绝目录穿越、非 references/assets 路径与非白名单扩展名")
    void rejectsUnsafePaths() {
        ReadSkillResourceTool tool = new ReadSkillResourceTool(catalog, 4096);

        assertFalse(tool.invoke("{\"skill\":\"daily\",\"path\":\"references/../SKILL.md\"}",
                context(ADMIN)).ok());
        assertFalse(tool.invoke("{\"skill\":\"daily\",\"path\":\"SKILL.md\"}",
                context(ADMIN)).ok());
        assertFalse(tool.invoke("{\"skill\":\"daily\",\"path\":\"references/x.exe\"}",
                context(ADMIN)).ok());
        assertFalse(tool.invoke("{\"skill\":\"daily\",\"path\":\"/etc/passwd\"}",
                context(ADMIN)).ok());
    }

    @Test
    @DisplayName("无权用户读资料：与不存在同一句")
    void resourceRespectsPermissions() {
        ToolResult result = new ReadSkillResourceTool(catalog, 4096).invoke(
                "{\"skill\":\"daily\",\"path\":\"references/metrics.md\"}", context(PILOT));

        assertFalse(result.ok());
        assertTrue(result.content().contains("没有找到该流程"));
    }

    private static ToolContext context(Set<String> permissions) {
        return new ToolContext(Set.of(), permissions, null);
    }

    private static String content(String name, String permissions, String body) {
        return "---\nname: " + name + "\nversion: 1\ndescription: d\npermissions: [" + permissions
                + "]\ntools: []\n---\n" + body;
    }
}
