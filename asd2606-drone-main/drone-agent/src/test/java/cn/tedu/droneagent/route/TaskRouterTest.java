package cn.tedu.droneagent.route;

import cn.tedu.droneagent.plan.Plan;
import cn.tedu.droneagent.plan.PlanCatalog;
import cn.tedu.droneagent.plan.PlanStep;
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

class TaskRouterTest {

    private final TaskRouter router = new TaskRouter(catalog(), true);

    @Test
    @DisplayName("复杂只读对比 → SubAgent；简单两周对比 → ReAct")
    void routesComplexReadonlyToSubAgent() {
        assertEquals(TaskRoute.SUBAGENT, router.route(
                "对比本周和上周的任务完成率和告警数量，给 3 条建议").route());
        assertEquals(TaskRoute.REACT, router.route("对比本周和上周的运营").route());
        assertEquals(TaskRoute.SUBAGENT, router.route(
                "分析近30天所有告警，逐条看任务和轨迹").route());
    }

    @Test
    @DisplayName("内置日报 → Plan；日报模板怎么改 → ReAct")
    void routesBuiltinAndKnowledge() {
        assertEquals(TaskRoute.PLAN, router.route("出个今天的运营日报").route());
        assertEquals(TaskRoute.REACT, router.route("日报模板怎么改").route());
    }

    @Test
    @DisplayName("显式指令优先：先给方案 / 用子代理 / 直接查")
    void explicitIntentWins() {
        assertEquals(TaskRoute.PLAN, router.route("先给方案：对比本周和上周运营").route());
        assertEquals(TaskRoute.SUBAGENT, router.route("用子代理分析本周运营").route());
        assertEquals(TaskRoute.REACT, router.route("直接查一下本周运营").route());
    }

    @Test
    @DisplayName("写操作：多步/批量 → Plan；单步 → ReAct")
    void routesWrites() {
        assertEquals(TaskRoute.PLAN, router.route("创建一个用户再给他配设备").route());
        assertEquals(TaskRoute.REACT, router.route("帮我派发任务123").route());
        assertEquals(TaskRoute.PLAN, router.route("把今天所有 critical 告警处理掉").route());
    }

    @Test
    @DisplayName("简单查询/知识问答 → ReAct")
    void routesSimpleToReact() {
        assertEquals(TaskRoute.REACT, router.route("今天有多少设备").route());
        assertEquals(TaskRoute.REACT, router.route("设备详情在哪里看").route());
    }

    private static PlanCatalog catalog() {
        Tool tool = new Tool() {
            @Override
            public String name() {
                return "demo";
            }

            @Override
            public ToolResult invoke(String argumentsJson) {
                return ToolResult.ok("{}");
            }
        };
        ToolRegistry registry = new ToolRegistry(List.of(tool),
                List.of(new ToolDescriptor("demo", "demo", "{}", true, true)),
                new ObjectMapper());
        Plan plan = new Plan("builtin-daily", "daily-operations-report", Plan.Origin.BUILTIN,
                1, "日报", null, List.of("日报"), List.of(
                new PlanStep("s1", "总览", PlanStep.Kind.TOOL, "demo", Map.of(),
                        List.of(), null, null)));
        return PlanCatalog.fromPlans(List.of(plan), registry, SkillCatalog.empty(),
                new ObjectMapper());
    }
}
