package cn.tedu.droneagent.plan;

import cn.tedu.droneagent.skill.SkillCatalog;
import cn.tedu.droneagent.tool.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 内置计划目录：启动时加载 `classpath*:plans/*.json`。
 *
 * <p>与 SkillCatalog 一样，坏计划**直接让启动失败**：计划是“强流程资产”，
 * 工具名写错、依赖不存在、出现环，都会在执行时变成不可解释的半成品。
 */
@Slf4j
public final class PlanCatalog {

    private final List<Plan> plans;
    private final Map<String, Plan> byName;
    private final ToolRegistry registry;
    private final SkillCatalog skills;
    private final ObjectMapper mapper;

    private PlanCatalog(List<Plan> plans, ToolRegistry registry, SkillCatalog skills,
                        ObjectMapper mapper) {
        this.plans = List.copyOf(plans);
        Map<String, Plan> map = new LinkedHashMap<>();
        for (Plan plan : plans) {
            map.put(plan.name(), plan);
        }
        this.byName = Map.copyOf(map);
        this.registry = registry;
        this.skills = skills;
        this.mapper = mapper;
    }

    public static PlanCatalog empty() {
        return new PlanCatalog(List.of(), null, null, null);
    }

    public static PlanCatalog load(String locationPattern, ToolRegistry registry,
                                   SkillCatalog skills, ObjectMapper mapper) {
        List<Plan> loaded = new ArrayList<>();
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver()
                    .getResources(locationPattern);
            for (Resource resource : resources) {
                if (!resource.isReadable() || resource.getFilename() == null
                        || !resource.getFilename().endsWith(".json")) {
                    continue;
                }
                String text = resource.getContentAsString(StandardCharsets.UTF_8);
                Plan plan = mapper.readValue(text, Plan.class);
                loaded.add(plan);
            }
        } catch (Exception e) {
            throw new IllegalStateException("加载内置计划失败：" + e.getMessage(), e);
        }

        PlanCatalog draft = new PlanCatalog(loaded, registry, skills, mapper);
        List<Plan> normalized = new ArrayList<>();
        for (Plan plan : draft.plans) {
            normalized.add(draft.validate(plan, Plan.Origin.BUILTIN));
        }
        normalized.sort(Comparator.comparing(Plan::name));
        log.info("【计划】加载完成：{} 个（{}）", normalized.size(),
                normalized.stream().map(Plan::name).toList());
        return new PlanCatalog(normalized, registry, skills, mapper);
    }

    /** 程序化构建目录（测试与动态计划用）；同样做校验。 */
    public static PlanCatalog fromPlans(List<Plan> plans, ToolRegistry registry, SkillCatalog skills,
                                        ObjectMapper mapper) {
        PlanCatalog draft = new PlanCatalog(plans, registry, skills, mapper);
        List<Plan> normalized = new ArrayList<>();
        for (Plan plan : draft.plans) {
            normalized.add(draft.validate(plan, Plan.Origin.BUILTIN));
        }
        return new PlanCatalog(normalized, registry, skills, mapper);
    }

    public List<Plan> all() {
        return plans;
    }

    public Optional<Plan> byName(String name) {
        return Optional.ofNullable(byName.get(name));
    }

    /** 按触发词匹配内置计划；消息包含任一触发词即命中（先到先得，按名字排序）。 */
    public Optional<Plan> match(String message) {
        if (message == null || message.isBlank()) {
            return Optional.empty();
        }
        String normalized = message.toLowerCase(Locale.ROOT);
        for (Plan plan : plans) {
            for (String trigger : plan.triggers()) {
                if (trigger != null && !trigger.isBlank()
                        && normalized.contains(trigger.toLowerCase(Locale.ROOT))) {
                    return Optional.of(plan);
                }
            }
        }
        return Optional.empty();
    }

    /**
     * 校验一份计划（内置加载与生成计划共用）。
     *
     * @throws IllegalArgumentException 计划不合法
     */
    public Plan validate(Plan plan, Plan.Origin origin) {
        if (plan == null) {
            throw new IllegalArgumentException("计划为空");
        }
        String name = plan.name();
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("计划缺少 name");
        }
        if (plan.steps().isEmpty()) {
            throw new IllegalArgumentException("计划「" + name + "」没有步骤");
        }
        if (plan.steps().size() > 12) {
            throw new IllegalArgumentException("计划「" + name + "」步骤过多（"
                    + plan.steps().size() + "），上限 12");
        }
        Set<String> ids = new HashSet<>();
        for (PlanStep step : plan.steps()) {
            if (step.id() == null || step.id().isBlank()) {
                throw new IllegalArgumentException("计划「" + name + "」有步骤缺少 id");
            }
            if (!ids.add(step.id())) {
                throw new IllegalArgumentException("计划「" + name + "」步骤 id 重复：" + step.id());
            }
            if (step.kind() == null) {
                throw new IllegalArgumentException("步骤「" + step.id() + "」缺少 kind");
            }
            if (step.toolStep()) {
                if (step.tool() == null || step.tool().isBlank()) {
                    throw new IllegalArgumentException("步骤「" + step.id() + "」缺少 tool");
                }
                if (origin == Plan.Origin.GENERATED
                        && registry != null && registry.handler(step.tool()) == null) {
                    throw new IllegalArgumentException("步骤「" + step.id() + "」引用了未注册工具："
                            + step.tool());
                }
                if (origin == Plan.Origin.BUILTIN
                        && registry != null && registry.handler(step.tool()) == null) {
                    // 内置计划允许“工具暂时不可用”（例如测试环境关了 MCP）：
                    // 不阻止服务启动，执行到该步会明确失败。
                    log.warn("【计划】内置计划「{}」的步骤「{}」引用了当前未注册工具：{}",
                            name, step.id(), step.tool());
                }
            }
            if (step.synthesizeStep()
                    && step.skill() != null && !step.skill().isBlank()
                    && skills != null && skills.findAny(step.skill()).isEmpty()) {
                throw new IllegalArgumentException("步骤「" + step.id() + "」引用了不存在的 Skill："
                        + step.skill());
            }
        }
        for (PlanStep step : plan.steps()) {
            for (String dep : step.dependsOn()) {
                if (!ids.contains(dep)) {
                    throw new IllegalArgumentException("步骤「" + step.id()
                            + "」依赖不存在的步骤：" + dep);
                }
                if (dep.equals(step.id())) {
                    throw new IllegalArgumentException("步骤「" + step.id() + "」不能依赖自己");
                }
            }
        }
        detectCycle(name, plan);
        return new Plan(
                plan.planId() == null || plan.planId().isBlank()
                        ? (origin == Plan.Origin.BUILTIN ? "builtin-" + name : plan.planId())
                        : plan.planId(),
                name,
                origin == null ? Plan.Origin.BUILTIN : origin,
                plan.version() <= 0 ? 1 : plan.version(),
                plan.goal(),
                plan.sourceMessage(),
                plan.triggers(),
                plan.steps());
    }

    private static void detectCycle(String name, Plan plan) {
        Map<String, Integer> mark = new LinkedHashMap<>();
        for (PlanStep step : plan.steps()) {
            dfs(plan, step.id(), mark, name);
        }
    }

    private static void dfs(Plan plan, String id, Map<String, Integer> mark, String planName) {
        Integer state = mark.get(id);
        if (state != null) {
            if (state == 1) {
                throw new IllegalArgumentException("计划「" + planName + "」步骤依赖有环：" + id);
            }
            return;
        }
        mark.put(id, 1);
        PlanStep step = plan.step(id);
        if (step != null) {
            for (String dep : step.dependsOn()) {
                dfs(plan, dep, mark, planName);
            }
        }
        mark.put(id, 2);
    }
}
