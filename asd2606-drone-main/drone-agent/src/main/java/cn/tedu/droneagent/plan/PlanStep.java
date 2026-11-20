package cn.tedu.droneagent.plan;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 计划中的一步。
 *
 * <p>`TOOL` 调一个已注册工具；`SYNTHESIZE` 把前序结果交给模型生成最终答复。
 * `args` 里的 `${today.start}`、`${s1}` 等占位符由 {@link PlanExecutor} 解析。
 */
public record PlanStep(
        String id,
        String title,
        Kind kind,
        String tool,
        Map<String, Object> args,
        List<String> dependsOn,
        String skill,
        String prompt
) {

    public enum Kind {
        TOOL,
        SYNTHESIZE
    }

    public PlanStep {
        args = args == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(args));
        dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
    }

    public boolean toolStep() {
        return kind == Kind.TOOL;
    }

    public boolean synthesizeStep() {
        return kind == Kind.SYNTHESIZE;
    }
}
