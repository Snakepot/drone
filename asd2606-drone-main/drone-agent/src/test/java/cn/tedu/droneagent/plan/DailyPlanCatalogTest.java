package cn.tedu.droneagent.plan;

import cn.tedu.droneagent.skill.SkillCatalog;
import cn.tedu.droneagent.tool.Tool;
import cn.tedu.droneagent.tool.ToolDescriptor;
import cn.tedu.droneagent.tool.ToolRegistry;
import cn.tedu.droneagent.tool.ToolResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 真实资源校验：日报 JSON 能加载、能按触发词命中、步骤与 Skill 对齐。 */
class DailyPlanCatalogTest {

    @Test
    @DisplayName("资源文件：日报内置计划可加载并命中")
    void loadsDailyReportPlan() {
        ToolRegistry registry = registry();
        SkillCatalog skills = SkillCatalog.load("classpath*:skills/**/SKILL.md", 20000);

        PlanCatalog catalog = PlanCatalog.load("classpath*:plans/*.json", registry, skills,
                new ObjectMapper());

        Plan plan = catalog.match("帮我出个今天的运营日报").orElseThrow();
        assertEquals("daily-operations-report", plan.name());
        assertEquals(6, plan.steps().size());
        assertNotNull(plan.step("s6"));
        assertEquals("daily-operations-report", plan.step("s6").skill());
        assertTrue(plan.steps().stream()
                .filter(PlanStep::toolStep)
                .allMatch(step -> step.tool().startsWith("drone__")));
    }

    private static ToolRegistry registry() {
        String[] names = {"drone__dashboard_summary", "drone__analytics_overview",
                "drone__analytics_dispatch_block", "drone__task_list", "drone__alert_list"};
        List<Tool> tools = new ArrayList<>();
        List<ToolDescriptor> descriptors = new ArrayList<>();
        for (String name : names) {
            tools.add(new Tool() {
                @Override
                public String name() {
                    return name;
                }

                @Override
                public ToolResult invoke(String argumentsJson) {
                    return ToolResult.ok("{}");
                }
            });
            descriptors.add(new ToolDescriptor(name, name, "{}", true, true));
        }
        return new ToolRegistry(tools, descriptors, new ObjectMapper());
    }
}
