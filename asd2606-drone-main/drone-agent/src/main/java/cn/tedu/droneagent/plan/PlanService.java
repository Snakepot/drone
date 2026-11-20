package cn.tedu.droneagent.plan;

import cn.tedu.droneagent.cancel.CancellationSignal;
import cn.tedu.droneagent.memory.SessionKey;
import cn.tedu.droneagent.tool.ToolContext;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * 计划门面（`REQ-AGENT-0031`）：把“匹配 / 生成 / 存储 / 执行”收在一处。
 *
 * <p>内置计划由 {@link PlanCatalog} 提供，不审批；生成计划由 {@link PlanPlanner}
 * 产出、{@link PlanStore} 落盘、用户批准后执行。
 */
@Slf4j
public class PlanService {

    private final PlanCatalog catalog;
    private final PlanStore store;
    private final PlanPlanner planner;
    private final PlanExecutor executor;

    public PlanService(PlanCatalog catalog, PlanStore store, PlanPlanner planner,
                       PlanExecutor executor) {
        this.catalog = catalog;
        this.store = store;
        this.planner = planner;
        this.executor = executor;
    }

    public static PlanService noop() {
        return new PlanService(PlanCatalog.empty(), PlanStore.noop(), null, null);
    }

    public boolean enabled() {
        return executor != null;
    }

    public Optional<Plan> matchBuiltin(String message) {
        return catalog.match(message);
    }

    /** 用户是否明确要求“先给方案/计划”。本期不做自动复杂度判断。 */
    public boolean wantsGeneratedPlan(String message) {
        if (message == null) {
            return false;
        }
        return message.contains("先给方案")
                || message.contains("先给计划")
                || message.contains("先规划")
                || message.startsWith("plan ")
                || message.startsWith("plan：");
    }

    /** 生成并落盘；返回待审批摘要。 */
    public PlanSummary generateAndStore(long userId, SessionKey key, String message,
                                        ToolContext context, String traceId) {
        if (planner == null) {
            throw new IllegalStateException("PlanPlanner 未装配，无法生成计划");
        }
        Plan plan = planner.generate(key, message, context, traceId);
        store.put(userId, plan);
        log.info("【计划】已落盘，等待用户审批：planId={}，userId={}", plan.planId(), userId);
        return summary(plan, "PENDING_APPROVAL");
    }

    public Optional<Plan> take(long userId, String planId) {
        return store.take(userId, planId);
    }

    public PlanExecutor.Result execute(Plan plan, SessionKey key, String message,
                                       ToolContext context, String traceId,
                                       CancellationSignal signal, Consumer<String> status,
                                       PlanToolInvoker invoker) {
        return executor.execute(plan, key, message, context, traceId, signal, status, invoker);
    }

    public static PlanSummary summary(Plan plan, String status) {
        List<PlanSummary.Step> steps = new ArrayList<>();
        for (PlanStep step : plan.steps()) {
            steps.add(new PlanSummary.Step(step.id(), step.title(), "PENDING", null));
        }
        return new PlanSummary(plan.planId(), plan.name(), plan.origin().name(), status, steps);
    }

    /** 被拒绝/过期的计划摘要：步骤全部标 SKIPPED。 */
    public static PlanSummary rejected(Plan plan) {
        List<PlanSummary.Step> steps = new ArrayList<>();
        for (PlanStep step : plan.steps()) {
            steps.add(new PlanSummary.Step(step.id(), step.title(), "SKIPPED", "计划未执行"));
        }
        return new PlanSummary(plan.planId(), plan.name(), plan.origin().name(), "REJECTED", steps);
    }
}
