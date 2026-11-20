package cn.tedu.droneagent.plan;

import cn.tedu.droneagent.cancel.CancellationSignal;
import cn.tedu.droneagent.context.ContextMessage;
import cn.tedu.droneagent.context.MessageKind;
import cn.tedu.droneagent.dto.ChatMessage;
import cn.tedu.droneagent.journal.WriteJournal;
import cn.tedu.droneagent.memory.SessionKey;
import cn.tedu.droneagent.skill.SkillCatalog;
import cn.tedu.droneagent.skill.SkillDefinition;
import cn.tedu.droneagent.tool.ToolCall;
import cn.tedu.droneagent.tool.ToolContext;
import cn.tedu.droneagent.tool.ToolGovernance;
import cn.tedu.droneagent.tool.ToolRegistry;
import cn.tedu.droneagent.tool.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

/**
 * 计划执行器（`REQ-AGENT-0031`）。
 *
 * <p>按 `dependsOn` 拓扑执行：同一批 ready 步骤里，只读工具并发、写与合成串行。
 * 任一步失败就停，未执行步骤如实标记；取消后不再开新步骤。
 *
 * <p>它**不绕过治理**：工具必须已注册、当前角色可用；危险工具本期直接拒绝，
 * 因为“计划批准”不等于“危险操作批准”。
 */
@Slf4j
public class PlanExecutor {

    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final SkillCatalog skills;
    private final ToolRegistry registry;
    private final ToolGovernance governance;
    private final ObjectMapper mapper;
    private final ExecutorService executor;
    private final int parallel;
    private final WriteJournal journal;
    private final cn.tedu.droneagent.service.ChatService chatService;

    public PlanExecutor(cn.tedu.droneagent.service.ChatService chatService, SkillCatalog skills,
                        ToolRegistry registry, ToolGovernance governance, ObjectMapper mapper,
                        ExecutorService executor, int parallel, WriteJournal journal) {
        this.chatService = chatService;
        this.skills = skills;
        this.registry = registry;
        this.governance = governance;
        this.mapper = mapper;
        this.executor = executor;
        this.parallel = Math.max(1, parallel);
        this.journal = journal == null ? WriteJournal.noop() : journal;
    }

    /**
     * 执行一份计划。
     *
     * @param status 进度回调（可空）；不要在高并发线程里直接调用，执行器只在主线程调用
     */
    public Result execute(Plan plan, SessionKey key, String userMessage, ToolContext context,
                          String traceId, CancellationSignal signal, Consumer<String> status,
                          PlanToolInvoker invoker) {
        long startNanos = System.nanoTime();
        Map<String, State> states = new LinkedHashMap<>();
        for (PlanStep step : plan.steps()) {
            states.put(step.id(), new State(step));
        }
        Map<String, String> outputs = new LinkedHashMap<>();
        log.info("【计划】开始执行：planId={}，name={}，origin={}，步骤 {} 个，traceId={}",
                plan.planId(), plan.name(), plan.origin(), plan.steps().size(), traceId);

        while (hasPending(states)) {
            if (signal != null && signal.isCancelled()) {
                skipPending(states);
                return result(plan, states, "计划已取消；已完成的步骤仍然有效。", traceId,
                        startNanos, "CANCELLED");
            }
            List<State> ready = ready(states);
            if (ready.isEmpty()) {
                failAllPending(states, "依赖无法满足（可能有环或依赖失败）");
                return result(plan, states, failureReply(plan, states), traceId, startNanos, "FAILED");
            }

            List<State> parallelReads = new ArrayList<>();
            List<State> serial = new ArrayList<>();
            for (State state : ready) {
                if (state.step.toolStep() && registry.isReadOnly(state.step.tool())
                        && !governance.isDangerous(state.step.tool())) {
                    parallelReads.add(state);
                } else {
                    serial.add(state);
                }
            }

            if (!parallelReads.isEmpty()) {
                if (!runParallelReads(plan, key, parallelReads, context, traceId, states, outputs, invoker)) {
                    skipPending(states);
                    return result(plan, states, failureReply(plan, states), traceId, startNanos, "FAILED");
                }
            }

            for (State state : serial) {
                if (signal != null && signal.isCancelled()) {
                    skipPending(states);
                    return result(plan, states, "计划已取消；已完成的步骤仍然有效。", traceId,
                            startNanos, "CANCELLED");
                }
                if (!runStep(plan, state, key, userMessage, context, traceId, states, outputs, invoker)) {
                    skipPending(states);
                    return result(plan, states, failureReply(plan, states), traceId, startNanos, "FAILED");
                }
                if (status != null) {
                    status.accept("计划步骤 " + doneCount(states) + "/" + plan.steps().size()
                            + " 完成：" + state.step.title());
                }
            }
        }

        String reply = finalReply(plan, states, outputs);
        String planStatus = "COMPLETED";
        log.info("【计划】完成：planId={}，name={}，耗时 {} ms，回复 {} 字",
                plan.planId(), plan.name(), millisSince(startNanos), reply.length());
        return result(plan, states, reply, traceId, startNanos, planStatus);
    }

    private boolean runParallelReads(Plan plan, SessionKey key, List<State> states,
                                     ToolContext context, String traceId, Map<String, State> all,
                                     Map<String, String> outputs, PlanToolInvoker invoker) {
        List<CompletableFuture<ToolResult>> futures = new ArrayList<>();
        for (State state : states) {
            state.status = "RUNNING";
            futures.add(CompletableFuture.supplyAsync(
                    () -> invokeTool(plan, key, state, context, traceId, outputs, invoker),
                    executor));
        }
        boolean ok = true;
        for (int i = 0; i < states.size(); i++) {
            State state = states.get(i);
            ToolResult result;
            try {
                result = futures.get(i).join();
            } catch (RuntimeException e) {
                result = ToolResult.failed("计划步骤执行异常：" + e.getMessage());
            }
            ok &= applyResult(state, result, outputs);
        }
        return ok;
    }

    private boolean runStep(Plan plan, State state, SessionKey key, String userMessage,
                            ToolContext context, String traceId, Map<String, State> all,
                            Map<String, String> outputs, PlanToolInvoker invoker) {
        state.status = "RUNNING";
        if (state.step.synthesizeStep()) {
            return runSynthesize(state, key, userMessage, context, traceId, outputs);
        }
        ToolResult result = invokeTool(plan, key, state, context, traceId, outputs, invoker);
        return applyResult(state, result, outputs);
    }

    private ToolResult invokeTool(Plan plan, SessionKey key, State state, ToolContext context,
                                  String traceId, Map<String, String> outputs,
                                  PlanToolInvoker invoker) {
        PlanStep step = state.step;
        if (step.tool() == null || registry.handler(step.tool()) == null) {
            return ToolResult.failed("工具未注册：" + step.tool());
        }
        if (!governance.allows(step.tool(), context.roles())) {
            return ToolResult.failed("当前角色无权使用工具：" + step.tool());
        }
        if (governance.isDangerous(step.tool())) {
            return ToolResult.failed("计划包含需要人工确认的危险工具「" + step.tool()
                    + "」，本次计划执行不绕过确认");
        }
        String argsJson;
        try {
            argsJson = mapper.writeValueAsString(resolveValue(step.args(), outputs));
        } catch (Exception e) {
            return ToolResult.failed("步骤参数无法序列化：" + e.getMessage());
        }
        if (argsJson.contains("${")) {
            return ToolResult.failed("步骤参数包含未解析占位符：" + argsJson);
        }
        String operationId = "plan-" + plan.planId() + "-" + step.id();
        boolean write = !registry.isReadOnly(step.tool());
        log.info("【计划】执行步骤：planId={}，step={}，tool={}，operationId={}，traceId={}",
                plan.planId(), step.id(), step.tool(), operationId, traceId);
        if (write) {
            journal.record(operationId, traceId, context.userId() == null ? 0 : context.userId(),
                    key.conversationId(), step.tool(), argsJson, "started", null);
        }
        ToolResult result = invoker.invoke(
                new ToolCall("plan-" + step.id(), step.tool(), argsJson), context, operationId);
        if (write) {
            journal.record(operationId, traceId, context.userId() == null ? 0 : context.userId(),
                    key.conversationId(), step.tool(), argsJson,
                    result != null && result.ok() ? "succeeded" : "failed", null);
        }
        return result;
    }

    private boolean applyResult(State state, ToolResult result, Map<String, String> outputs) {
        if (result != null && result.ok()) {
            state.status = "DONE";
            outputs.put(state.step.id(), result.content() == null ? "" : result.content());
            return true;
        }
        state.status = "FAILED";
        state.error = result == null ? "工具无结果" : result.content();
        return false;
    }

    private boolean runSynthesize(State state, SessionKey key, String userMessage,
                                  ToolContext context, String traceId,
                                  Map<String, String> outputs) {
        String prompt = synthesisPrompt(state.step, outputs);
        List<ContextMessage> messages = List.of(
                ContextMessage.of(new ChatMessage("system", prompt), MessageKind.SYSTEM),
                ContextMessage.of(new ChatMessage("user", userMessage), MessageKind.SESSION));
        try {
            String reply = chatService.chat(key, messages, context.permissions(), traceId);
            if (reply == null || reply.isBlank()) {
                state.status = "FAILED";
                state.error = "模型没有返回内容";
                return false;
            }
            state.status = "DONE";
            outputs.put(state.step.id(), reply);
            return true;
        } catch (RuntimeException e) {
            state.status = "FAILED";
            state.error = "生成最终答复失败：" + e.getMessage();
            return false;
        }
    }

    private String synthesisPrompt(PlanStep step, Map<String, String> outputs) {
        StringBuilder text = new StringBuilder();
        text.append("你正在执行一份已确定的计划，请基于步骤结果产出最终答复。\n");
        text.append("不要编造步骤结果里没有的数据；失败/缺失的步骤要如实说明。\n\n");
        if (step.prompt() != null && !step.prompt().isBlank()) {
            text.append("【本步要求】\n").append(step.prompt()).append("\n\n");
        }
        if (step.skill() != null && !step.skill().isBlank() && skills != null) {
            skills.findAny(step.skill()).ifPresent(skill -> {
                text.append("【流程说明】\n").append(skill.body()).append("\n\n");
                appendResources(text, skill);
            });
        }
        text.append("【步骤结果】\n");
        outputs.forEach((id, content) -> text.append("- ").append(id).append("：")
                .append(limit(content, 4000)).append("\n"));
        return text.toString();
    }

    private static void appendResources(StringBuilder text, SkillDefinition skill) {
        if (skill.resources().isEmpty()) {
            return;
        }
        text.append("【流程资料】\n");
        skill.resources().forEach((path, content) ->
                text.append("### ").append(path).append("\n").append(limit(content, 20000)).append("\n"));
        text.append("\n");
    }

    private Object resolveValue(Object value, Map<String, String> outputs) {
        if (value instanceof String text) {
            return resolveString(text, outputs);
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> resolved = new LinkedHashMap<>();
            map.forEach((k, v) -> resolved.put(String.valueOf(k), resolveValue(v, outputs)));
            return resolved;
        }
        if (value instanceof List<?> list) {
            List<Object> resolved = new ArrayList<>(list.size());
            for (Object item : list) {
                resolved.add(resolveValue(item, outputs));
            }
            return resolved;
        }
        return value;
    }

    private String resolveString(String text, Map<String, String> outputs) {
        String resolved = text;
        LocalDate today = LocalDate.now();
        LocalDate monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        resolved = resolved.replace("${today.start}", today.atStartOfDay().format(TIME_FORMAT));
        resolved = resolved.replace("${today.end}",
                today.plusDays(1).atStartOfDay().format(TIME_FORMAT));
        resolved = resolved.replace("${week.this.start}",
                monday.atStartOfDay().format(TIME_FORMAT));
        resolved = resolved.replace("${week.this.end}",
                monday.plusWeeks(1).atStartOfDay().format(TIME_FORMAT));
        resolved = resolved.replace("${week.last.start}",
                monday.minusWeeks(1).atStartOfDay().format(TIME_FORMAT));
        resolved = resolved.replace("${week.last.end}",
                monday.atStartOfDay().format(TIME_FORMAT));
        resolved = resolved.replace("${week.start}", monday.atStartOfDay().format(TIME_FORMAT));
        resolved = resolved.replace("${week.end}",
                monday.plusWeeks(1).atStartOfDay().format(TIME_FORMAT));
        for (Map.Entry<String, String> entry : outputs.entrySet()) {
            resolved = resolved.replace("${" + entry.getKey() + "}", entry.getValue());
            resolved = resolved.replace("${" + entry.getKey() + ".", "${" + entry.getKey() + ":");
        }
        // 形如 ${s1.id}：从 JSON 结果里取字段
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("\\$\\{([A-Za-z0-9_-]+):([A-Za-z0-9_.-]+)}")
                .matcher(resolved);
        while (matcher.find()) {
            String stepId = matcher.group(1);
            String field = matcher.group(2);
            String value = extractField(outputs.get(stepId), field);
            resolved = resolved.replace(matcher.group(), value == null ? "" : value);
        }
        return resolved;
    }

    private String extractField(String json, String field) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonNode root = mapper.readTree(json);
            String value = walk(root, field);
            if (value != null) {
                return value;
            }
            // 平台接口常见形状是 {"state":200,"data":{...}}：根上找不到就再往 data 里找
            return walk(root.path("data"), field);
        } catch (Exception e) {
            return null;
        }
    }

    private static String walk(JsonNode node, String field) {
        for (String part : field.split("\\.")) {
            node = node.path(part);
        }
        return node.isMissingNode() || node.isNull() ? null : node.asText();
    }

    private String finalReply(Plan plan, Map<String, State> states, Map<String, String> outputs) {
        for (State state : states.values()) {
            if (state.step.synthesizeStep() && "DONE".equals(state.status)) {
                return outputs.getOrDefault(state.step.id(), "计划已完成。");
            }
        }
        StringBuilder reply = new StringBuilder("计划已完成：\n");
        for (State state : states.values()) {
            reply.append("- ").append(state.step.title()).append("：")
                    .append("DONE".equals(state.status) ? "完成" : state.status).append("\n");
        }
        return reply.toString();
    }

    private String failureReply(Plan plan, Map<String, State> states) {
        StringBuilder reply = new StringBuilder("计划执行失败，未自动回滚。\n");
        for (State state : states.values()) {
            if ("FAILED".equals(state.status)) {
                reply.append("- 失败：").append(state.step.title())
                        .append("（").append(state.step.tool()).append("）：")
                        .append(limit(state.error, 300)).append("\n");
            } else if ("DONE".equals(state.status)) {
                reply.append("- 已完成：").append(state.step.title()).append("\n");
            } else if ("SKIPPED".equals(state.status)) {
                reply.append("- 未执行：").append(state.step.title()).append("\n");
            }
        }
        return reply.toString();
    }

    private Result result(Plan plan, Map<String, State> states, String reply, String traceId,
                          long startNanos, String status) {
        List<PlanSummary.Step> steps = new ArrayList<>();
        for (State state : states.values()) {
            steps.add(new PlanSummary.Step(state.step.id(), state.step.title(),
                    state.status, state.error));
        }
        return new Result(reply,
                new PlanSummary(plan.planId(), plan.name(), plan.origin().name(), status, steps),
                millisSince(startNanos));
    }

    private static boolean hasPending(Map<String, State> states) {
        return states.values().stream().anyMatch(state -> "PENDING".equals(state.status));
    }

    private static List<State> ready(Map<String, State> states) {
        List<State> ready = new ArrayList<>();
        for (State state : states.values()) {
            if (!"PENDING".equals(state.status)) {
                continue;
            }
            boolean ok = true;
            for (String dep : state.step.dependsOn()) {
                State dependency = states.get(dep);
                if (dependency == null || !"DONE".equals(dependency.status)) {
                    ok = false;
                    break;
                }
            }
            if (ok) {
                ready.add(state);
            }
        }
        return ready;
    }

    private static void skipPending(Map<String, State> states) {
        for (State state : states.values()) {
            if ("PENDING".equals(state.status)) {
                state.status = "SKIPPED";
            }
        }
    }

    private static void failAllPending(Map<String, State> states, String reason) {
        for (State state : states.values()) {
            if ("PENDING".equals(state.status)) {
                state.status = "FAILED";
                state.error = reason;
            }
        }
    }

    private static int doneCount(Map<String, State> states) {
        return (int) states.values().stream().filter(state -> "DONE".equals(state.status)).count();
    }

    private static String limit(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }

    private static long millisSince(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private static final class State {
        private final PlanStep step;
        private String status = "PENDING";
        private String error;

        private State(PlanStep step) {
            this.step = step;
        }
    }

    /** 执行结果：最终答复 + 计划摘要。 */
    public record Result(String reply, PlanSummary summary, long elapsedMs) {
    }
}
