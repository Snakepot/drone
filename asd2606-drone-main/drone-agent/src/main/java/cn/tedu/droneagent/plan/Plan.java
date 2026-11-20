package cn.tedu.droneagent.plan;

import java.util.List;

/**
 * 一份可执行的计划。
 *
 * <p>`BUILTIN` 来自 `resources/plans/*.json`，固定、不审批；
 * `GENERATED` 由 Planner 运行时生成，必须经用户批准。
 */
public record Plan(
        String planId,
        String name,
        Origin origin,
        int version,
        String goal,
        String sourceMessage,
        List<String> triggers,
        List<PlanStep> steps
) {

    public enum Origin {
        BUILTIN,
        GENERATED
    }

    public Plan {
        triggers = triggers == null ? List.of() : List.copyOf(triggers);
        steps = steps == null ? List.of() : List.copyOf(steps);
    }

    public PlanStep step(String id) {
        return steps.stream().filter(step -> step.id().equals(id)).findFirst().orElse(null);
    }

    public Plan withRuntime(String planId, Origin origin) {
        return new Plan(planId, name, origin, version, goal, sourceMessage, triggers, steps);
    }
}
