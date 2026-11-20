package cn.tedu.droneagent.plan;

import cn.tedu.droneagent.skill.SkillCatalog;
import cn.tedu.droneagent.tool.Tool;
import cn.tedu.droneagent.tool.ToolDescriptor;
import cn.tedu.droneagent.tool.ToolRegistry;
import cn.tedu.droneagent.tool.ToolResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanCatalogTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("内置计划：按触发词命中，planId 自动补全")
    void matchesByTrigger() {
        PlanCatalog catalog = PlanCatalog.fromPlans(List.of(plan("daily", Plan.Origin.BUILTIN,
                List.of("日报", "运营日报"))), registry("a", "b"), SkillCatalog.empty(), mapper);

        Plan matched = catalog.match("帮我出个今天的运营日报").orElseThrow();

        assertEquals("daily", matched.name());
        assertEquals("builtin-daily", matched.planId());
    }

    @Test
    @DisplayName("依赖形成环：加载时拒绝")
    void rejectsCycle() {
        Plan cyclic = new Plan("p", "cyclic", Plan.Origin.BUILTIN, 1, "g", null, List.of(),
                List.of(
                        step("s1", "a", List.of("s2")),
                        step("s2", "b", List.of("s1"))));

        assertThrows(IllegalArgumentException.class, () -> PlanCatalog.fromPlans(
                List.of(cyclic), registry("a", "b"), SkillCatalog.empty(), mapper));
    }

    @Test
    @DisplayName("生成计划：引用未注册工具直接拒绝")
    void generatedPlanRejectsUnknownTool() {
        Plan plan = plan("g", Plan.Origin.GENERATED, List.of());
        Plan unknown = new Plan("g-1", "g", Plan.Origin.GENERATED, 1, "g", "x", List.of(),
                List.of(step("s1", "not-exists", List.of())));
        PlanCatalog catalog = PlanCatalog.fromPlans(List.of(), registry("a", "b"),
                SkillCatalog.empty(), mapper);

        assertThrows(IllegalArgumentException.class,
                () -> catalog.validate(unknown, Plan.Origin.GENERATED));
    }

    @Test
    @DisplayName("内置计划：工具暂时不可用只告警，不阻止启动")
    void builtinPlanToleratesMissingTool() {
        Plan plan = new Plan(null, "missing-tool", Plan.Origin.BUILTIN, 1, "g", null,
                List.of("测试"), List.of(step("s1", "not-exists", List.of())));

        PlanCatalog catalog = PlanCatalog.fromPlans(List.of(plan), registry("a"),
                SkillCatalog.empty(), mapper);

        assertTrue(catalog.byName("missing-tool").isPresent());
    }

    private static Plan plan(String name, Plan.Origin origin, List<String> triggers) {
        return new Plan(null, name, origin, 1, "goal", null, triggers,
                List.of(step("s1", "a", List.of()), step("s2", "b", List.of("s1"))));
    }

    private static PlanStep step(String id, String tool, List<String> dependsOn) {
        return new PlanStep(id, id, PlanStep.Kind.TOOL, tool, Map.of(), dependsOn, null, null);
    }

    private static ToolRegistry registry(String... names) {
        List<Tool> tools = new java.util.ArrayList<>();
        List<ToolDescriptor> descriptors = new java.util.ArrayList<>();
        for (String name : names) {
            tools.add(new Tool() {
                @Override
                public String name() {
                    return name;
                }

                @Override
                public ToolResult invoke(String argumentsJson) {
                    return ToolResult.ok("ok-" + name);
                }
            });
            descriptors.add(new ToolDescriptor(name, name, "{}", true, true));
        }
        return new ToolRegistry(tools, descriptors, new ObjectMapper());
    }
}
