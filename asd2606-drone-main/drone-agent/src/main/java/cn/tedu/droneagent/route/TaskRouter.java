package cn.tedu.droneagent.route;

import cn.tedu.droneagent.plan.PlanCatalog;
import lombok.extern.slf4j.Slf4j;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * 代码级任务路由（`REQ-AGENT-0033`）：根据用户问题选择 ReAct / Plan / SubAgent。
 *
 * <p>用户不需要知道“子代理/委派”这些概念；路由在代码里做，依据是**任务特征**：
 * 是否多数据源、是否要逐条下钻、是否多轮诊断、是否写操作、是否是知识问答。
 *
 * <p>它是**规则路由**，不是模型分类：确定性、可测试、无额外模型调用。
 * 后续若要更细的语义判断，可以替换成轻量分类器，但接口不变。
 */
@Slf4j
public class TaskRouter {

    private static final String DEFAULT_AGENT = "ops-analyst";

    private final PlanCatalog plans;
    private final boolean subAgentAvailable;
    private final boolean enabled;

    public TaskRouter(PlanCatalog plans, boolean subAgentAvailable) {
        this(plans, subAgentAvailable, true);
    }

    private TaskRouter(PlanCatalog plans, boolean subAgentAvailable, boolean enabled) {
        this.plans = plans;
        this.subAgentAvailable = subAgentAvailable;
        this.enabled = enabled;
    }

    public static TaskRouter disabled() {
        return new TaskRouter(null, false, false);
    }

    public boolean enabled() {
        return enabled;
    }

    public RouteDecision route(String message) {
        if (!enabled) {
            return RouteDecision.react("router_disabled", 0, 0, 0);
        }
        if (message == null || message.isBlank()) {
            return RouteDecision.react("empty", 0, 0, 0);
        }
        String text = message.toLowerCase(Locale.ROOT);

        if (containsAny(text, "用子代理", "委派", "让子代理", "子代理分析")) {
            return logDecision(subAgentAvailable
                    ? RouteDecision.subAgent(DEFAULT_AGENT, "explicit_subagent", 0, 99, 0)
                    : RouteDecision.react("subagent_unavailable", 0, 99, 0),
                    message, 0);
        }
        if (containsAny(text, "直接查", "不用子代理", "不要委派", "直接回答")) {
            return logDecision(RouteDecision.react("explicit_direct", 0, 0, 99), message, 0);
        }
        if (containsAny(text, "先给方案", "先给计划", "先规划", "给我方案", "出个方案", "帮我规划")) {
            return logDecision(RouteDecision.plan("explicit_plan", 99, 0, 0), message, 0);
        }

        boolean knowledge = containsAny(text, "怎么", "如何", "是什么", "在哪", "模板",
                "文档", "手册", "说明", "区别");
        Optional<cn.tedu.droneagent.plan.Plan> builtin =
                plans == null ? Optional.empty() : plans.match(text);
        if (builtin.isPresent() && !knowledge) {
            return logDecision(RouteDecision.plan("builtin:" + builtin.get().name(), 5, 0, 0),
                    message, 0);
        }
        if (knowledge) {
            return logDecision(RouteDecision.react("knowledge_query", 0, 0, 5), message, 0);
        }

        int domains = domains(text);
        boolean compare = containsAny(text, "对比", "比较", "环比", "同比", "趋势",
                "变化", "变差", "下降", "上升", "增加", "减少");
        boolean analysis = containsAny(text, "分析", "诊断", "归因", "原因", "汇总",
                "复盘", "排查", "评估", "建议", "为什么");
        boolean drill = containsAny(text, "逐条", "每条", "逐个", "下钻", "明细", "每台",
                "每个", "所有", "全部", "近", "本周", "上周", "最近", "一个月", "季度");
        boolean write = containsAny(text, "创建", "新增", "删除", "修改", "派发", "处置",
                "撤销", "停用", "授权", "导入", "上报", "撤回", "处理掉", "批量处理");
        boolean sequence = containsAny(text, "再", "然后", "接着", "并且", "同时", "之后");
        boolean simple = containsAny(text, "多少", "几个", "在哪", "列表", "详情", "查一下",
                "看一下", "有没有", "谁");

        int plan = 0;
        int sub = 0;
        int react = 0;

        if (compare) {
            sub += 2;
        }
        if (analysis) {
            sub += 2;
        }
        if (domains >= 2) {
            sub += 2;
        }
        if (domains >= 3) {
            sub += 1;
        }
        if (drill) {
            sub += 2;
        }
        if (message.length() >= 60) {
            sub += 1;
        }
        if (write) {
            sub -= 5;
            plan += 2;
        }
        if (write && containsAny(text, "所有", "全部", "批量", "逐条", "每个", "每台")) {
            plan += 2;
        }
        if (sequence) {
            plan += 2;
        }
        if (simple) {
            sub -= 3;
            react += 2;
        }
        if (message.length() < 25) {
            react += 1;
        }

        RouteDecision decision;
        if (subAgentAvailable && sub >= 4 && domains >= 2 && !write) {
            decision = RouteDecision.subAgent(DEFAULT_AGENT, "complex_readonly", plan, sub, react);
        } else if (plan >= 3 && !simple && plan >= react) {
            decision = RouteDecision.plan("multi_step_or_write", plan, sub, react);
        } else {
            decision = RouteDecision.react("simple_or_default", plan, sub, react);
        }
        log.info("【路由】decision={}，reason={}，domains={}，scores(plan/sub/react)={}/{}/{}，消息长度={}",
                decision.route(), decision.reason(), domains, plan, sub, react, message.length());
        return decision;
    }

    private static RouteDecision logDecision(RouteDecision decision, String message, int domains) {
        log.info("【路由】decision={}，reason={}，domains={}，scores(plan/sub/react)={}/{}/{}，消息长度={}",
                decision.route(), decision.reason(), domains, decision.planScore(),
                decision.subAgentScore(), decision.reactScore(), message.length());
        return decision;
    }

    private static int domains(String text) {
        Set<String> hits = new HashSet<>();
        for (String domain : List.of("任务", "告警", "设备", "人员", "飞手", "机务",
                "效率", "积压", "证据", "轨迹", "航线", "禁飞区", "点位", "员工", "大屏")) {
            if (text.contains(domain)) {
                hits.add(domain);
            }
        }
        return hits.size();
    }

    private static boolean containsAny(String text, String... words) {
        for (String word : words) {
            if (text.contains(word)) {
                return true;
            }
        }
        return false;
    }
}
