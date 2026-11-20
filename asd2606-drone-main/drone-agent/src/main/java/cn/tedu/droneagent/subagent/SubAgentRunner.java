package cn.tedu.droneagent.subagent;

import cn.tedu.droneagent.budget.BudgetService;
import cn.tedu.droneagent.common.TraceIds;
import cn.tedu.droneagent.config.SubAgentProperties;
import cn.tedu.droneagent.context.ContextMessage;
import cn.tedu.droneagent.context.MessageKind;
import cn.tedu.droneagent.dto.ChatMessage;
import cn.tedu.droneagent.memory.SessionKey;
import cn.tedu.droneagent.prompt.ToolResultEnvelope;
import cn.tedu.droneagent.service.ChatService;
import cn.tedu.droneagent.tool.ModelMessage;
import cn.tedu.droneagent.tool.ModelTurn;
import cn.tedu.droneagent.tool.Tool;
import cn.tedu.droneagent.tool.ToolCall;
import cn.tedu.droneagent.tool.ToolContext;
import cn.tedu.droneagent.tool.ToolDefinition;
import cn.tedu.droneagent.tool.ToolGovernance;
import cn.tedu.droneagent.tool.ToolRegistry;
import cn.tedu.droneagent.tool.ToolResult;
import cn.tedu.droneagent.trace.TraceRecorder;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * 子 Agent 执行器（`REQ-AGENT-0032`）：独立上下文 + 受限 ReAct。
 *
 * <p>它只做三件事：给子 Agent 一份干净上下文、只开放白名单内的只读工具、
 * 把结果压成摘要返回。**不写、不共享主对话、不越过治理**。
 */
@Slf4j
public class SubAgentRunner {

    private final ChatService chatService;
    private final Supplier<ToolRegistry> registrySupplier;
    private final Supplier<ToolGovernance> governanceSupplier;
    private final TraceRecorder traceRecorder;
    private final BudgetService budgetService;
    private final SubAgentProperties properties;
    private final ExecutorService executor;

    public SubAgentRunner(ChatService chatService, Supplier<ToolRegistry> registrySupplier,
                          Supplier<ToolGovernance> governanceSupplier,
                          TraceRecorder traceRecorder, BudgetService budgetService,
                          SubAgentProperties properties, ExecutorService executor) {
        this.chatService = chatService;
        this.registrySupplier = registrySupplier;
        this.governanceSupplier = governanceSupplier;
        this.traceRecorder = traceRecorder == null ? TraceRecorder.noop() : traceRecorder;
        this.budgetService = budgetService == null ? BudgetService.noop() : budgetService;
        this.properties = properties == null ? new SubAgentProperties(0, 0) : properties;
        this.executor = executor;
    }

    public SubAgentResult run(SubAgentDefinition definition, String task, ToolContext context,
                              String parentTraceId) {
        long startNanos = System.nanoTime();
        String traceId = parentTraceId == null || parentTraceId.isBlank()
                ? TraceIds.newId() : parentTraceId;
        String parentSpanId = traceRecorder.rootSpanId(traceId);
        long userId = context.userId() == null ? 0 : context.userId();
        SessionKey key = new SessionKey(userId,
                "subagent-" + definition.name() + "-" + UUID.randomUUID());
        ToolRegistry registry = registrySupplier.get();
        ToolGovernance governance = governanceSupplier.get();

        List<ToolDefinition> tools = registry.definitions().stream()
                .filter(tool -> definition.tools().contains(tool.name()))
                .toList();
        if (tools.isEmpty()) {
            return finish(definition, "子代理没有可用工具（白名单为空或工具未注册）。",
                    0, 0, List.of(), userId, traceId, parentSpanId, startNanos, "error");
        }

        BudgetService.Check startBudget = budgetService.checkRequest(traceId);
        long startUsed = startBudget.used();
        long remaining = Math.max(0, startBudget.limit() - startUsed);
        if (startBudget.exceeded()
                || (properties.minRemainingTokens() > 0
                && remaining < properties.minRemainingTokens())) {
            return finish(definition, "子代理预算不足（剩余 " + remaining
                            + " token，启动下限 " + properties.minRemainingTokens() + "），未启动。",
                    0, 0, List.of(), userId, traceId, parentSpanId, startNanos, "budget_exceeded");
        }

        List<ContextMessage> messages = new ArrayList<>();
        messages.add(ContextMessage.of(
                new ChatMessage("system", definition.prompt()),
                MessageKind.SYSTEM));
        messages.add(ContextMessage.of(new ChatMessage("user", task), MessageKind.SESSION));

        long deadline = startNanos + TimeUnit.SECONDS.toNanos(definition.timeoutSeconds());
        int rounds = 0;
        int toolCalls = 0;
        String lastContent = null;
        Set<String> used = new LinkedHashSet<>();

        log.info("【子代理】开始：name={}，traceId={}，maxRounds={}，tools={}，taskLen={}",
                definition.name(), traceId, definition.maxRounds(),
                tools.stream().map(ToolDefinition::name).toList(), task.length());

        while (rounds < definition.maxRounds()) {
            if (System.nanoTime() > deadline) {
                return finish(definition, "子代理超时（超过 " + definition.timeoutSeconds()
                                + " 秒），未完成。", rounds, toolCalls, List.copyOf(used),
                        userId, traceId, parentSpanId, startNanos, "timeout");
            }
            BudgetService.Check budget = budgetService.checkRequest(traceId);
            if (budget.exceeded()) {
                return finish(definition, "子代理触发单请求预算上限（已用 "
                                + budget.used() + " / " + budget.limit() + "），未完成。",
                        rounds, toolCalls, List.copyOf(used), userId, traceId, parentSpanId,
                        startNanos, "budget_exceeded");
            }
            long spent = Math.max(0, budget.used() - startUsed);
            if (properties.maxPromptTokens() > 0 && spent >= properties.maxPromptTokens()) {
                return finish(definition, "子代理达到自身预算上限（已用 " + spent + " / "
                                + properties.maxPromptTokens() + " token），未完成。",
                        rounds, toolCalls, List.copyOf(used), userId, traceId, parentSpanId,
                        startNanos, "budget_exceeded");
            }

            ModelTurn turn = chatService.chatStep(key, messages, tools, traceId,
                    context.permissions());
            rounds++;
            if (turn.content() != null && !turn.content().isBlank()) {
                lastContent = turn.content();
            }
            if (!turn.hasToolCalls()) {
                String summary = turn.content() == null || turn.content().isBlank()
                        ? "子代理没有返回内容。" : turn.content();
                return finish(definition, summary, rounds, toolCalls, List.copyOf(used),
                        userId, traceId, parentSpanId, startNanos, "ok");
            }

            log.info("【子代理】第 {} 轮：模型请求 {} 个工具（name={}，traceId={}）",
                    rounds, turn.toolCalls().size(), definition.name(), traceId);
            messages.add(ContextMessage.toolCall(
                    ModelMessage.assistantToolCalls(turn.toolCalls(), turn.reasoningContent())));
            for (ToolCall call : turn.toolCalls()) {
                ToolResult result = invokeTool(definition, registry, governance, call, context,
                        traceId, parentSpanId);
                toolCalls++;
                used.add(call.name());
                messages.add(ContextMessage.toolResult(
                        ModelMessage.tool(call.id(), ToolResultEnvelope.wrap(
                                result.content() == null ? "" : result.content())),
                        call.name()));
            }
        }

        return finish(definition, "子代理达到轮次上限（" + definition.maxRounds()
                        + "），未完成。" + (lastContent == null ? "" : "最后输出：" + lastContent),
                rounds, toolCalls, List.copyOf(used), userId, traceId, parentSpanId,
                startNanos, "max_rounds");
    }

    private ToolResult invokeTool(SubAgentDefinition definition, ToolRegistry registry,
                                  ToolGovernance governance, ToolCall call, ToolContext context,
                                  String traceId, String parentSpanId) {
        long startNanos = System.nanoTime();
        String start = Instant.now().toString();
        ToolResult result = doInvokeTool(definition, registry, governance, call, context);
        traceRecorder.record(context.userId(), traceId, parentSpanId, "subagent.tool", start,
                millisSince(startNanos), result.ok() ? "ok" : "error",
                java.util.Map.of("subagent", definition.name(), "tool", call.name(),
                        "argsChars", call.argumentsJson() == null ? 0 : call.argumentsJson().length(),
                        "resultChars", result.content() == null ? 0 : result.content().length()));
        log.info("【子代理】工具完成：name={}，tool={}，ok={}，耗时 {} ms，traceId={}",
                definition.name(), call.name(), result.ok(), millisSince(startNanos), traceId);
        return result;
    }

    private ToolResult doInvokeTool(SubAgentDefinition definition, ToolRegistry registry,
                                    ToolGovernance governance, ToolCall call,
                                    ToolContext context) {
        if (!definition.tools().contains(call.name())) {
            return ToolResult.failed("子代理不允许调用工具「" + call.name() + "」");
        }
        Tool tool = registry.handler(call.name());
        if (tool == null) {
            return ToolResult.failed("工具未注册：" + call.name());
        }
        if (!registry.isReadOnly(call.name())) {
            return ToolResult.failed("子代理只允许只读工具，拒绝执行：" + call.name());
        }
        if (!governance.allows(call.name(), context.roles())) {
            return ToolResult.failed("当前角色无权使用工具：" + call.name());
        }
        if (governance.isDangerous(call.name())) {
            return ToolResult.failed("子代理不执行危险工具：" + call.name());
        }
        try {
            CompletableFuture<ToolResult> future = CompletableFuture.supplyAsync(
                    () -> tool.invoke(call.argumentsJson(), context), executor);
            return future.get(definition.timeoutSeconds(), TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            return ToolResult.failed("子代理工具超时（超过 "
                    + definition.timeoutSeconds() + " 秒）：" + call.name());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResult.failed("子代理工具被中断：" + call.name());
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            return ToolResult.failed("子代理工具异常：" + cause.getMessage());
        }
    }

    private SubAgentResult finish(SubAgentDefinition definition, String summary, int rounds,
                                  int toolCalls, List<String> used, long userId, String traceId,
                                  String parentSpanId, long startNanos, String status) {
        long elapsed = millisSince(startNanos);
        traceRecorder.record(userId, traceId, parentSpanId, "subagent.run",
                Instant.now().minusMillis(elapsed).toString(), elapsed, status,
                java.util.Map.of("subagent", definition.name(), "rounds", rounds,
                        "toolCalls", toolCalls));
        log.info("【子代理】完成：name={}，status={}，轮次={}，工具调用={}，耗时 {} ms，摘要 {} 字，traceId={}",
                definition.name(), status, rounds, toolCalls, elapsed,
                summary == null ? 0 : summary.length(), traceId);
        return new SubAgentResult(summary == null ? "" : summary, rounds, toolCalls, elapsed,
                used, status);
    }

    private static long millisSince(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
