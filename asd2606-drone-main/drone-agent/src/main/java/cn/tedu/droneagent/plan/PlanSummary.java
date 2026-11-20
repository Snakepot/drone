package cn.tedu.droneagent.plan;

import java.util.List;

/** 给前端/接口看的计划摘要：不包含参数与工具结果。 */
public record PlanSummary(String planId, String name, String origin, String status,
                          List<Step> steps) {

    public PlanSummary {
        steps = steps == null ? List.of() : List.copyOf(steps);
    }

    public record Step(String id, String title, String status, String error) {
    }
}
