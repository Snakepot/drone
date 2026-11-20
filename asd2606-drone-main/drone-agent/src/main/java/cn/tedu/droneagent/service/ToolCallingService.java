package cn.tedu.droneagent.service;

import cn.tedu.droneagent.budget.BudgetService;
import cn.tedu.droneagent.cancel.CancellationReport;
import cn.tedu.droneagent.cancel.CancellationSignal;
import cn.tedu.droneagent.journal.WriteJournal;
import cn.tedu.droneagent.common.TraceIds;
import cn.tedu.droneagent.config.ToolProperties;
import cn.tedu.droneagent.context.ContextMessage;
import cn.tedu.droneagent.context.MessageKind;
import cn.tedu.droneagent.dto.PendingConfirmation;
import cn.tedu.droneagent.memory.SensitiveMasker;
import cn.tedu.droneagent.memory.SessionKey;
import cn.tedu.droneagent.plan.Plan;
import cn.tedu.droneagent.plan.PlanExecutor;
import cn.tedu.droneagent.plan.PlanService;
import cn.tedu.droneagent.plan.PlanSummary;
import cn.tedu.droneagent.prompt.ToolResultEnvelope;
import cn.tedu.droneagent.route.RouteDecision;
import cn.tedu.droneagent.route.TaskRoute;
import cn.tedu.droneagent.route.TaskRouter;
import cn.tedu.droneagent.subagent.SubAgentResult;
import cn.tedu.droneagent.subagent.SubAgentService;
import cn.tedu.droneagent.tool.ModelMessage;
import cn.tedu.droneagent.tool.ModelTurn;
import cn.tedu.droneagent.tool.PendingActionStore;
import cn.tedu.droneagent.tool.Tool;
import cn.tedu.droneagent.tool.ToolAuditLog;
import cn.tedu.droneagent.skill.ReadSkillResourceTool;
import cn.tedu.droneagent.skill.UseSkillTool;
import cn.tedu.droneagent.tool.ToolCall;
import cn.tedu.droneagent.tool.ToolContext;
import cn.tedu.droneagent.tool.ToolDefinition;
import cn.tedu.droneagent.tool.ToolGovernance;
import cn.tedu.droneagent.tool.ToolRegistry;
import cn.tedu.droneagent.tool.ToolResult;
import cn.tedu.droneagent.tool.ToolResultCompressor;
import cn.tedu.droneagent.tool.ToolVisibility;
import cn.tedu.droneagent.trace.TraceRecorder;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 工具调用的编排：把「一问一答」变成「模型要工具 → 执行 → 回填 → 再问」的小循环。
 *
 * <h2>它把哪 7 步串起来</h2>
 * <ol>
 *   <li>把组装好的上下文搬成线格式，并在请求里带上工具定义；</li>
 *   <li>调一次模型（{@link ChatService#chatStep}）；</li>
 *   <li>模型若返回 {@code tool_calls}：把「助手请求」这一帧入列；</li>
 *   <li>逐个执行工具（{@link Tool#invoke}）；</li>
 *   <li>把每条工具结果作为 {@code tool} 帧入列（靠 {@code tool_call_id} 对上号）；</li>
 *   <li>回到第 2 步再问一次；</li>
 *   <li>模型不再要工具时，它的正文就是最终答复。</li>
 * </ol>
 *
 * <h2>为什么返回 {@link AskResult} 而不是一个字符串</h2>
 * 遇到**危险工具**（见 {@link ToolGovernance}）时，本轮**不执行**：先生成一枚「待确认」票据回给前端，
 * 等用户确认后再由 {@code ChatConfirmService} 续跑。所以「最终答复」之外还得能带回「待确认」这一路。
 * 只要正文的调用方（含测试）走 {@link #ask} 便捷版即可。
 *
 * <h2>为什么要设「轮次上限」</h2>
 * 模型可能没完没了地要工具。超过上限后**不再提供工具**，逼它基于已有结果作答 ——
 * 这样循环必然终止，且用户拿到的仍是「基于已查到内容」的回答。
 *
 * <h2>它不负责什么</h2>
 * <ul>
 *   <li><b>不组装会话上下文</b>：传进来的 {@code context} 已由
 *       {@link cn.tedu.droneagent.memory.ContextAssembler} 备好。</li>
 *   <li><b>不写会话记忆</b>：工具调用的中间消息**不入记忆**，只把最终答复交回调用方。</li>
 *   <li><b>不判身份</b>：角色由调用方**显式传进来**（不读 ThreadLocal）。</li>
 *   <li><b>不联网</b>：真正干活的是 {@link Tool} 的实现。</li>
 * </ul>
 */
@Slf4j
public class ToolCallingService {

    /** 默认最多允许「几轮带工具」的调用；超了就不再给工具，改为收尾作答 */
    private static final int DEFAULT_MAX_ROUNDS = 3;

    /** 读工具在途时轮询取消的间隔：足够快，又不会把等待切得太碎 */
    private static final long CANCEL_POLL_MILLIS = 50;

    /**
     * 关闭编排优化时用的**旧**单次回填上限（`REQ-AGENT-0007` 之前的行为）；
     * 开启优化后改用配置里的 {@code max-result-chars}。
     */
    static final int LEGACY_RESULT_CHARS = 4000;

    /** 工具结果总量预算用尽时，回填给模型的占位（免得把「没回填」误当成「工具没返回」） */
    static final String SKIPPED_RESULT = "（本轮工具结果过多，已省略）";

    /** Skill 资源单次上限默认值：与 {@code agent.skill.max-resource-chars} 对齐 */
    static final int DEFAULT_MAX_SKILL_RESULT_CHARS = 32768;

    /**
     * Skill 资源累计上限默认值。
     *
     * <p>取值依据：日报正文 5.5KB + 模板 12.3KB + 口径 2.4KB ≈ 20KB，
     * 30,000 字符能容纳一次完整流程，同时把最坏情况限制在约 1.5 万 token。
     */
    static final int DEFAULT_MAX_TOTAL_SKILL_CHARS = 30000;

    /** Skill 资源被截断时追加的标记：必须让模型知道「后面还有，别当成全文」 */
    static final String SKILL_TRUNCATED = "（技能资料超出预算，已截断；如需后续内容，请分段读取）";

    /** Skill 资源累计预算已用尽时的占位（返回空串会让模型以为资源本来就是空的） */
    static final String SKILL_SKIPPED = "（技能资料预算已用尽，本次未加载；如确需，请分段读取）";

    /** 模型始终给不出正文时的兜底话术，避免返回空字符串 */
    /** 模型把工具调用写成了伪标记（DSML）时的兜底话术：这种内容对用户没有意义 */
    private static final String PSEUDO_TOOL_REPLY =
            "抱歉，这次没有整理出完整结果（工具调用轮次用尽）。请再试一次，或让我只做其中一部分。";

    private static final String FALLBACK_REPLY = "抱歉，我这边暂时没能给出结果，请稍后再试。";

    /** 用户取消后的答复（`REQ-AGENT-0029`）：不假装回滚，已完成步骤仍有效 */
    static final String CANCELLED_REPLY =
            "已取消本次请求。已完成的步骤仍然有效；如需撤销，请告诉我。";

    /** 需要人工确认时，先回给用户的提示（真正的执行留到确认之后） */
    private static final String PENDING_HINT = "这项操作需要你确认后才会执行。";

    /** 未授权时回填给模型的话术 */
    private static final String UNAUTHORIZED = "你没有执行「%s」的权限";

    /**
     * 一次编排的结果：要么是最终答复，要么是「需要人工确认」。
     *
     * @param reply   最终答复正文；处于「待确认」时只是一句提示
     * @param pending 待确认信息；没有则为 null
     */
    public record AskResult(String reply, PendingConfirmation pending, long reasoningMs,
                            CancellationReport report, PlanSummary plan) {

        /** 兼容既有调用：没有用时、没有取消报告、没有计划 */
        public AskResult(String reply, PendingConfirmation pending, long reasoningMs,
                         CancellationReport report) {
            this(reply, pending, reasoningMs, report, null);
        }

        /** 兼容既有调用：没有用时、没有取消报告 */
        public AskResult(String reply, PendingConfirmation pending, long reasoningMs) {
            this(reply, pending, reasoningMs, null, null);
        }

        public AskResult(String reply, PendingConfirmation pending) {
            this(reply, pending, 0, null, null);
        }
    }

    private final ChatService chatService;

    /** 工具注册处：定义与行为都在这里，本类不再自己维护 Map */
    private final ToolRegistry registry;

    /** 跑工具用的执行器：工具可能慢，别占着请求线程干等；并发也靠它 */
    private final Executor toolExecutor;

    /** 是否允许把本轮的多个只读工具**并发**执行 */
    private final boolean parallelEnabled;

    /** 单个工具的执行超时；到点就把**这一个**工具按失败回填 */
    private final Duration invokeTimeout;

    private final int maxRounds;

    /** 编排优化（重试 / 压缩 / 预算）总开关；关掉即回到 `0005` 的行为 */
    private final boolean optimizeEnabled;

    /** 单次工具回填的字符上限（关闭优化时用 {@link #LEGACY_RESULT_CHARS}） */
    private final int maxResultChars;

    /** 一次对话里所有工具结果的字符总量上限 */
    private final int maxTotalResultChars;

    /** 单次 Skill 资源（use_skill / read_skill_resource）的字符上限 */
    private final int maxSkillResultChars;

    /** 一次请求内 Skill 资源的累计字符上限（`REQ-AGENT-0019`） */
    private final int maxTotalSkillChars;

    /** 工具失败后允许的重试次数 */
    private final int maxRetries;

    /** 工具结果的压缩（分级：结构化裁剪 → 模型摘要 → 硬截断） */
    private final ToolResultCompressor compressor;

    /** 危险工具的治理（谁是危险工具、谁有权用） */
    private final ToolGovernance governance;

    /** 工具可见性的权限门（`REQ-AGENT-0018`）：按角色与权限裁剪下发给模型的定义 */
    private final ToolVisibility visibility;

    /** 待确认动作的短期存储 */
    private final PendingActionStore pendingStore;

    /** 危险操作执行的审计（写失败只记日志，不回退） */
    private final ToolAuditLog auditLog;

    /** 链路追踪；测试与不关心的场景用 noop */
    private TraceRecorder traceRecorder = TraceRecorder.noop();

    /** 预算执行（REQ-AGENT-0028）；测试与不关心的场景用 noop */
    private BudgetService budgetService = BudgetService.noop();

    /** 写操作台账（REQ-AGENT-0029）；测试与不关心的场景用 noop */
    private WriteJournal writeJournal = WriteJournal.noop();

    /** Plan 模式（REQ-AGENT-0031）；测试与不关心的场景用 noop */
    private PlanService planService = PlanService.noop();

    /** 任务路由（REQ-AGENT-0033）；测试与不关心的场景用 disabled */
    private TaskRouter taskRouter = TaskRouter.disabled();

    /** 子 Agent 门面（REQ-AGENT-0033）；测试与不关心的场景用 noop */
    private SubAgentService subAgentService = SubAgentService.noop();

    /** 顺序执行、不设并行、优化关闭、无治理——供测试与不支持并发的场景 */
    ToolCallingService(ChatService chatService, ToolRegistry registry) {
        this(chatService, registry, DEFAULT_MAX_ROUNDS);
    }

    ToolCallingService(ChatService chatService, ToolRegistry registry, int maxRounds) {
        this(chatService, registry, Runnable::run, false, Duration.ofSeconds(15), maxRounds);
    }

    /** 指定并行 / 超时 / 轮数；优化关闭、沿用旧的 4000 截断（供测试） */
    public ToolCallingService(ChatService chatService, ToolRegistry registry, Executor toolExecutor,
                              boolean parallelEnabled, Duration invokeTimeout, int maxRounds) {
        this(chatService, registry, toolExecutor, parallelEnabled, invokeTimeout, maxRounds,
                false, LEGACY_RESULT_CHARS, Integer.MAX_VALUE,
                DEFAULT_MAX_SKILL_RESULT_CHARS, DEFAULT_MAX_TOTAL_SKILL_CHARS, 0,
                new ToolResultCompressor(null),
                noGovernance(), new PendingActionStore(Duration.ofMinutes(5)), ToolAuditLog.noop(),
                ToolVisibility.allowAll());
    }

    /** 治理场景（`REQ-AGENT-0006`）测试用：可注入治理与待确认存储，其余取默认 */
    ToolCallingService(ChatService chatService, ToolRegistry registry, ToolGovernance governance,
                       PendingActionStore pendingStore) {
        this(chatService, registry, Runnable::run, false, Duration.ofSeconds(15), DEFAULT_MAX_ROUNDS,
                false, LEGACY_RESULT_CHARS, Integer.MAX_VALUE,
                DEFAULT_MAX_SKILL_RESULT_CHARS, DEFAULT_MAX_TOTAL_SKILL_CHARS, 0,
                new ToolResultCompressor(null),
                governance, pendingStore, ToolAuditLog.noop(), ToolVisibility.allowAll());
    }

    /** 装配（{@code ToolConfig}）用：全部取值来自配置 */
    public ToolCallingService(ChatService chatService, ToolRegistry registry, Executor toolExecutor,
                              ToolResultCompressor compressor, ToolGovernance governance,
                              PendingActionStore pendingStore, ToolAuditLog auditLog,
                              ToolProperties properties) {
        this(chatService, registry, toolExecutor, properties.parallelEnabled(), properties.invokeTimeout(),
                properties.maxRounds(), properties.optimizeEnabled(), properties.maxResultChars(),
                properties.maxTotalResultChars(), properties.maxSkillResultChars(),
                properties.maxTotalSkillChars(), properties.maxRetries(), compressor, governance,
                pendingStore, auditLog, new ToolVisibility(properties.visibleBy()));
    }

    /** 装配用：额外注入链路追踪；旧构造保持 noop，测试无需改动 */
    public ToolCallingService(ChatService chatService, ToolRegistry registry, Executor toolExecutor,
                              ToolResultCompressor compressor, ToolGovernance governance,
                              PendingActionStore pendingStore, ToolAuditLog auditLog,
                              ToolProperties properties, TraceRecorder traceRecorder) {
        this(chatService, registry, toolExecutor, compressor, governance, pendingStore, auditLog,
                properties, traceRecorder, BudgetService.noop());
    }

    /** 装配用：追踪 + 预算（REQ-AGENT-0028） */
    public ToolCallingService(ChatService chatService, ToolRegistry registry, Executor toolExecutor,
                              ToolResultCompressor compressor, ToolGovernance governance,
                              PendingActionStore pendingStore, ToolAuditLog auditLog,
                              ToolProperties properties, TraceRecorder traceRecorder,
                              BudgetService budgetService) {
        this(chatService, registry, toolExecutor, compressor, governance, pendingStore, auditLog,
                properties, traceRecorder, budgetService, WriteJournal.noop());
    }

    /** 装配用：追踪 + 预算 + 写台账（REQ-AGENT-0029） */
    public ToolCallingService(ChatService chatService, ToolRegistry registry, Executor toolExecutor,
                              ToolResultCompressor compressor, ToolGovernance governance,
                              PendingActionStore pendingStore, ToolAuditLog auditLog,
                              ToolProperties properties, TraceRecorder traceRecorder,
                              BudgetService budgetService, WriteJournal writeJournal) {
        this(chatService, registry, toolExecutor, properties.parallelEnabled(),
                properties.invokeTimeout(), properties.maxRounds(), properties.optimizeEnabled(),
                properties.maxResultChars(), properties.maxTotalResultChars(),
                properties.maxSkillResultChars(), properties.maxTotalSkillChars(),
                properties.maxRetries(), compressor, governance, pendingStore, auditLog,
                new ToolVisibility(properties.visibleBy()));
        this.traceRecorder = traceRecorder == null ? TraceRecorder.noop() : traceRecorder;
        this.budgetService = budgetService == null ? BudgetService.noop() : budgetService;
        this.writeJournal = writeJournal == null ? WriteJournal.noop() : writeJournal;
        this.planService = PlanService.noop();
    }

    /** 装配用：追踪 + 预算 + 写台账 + Plan（REQ-AGENT-0031） */
    public ToolCallingService(ChatService chatService, ToolRegistry registry, Executor toolExecutor,
                              ToolResultCompressor compressor, ToolGovernance governance,
                              PendingActionStore pendingStore, ToolAuditLog auditLog,
                              ToolProperties properties, TraceRecorder traceRecorder,
                              BudgetService budgetService, WriteJournal writeJournal,
                              PlanService planService) {
        this(chatService, registry, toolExecutor, compressor, governance, pendingStore, auditLog,
                properties, traceRecorder, budgetService, writeJournal);
        this.planService = planService == null ? PlanService.noop() : planService;
    }

    /** 装配用：Plan + 任务路由 + 子 Agent（REQ-AGENT-0033） */
    public ToolCallingService(ChatService chatService, ToolRegistry registry, Executor toolExecutor,
                              ToolResultCompressor compressor, ToolGovernance governance,
                              PendingActionStore pendingStore, ToolAuditLog auditLog,
                              ToolProperties properties, TraceRecorder traceRecorder,
                              BudgetService budgetService, WriteJournal writeJournal,
                              PlanService planService, TaskRouter taskRouter,
                              SubAgentService subAgentService) {
        this(chatService, registry, toolExecutor, compressor, governance, pendingStore, auditLog,
                properties, traceRecorder, budgetService, writeJournal, planService);
        this.taskRouter = taskRouter == null ? TaskRouter.disabled() : taskRouter;
        this.subAgentService = subAgentService == null ? SubAgentService.noop() : subAgentService;
    }

    private ToolCallingService(ChatService chatService, ToolRegistry registry, Executor toolExecutor,
                               boolean parallelEnabled, Duration invokeTimeout, int maxRounds,
                               boolean optimizeEnabled, int maxResultChars, int maxTotalResultChars,
                               int maxSkillResultChars, int maxTotalSkillChars, int maxRetries,
                               ToolResultCompressor compressor, ToolGovernance governance,
                               PendingActionStore pendingStore, ToolAuditLog auditLog,
                               ToolVisibility visibility) {
        this.chatService = chatService;
        this.registry = registry;
        this.toolExecutor = toolExecutor;
        this.parallelEnabled = parallelEnabled;
        this.invokeTimeout = invokeTimeout;
        this.maxRounds = Math.max(1, maxRounds);
        this.optimizeEnabled = optimizeEnabled;
        this.maxResultChars = maxResultChars;
        this.maxTotalResultChars = maxTotalResultChars;
        this.maxSkillResultChars = maxSkillResultChars;
        this.maxTotalSkillChars = maxTotalSkillChars;
        this.maxRetries = Math.max(0, maxRetries);
        this.compressor = compressor;
        this.governance = governance;
        this.visibility = visibility;
        this.pendingStore = pendingStore;
        this.auditLog = auditLog;
    }

    private static ToolGovernance noGovernance() {
        return new ToolGovernance(List.of(), List.of(), Map.of());
    }

    /** 便捷重载：自生成 traceId、不带角色（供测试与不需要治理的场景） */
    public String ask(SessionKey key, List<ContextMessage> context) {
        return ask(key, context, TraceIds.newId());
    }

    public String ask(SessionKey key, List<ContextMessage> context, String traceId) {
        return askOutcome(key, context, traceId, ToolContext.anonymous()).reply();
    }

    /**
     * 跑完整个工具调用循环，返回「答复或待确认」。
     *
     * @param key         会话标识（userId 用于日志与待确认归属）
     * @param context     已组装好的上下文（不含系统提示词，每条已带账单分类）
     * @param traceId     本次请求的追踪 id
     * @param toolContext 调用方身份（角色做授权判定；权限码供「按人收窄数据」的工具用）
     */
    public AskResult askOutcome(SessionKey key, List<ContextMessage> context, String traceId,
                                ToolContext toolContext) {
        return askOutcome(key, context, traceId, toolContext, CancellationSignal.never());
    }

    /** 带取消信号的编排（REQ-AGENT-0029） */
    public AskResult askOutcome(SessionKey key, List<ContextMessage> context, String traceId,
                                ToolContext toolContext, CancellationSignal signal) {
        return askOutcome(key, context, traceId, toolContext, signal, null);
    }

    /** 带取消信号 + 续跑令牌的编排（REQ-AGENT-0029） */
    public AskResult askOutcome(SessionKey key, List<ContextMessage> context, String traceId,
                                ToolContext toolContext, CancellationSignal signal,
                                CancellationReport.ResumeToken resumeToken) {
        return runLoop(key, new ArrayList<>(context), traceId, toolContext,
                signal == null ? CancellationSignal.never() : signal, resumeToken);
    }

    /**
     * 从「待确认」续跑：把用户**确认/拒绝**的结果接上去，继续这个循环。
     *
     * <p>{@code approve=true} 才真正执行票据里的工具；{@code false} 就当作「用户拒绝」回填。
     * 执行仍走 {@link #runTool}，所以**授权会再判一次**（防御性：确认期间权限可能已被改掉）。
     */
    public AskResult resume(SessionKey key, PendingActionStore.PendingAction action, boolean approve,
                            String traceId, ToolContext context) {
        // 关键：**原样回放模型当初那条 assistant 帧**（原始 tool_call id + reasoning_content）。
        // 不能现造一条 id=confirm_xxx 的帧——深度思考模式下模型服务会把 reasoning 与原始调用绑定，
        // id 对不上就会报 `reasoning_content must be passed back`（REQ-AGENT-0006 修复）。
        //
        // 暂停时只存了线格式消息（没存账单分类），所以恢复出来统一标 RESUME：
        // 这里再去猜「哪条是长期记忆、哪条是会话」只会猜错，账单宁可不细，也不能假。
        List<ContextMessage> messages = new ArrayList<>();
        for (ModelMessage message : action.messages()) {
            messages.add(new ContextMessage(message, MessageKind.RESUME, null));
        }
        messages.add(ContextMessage.toolCall(action.assistantFrame()));

        boolean dangerousHandled = false;
        int skipped = 0;
        for (ToolCall call : action.assistantFrame().toolCalls()) {
            ToolResult result;
            if (call.id().equals(action.dangerousCallId())) {
                dangerousHandled = true;
                result = approve
                        ? runTool(call, context)
                        : ToolResult.failed("用户拒绝了这次操作");
                if (approve) {
                    // 危险操作**执行之后**记账；被拒绝的没执行，不记
                    auditLog.recordExecution(key.userId(), call.name(), call.argumentsJson(), result.ok());
                }
            } else {
                // 同轮的其他工具：整轮在暂停时就没执行，这里如实回填「未执行」，
                // 保证 assistant 帧里的每个 tool_call 都有对应的 tool 结果
                skipped++;
                result = ToolResult.failed("本轮因危险操作等待确认，该工具未执行");
            }
            messages.add(ContextMessage.toolResult(
                    ModelMessage.tool(call.id(), fit(call.name(), result.content(), 0, 0)), call.name()));
        }

        if (!dangerousHandled) {
            log.error("【工具编排】票据与 assistant 帧不一致：pendingId={}，dangerousCallId={}，"
                            + "该帧里没有对应调用；本次续跑将不带任何执行结果",
                    action.id(), action.dangerousCallId());
        }

        log.info("【工具编排】确认续跑：approve={}，工具=「{}」，同轮跳过 {} 个，"
                        + "原样回放 assistant 帧（callId={}，reasoning {} 字符）（traceId={}，pendingId={}）",
                approve, action.toolName(), skipped, action.dangerousCallId(),
                action.assistantFrame().reasoningContent() == null
                        ? 0 : action.assistantFrame().reasoningContent().length(),
                traceId, action.id());
        return runLoop(key, messages, traceId, context, CancellationSignal.never(), null);
    }

    /** 一次「调模型」的动作：非流式与流式各给一个实现，循环本体只认它 */
    private interface RoundCall {
        ModelTurn call(List<ContextMessage> messages, List<ToolDefinition> tools);
    }

    /** 非流式循环（既有路径） */
    private AskResult runLoop(SessionKey key, List<ContextMessage> messages, String traceId,
                              ToolContext context, CancellationSignal signal,
                              CancellationReport.ResumeToken resumeToken) {
        return loop(key, messages, traceId, context,
                (msgs, tools) -> chatService.chatStep(key, msgs, tools, traceId, context.permissions()),
                text -> {
                }, signal, resumeToken);
    }

    /**
     * 流式循环：与非流式同一套编排逻辑，只把「调模型」换成流式版本。
     *
     * <p>思考与正文增量由 {@link ChatService#streamStep} 直接回调给前端；
     * 工具执行期间通过 {@code onStatus} 告知进度（否则用户会以为卡住了）。
     */
    public AskResult askOutcomeStreaming(SessionKey key, List<ContextMessage> context, String traceId,
                                         ToolContext toolContext, ChatStreamListener listener) {
        return askOutcomeStreaming(key, context, traceId, toolContext, listener,
                CancellationSignal.never());
    }

    /** 带取消信号的流式编排（REQ-AGENT-0029） */
    public AskResult askOutcomeStreaming(SessionKey key, List<ContextMessage> context, String traceId,
                                         ToolContext toolContext, ChatStreamListener listener,
                                         CancellationSignal signal) {
        return askOutcomeStreaming(key, context, traceId, toolContext, listener, signal, null);
    }

    /** 带取消信号 + 续跑令牌的流式编排 */
    public AskResult askOutcomeStreaming(SessionKey key, List<ContextMessage> context, String traceId,
                                         ToolContext toolContext, ChatStreamListener listener,
                                         CancellationSignal signal,
                                         CancellationReport.ResumeToken resumeToken) {
        ChatStreamListener sink = listener == null ? ChatStreamListener.NOOP : listener;
        return loop(key, new ArrayList<>(context), traceId, toolContext,
                (msgs, tools) -> chatService.streamStep(key, msgs, tools, traceId,
                        toolContext.permissions(), sink),
                sink::onStatus,
                signal == null ? CancellationSignal.never() : signal, resumeToken);
    }

    /** 循环本体。{@code messages} 会被就地追加，调用方不必复用。 */
    private AskResult loop(SessionKey key, List<ContextMessage> messages, String traceId,
                           ToolContext context, RoundCall roundCall,
                           java.util.function.Consumer<String> status, CancellationSignal signal,
                           CancellationReport.ResumeToken resumeToken) {
        long startNanos = System.nanoTime();
        long lastReasoningMs = 0;
        String parentSpanId = traceRecorder.rootSpanId(traceId);
        List<ToolDefinition> definitions = visibleDefinitions(context, traceId);

        log.info("【工具编排】开始：traceId={}，userId={}，conversationId={}，上下文 {} 条消息，可用工具 {} 个 {}，轮次上限 {}",
                traceId, key.userId(), key.conversationId(), messages.size(), definitions.size(),
                toolNames(definitions), maxRounds);

        // 任务路由（REQ-AGENT-0033）：由代码按问题特征选择 ReAct / Plan / SubAgent。
        // 路由开启时不再让模型自己纠结“要不要委派/要不要计划”。
        String userMessage = lastUserMessage(messages);
        if (taskRouter.enabled()) {
            BudgetService.Check requestBudget = budgetService.checkRequest(traceId);
            if (requestBudget.exceeded()) {
                log.warn("【预算】路由前已超限，停止处理：traceId={}，已用 {}，上限 {}",
                        traceId, requestBudget.used(), requestBudget.limit());
                return new AskResult(BudgetService.requestReply(requestBudget), null);
            }
            RouteDecision decision = taskRouter.route(userMessage);
            if (decision.route() == TaskRoute.SUBAGENT) {
                AskResult sub = runSubAgent(decision, userMessage, context, traceId, status);
                if (sub != null) {
                    return sub;
                }
                // 子代理不可用/失败：降级到 ReAct
            } else if (decision.route() == TaskRoute.PLAN) {
                AskResult planned = runPlan(decision, key, userMessage, context, traceId, signal, status);
                if (planned != null) {
                    return planned;
                }
                // 计划不可用/生成失败：降级到 ReAct
            }
        } else if (planService.enabled()) {
            // 路由关闭时保留旧行为：显式先给方案 → 内置计划 → ReAct
            if (planService.wantsGeneratedPlan(userMessage)) {
                try {
                    status.accept("正在生成计划...");
                    PlanSummary summary = planService.generateAndStore(key.userId(), key,
                            userMessage, context, traceId);
                    return new AskResult("计划已生成，请确认后执行。", null, 0, null, summary);
                } catch (RuntimeException e) {
                    log.warn("【计划】生成失败：userId={}，原因={}", key.userId(), e.getMessage());
                    return new AskResult("生成计划失败：" + e.getMessage(), null);
                }
            }
            Plan builtin = planService.matchBuiltin(userMessage).orElse(null);
            if (builtin != null) {
                log.info("【计划】命中内置计划：name={}，planId={}，traceId={}",
                        builtin.name(), builtin.planId(), traceId);
                status.accept("正在执行内置计划：" + builtin.name());
                return executePlan(builtin, key, userMessage, context, traceId, signal, status);
            }
        }

        boolean usedTool = false;
        // 工具结果的「总量预算」用量：跨轮累加，超预算的结果不再回填
        int usedResultChars = 0;
        // Skill 资源的独立预算用量（REQ-AGENT-0019）：与上面的通用预算互不挤占
        int usedSkillChars = 0;
        // 出现过失败的轮数：只有「失败」才可能触发重试，所以要数它
        int failedRounds = 0;
        // 重试预算：关闭优化时不限（回到 0005 的行为），开启时用配置值
        int retryBudget = optimizeEnabled ? maxRetries : Integer.MAX_VALUE;
        // 被压缩 / 省略的结果条数
        int compressedCount = 0;
        // Skill 资源被截断的次数（与普通压缩分开统计，便于发现模板变大）
        int skillTruncatedCount = 0;
        // 取消报告（REQ-AGENT-0029）：写步骤的成功/失败/未知，执行时逐条结算
        List<CancellationReport.Step> completedWrites = new ArrayList<>();
        List<CancellationReport.Step> failedWrites = new ArrayList<>();
        List<CancellationReport.Step> unknownWrites = new ArrayList<>();
        List<CancellationReport.Operation> completedOperations = new ArrayList<>();
        List<CancellationReport.Captured> capturedIds = new ArrayList<>();
        // 续跑索引：key = 工具 + 参数摘要 → 上一次的 operationId。
        // 用参数摘要而不是工具名，才能区分「同一工具跑了多次、参数不同」的步骤。
        Map<String, String> resumable = resumableOperations(resumeToken);

        for (int round = 1; ; round++) {
            if (signal.isCancelled()) {
                log.info("【取消】第 {} 轮开始前已取消，停止编排（traceId={}）", round, traceId);
                return cancelledResult(lastReasoningMs, completedWrites, failedWrites,
                        unknownWrites, List.of(), capturedIds, completedOperations);
            }
            boolean toolsAllowed = round <= maxRounds && failedRounds <= retryBudget;

            log.info("【工具编排】第 {} 轮：调用模型（本轮{}，当前对话 {} 条消息，traceId={}）",
                    round, toolsAllowed ? "提供工具" : "不再提供工具", messages.size(), traceId);

            long modelStartNanos = System.nanoTime();
            String modelStart = Instant.now().toString();
            ModelTurn turn = roundCall.call(messages, toolsAllowed ? definitions : List.of());
            lastReasoningMs = turn.reasoningMs();

            if (signal.isCancelled()) {
                log.info("【取消】模型返回后检测到取消，丢弃本轮后续动作（traceId={}）", traceId);
                return cancelledResult(lastReasoningMs, completedWrites, failedWrites,
                        unknownWrites, notStarted(turn.toolCalls()), capturedIds, completedOperations);
            }
            traceRecorder.record(context.userId(), traceId, parentSpanId, "model.call", modelStart,
                    millisSince(modelStartNanos), "ok", Map.of(
                            "round", round,
                            "toolsOffered", toolsAllowed ? definitions.size() : 0,
                            "toolCalls", turn.toolCalls().size(),
                            "contentChars", turn.content() == null ? 0 : turn.content().length(),
                            "reasoningMs", turn.reasoningMs()));

            // REQ-AGENT-0028：单请求累计 prompt 超限时立即收尾，避免继续滚雪球
            BudgetService.Check budget = budgetService.checkRequest(traceId);
            if (budget.exceeded() && turn.hasToolCalls()) {
                log.warn("【预算】单请求超限，停止工具循环：traceId={}，已用 {}，上限 {}，第 {} 轮",
                        traceId, budget.used(), budget.limit(), round);
                traceRecorder.record(context.userId(), traceId, parentSpanId, "budget.stop", modelStart,
                        millisSince(modelStartNanos), "budget_exceeded", Map.of(
                                "used", budget.used(), "limit", budget.limit(), "round", round));
                return new AskResult(BudgetService.requestReply(budget), null, lastReasoningMs);
            }

            // 不再要工具 —— 正文就是最终答复
            if (!turn.hasToolCalls()) {
                String reply = turn.content() == null || turn.content().isBlank()
                        ? FALLBACK_REPLY : turn.content();
                // 模型在工具被收回后，有时会把「还想调用」写成伪工具文本；不能原样给用户
                if (reply.contains("DSML")) {
                    log.warn("【工具编排】模型输出了伪工具文本（DSML），已替换为兜底话术：traceId={}", traceId);
                    reply = PSEUDO_TOOL_REPLY;
                }
                log.info("【工具编排】第 {} 轮：模型没有再请求工具，直接作答，循环结束（traceId={}）", round, traceId);
                log.info("【工具编排】完成：traceId={}，userId={}，conversationId={}，共 {} 步，工具失败 {} 轮，压缩 {} 条，技能资料 {} 字（截断 {} 次），本次{}用到工具，总耗时 {} ms，回复 {} 字",
                        traceId, key.userId(), key.conversationId(), round, failedRounds, compressedCount,
                        usedSkillChars, skillTruncatedCount,
                        usedTool ? "有" : "未", millisSince(startNanos), reply.length());
                return new AskResult(reply, null, lastReasoningMs);
            }

            if (!toolsAllowed) {
                log.warn("【工具编排】已不再提供工具（轮次上限 {}、失败已 {} 轮、重试上限 {}），模型仍请求调用，兜底返回："
                                + "traceId={}，userId={}，conversationId={}",
                        maxRounds, failedRounds, maxRetries, traceId, key.userId(), key.conversationId());
                return new AskResult(FALLBACK_REPLY, null);
            }

            // 危险工具**先暂停**：整轮不执行，生成「待确认」票据，等用户确认（REQ-AGENT-0006）
            for (ToolCall call : turn.toolCalls()) {
                if (governance.allows(call.name(), context.roles()) && governance.isDangerous(call.name())) {
                    return pause(key, messages, turn, call, traceId);
                }
            }

            usedTool = true;
            log.info("【工具编排】第 {} 轮：模型请求调用 {} 个工具（traceId={}）",
                    round, turn.toolCalls().size(), traceId);
            status.accept("正在执行 " + turn.toolCalls().size() + " 个工具："
                    + turn.toolCalls().stream().map(ToolCall::name).toList());

            if (signal.isCancelled()) {
                log.info("【取消】工具执行前检测到取消，本轮工具不执行（traceId={}）", traceId);
                return cancelledResult(lastReasoningMs, completedWrites, failedWrites,
                        unknownWrites, notStarted(turn.toolCalls()), capturedIds, completedOperations);
            }

            // 写操作先记台账（started），再执行；operationId 供续跑/补偿去重（REQ-AGENT-0029）
            Map<String, String> operationIds = new LinkedHashMap<>();
            for (ToolCall call : turn.toolCalls()) {
                if (registry.isReadOnly(call.name())) {
                    continue;
                }
                String operationId = resumable.getOrDefault(
                        operationKey(call.name(), call.argumentsJson()), "op-" + UUID.randomUUID());
                operationIds.put(call.id(), operationId);
                writeJournal.record(operationId, traceId, key.userId(), key.conversationId(),
                        call.name(), call.argumentsJson(), "started", null);
            }

            // 第三 / 四 / 五步：助手请求先入列，再执行、把每条结果按 tool_call_id 回填（顺序与请求一致）
            messages.add(ContextMessage.toolCall(
                    ModelMessage.assistantToolCalls(turn.toolCalls(), turn.reasoningContent())));
            Execution execution = execute(turn.toolCalls(), round, traceId, context,
                    operationIds, signal);
            List<ToolResult> results = execution.results();
            // 执行完立刻给写操作「结账」：台账补终态，同时攒出取消报告要用的成功/失败/未知
            settleWrites(turn.toolCalls(), results, execution.executed(), operationIds, traceId, key,
                    completedWrites, failedWrites, unknownWrites, capturedIds, completedOperations);
            if (signal.isCancelled()) {
                // 取消可能落在「某个工具已跑完、下一个还没开始」之间：
                // 已执行照常报告，剩下的写步骤如实列为「未开始」（REQ-AGENT-0029 AC-2）
                List<ToolCall> remaining = turn.toolCalls().subList(execution.executed(),
                        turn.toolCalls().size());
                // 这些写操作的 started 已落盘，必须补一条终态，否则台账里永远是「进行中」
                closeSkippedWrites(remaining, operationIds, traceId, key);
                log.info("【取消】工具执行中途收到取消，停止后续步骤：traceId={}，已执行 {} 个，未执行 {} 个",
                        traceId, execution.executed(), remaining.size());
                return cancelledResult(lastReasoningMs, completedWrites, failedWrites,
                        unknownWrites, notStarted(remaining), capturedIds, completedOperations);
            }
            if (results.stream().anyMatch(r -> !r.ok())) {
                // 只要本轮有一条失败，就算一次事故；重试预算按「事故轮数」计。
                // 注：ToolResult 目前只有 ok，不再细分「无结果」，两者都算失败。
                failedRounds++;
            }
            for (int i = 0; i < turn.toolCalls().size(); i++) {
                ToolCall call = turn.toolCalls().get(i);
                ToolResult result = results.get(i);
                // 先按「单次上限」压缩/截断，再受「总量预算」约束：超预算只留一个省略标记
                String raw = result.content();
                boolean skillResource = UNCOMPRESSED_TOOLS.contains(call.name());
                String content = fit(call.name(), raw, usedResultChars, usedSkillChars);
                if (raw != null && content.length() < raw.length()) {
                    if (skillResource) {
                        skillTruncatedCount++;
                    } else {
                        compressedCount++;
                    }
                }
                if (skillResource) {
                    usedSkillChars += content.length();
                } else {
                    usedResultChars += content.length();
                }
                // 回填前包一层「数据信封」：告诉模型这是**数据不是指令**（见 REQ-AGENT-0009）
                messages.add(ContextMessage.toolResult(
                        ModelMessage.tool(call.id(), ToolResultEnvelope.wrap(content)), call.name()));

                // 这是「回填」这一步：把工具结果按 call_id 塞回对话，下一轮模型就能看到它
                log.info("【工具编排】第 {} 轮：工具「{}」{}，结果已按 call_id={} 回填（{} 字），进入下一轮",
                        round, call.name(), result.ok() ? "执行成功" : "执行失败", call.id(), content.length());
            }
        }
    }

    /** 危险操作：不执行，落一枚待确认票据，把「答复 + pending」交回上层 */
    private AskResult pause(SessionKey key, List<ContextMessage> messages, ModelTurn turn,
                            ToolCall call, String traceId) {
        // 把模型原始帧一起存档：续跑时要原样回放（含 reasoning 与原始 tool_call id）
        ModelMessage assistantFrame =
                ModelMessage.assistantToolCalls(turn.toolCalls(), turn.reasoningContent());
        PendingActionStore.PendingAction action = pendingStore.put(key.userId(), key.conversationId(),
                call.name(), call.argumentsJson(), ContextMessage.unwrap(messages), assistantFrame,
                call.id(), traceId);
        log.info("【工具编排】工具「{}」是危险操作，已暂停并生成待确认：pendingId={}，traceId={}，userId={}",
                call.name(), action.id(), traceId, key.userId());
        PendingConfirmation pending = new PendingConfirmation(action.id(), call.name(),
                call.name() + "（参数 " + SensitiveMasker.mask(call.argumentsJson()) + "）");
        return new AskResult(PENDING_HINT, pending);
    }

    /**
     * 执行本轮的多个工具调用，**返回顺序与传入一致**。
     *
     * <p>并发条件（三条全满足才并发，否则逐个）：**开了并行开关** && **不止一个工具** &&
     * **本轮全是只读工具**。有副作用的工具并发风险高，宁可慢一点、逐个来。
     *
     * <p>取消检查（`REQ-AGENT-0029`）：**每个工具执行前**都看一次信号。取消后不再开新工具，
     * 未执行的用占位补齐；返回值里的 {@code executed} 告诉调用方「真正跑到第几个」。
     */
    private Execution execute(List<ToolCall> calls, int round, String traceId, ToolContext context,
                              Map<String, String> operationIds, CancellationSignal signal) {
        boolean parallel = parallelEnabled && calls.size() > 1 && allParallelSafe(calls);
        log.info("【工具编排】第 {} 轮：{}执行 {} 个工具（traceId={}）",
                round, parallel ? "并发" : "逐个", calls.size(), traceId);
        return parallel ? runAllAtOnce(calls, round, traceId, context, operationIds, signal)
                : runOneByOne(calls, round, traceId, context, operationIds, signal);
    }

    private boolean allParallelSafe(List<ToolCall> calls) {
        for (ToolCall call : calls) {
            if (!registry.isParallelSafe(call.name())) {
                return false;
            }
        }
        return true;
    }

    private Execution runAllAtOnce(List<ToolCall> calls, int round, String traceId,
                                   ToolContext context, Map<String, String> operationIds,
                                   CancellationSignal signal) {
        List<CompletableFuture<ToolResult>> futures = new ArrayList<>(calls.size());
        List<ToolResult> results = new ArrayList<>(calls.size());
        int started = 0;
        for (ToolCall call : calls) {
            if (signal.isCancelled()) {
                break;
            }
            futures.add(submit(call, round, traceId, context, operationIds.get(call.id())));
            started++;
        }
        for (int i = 0; i < started; i++) {
            ToolCall call = calls.get(i);
            results.add(await(futures.get(i), call, round, traceId, signal,
                    registry.isReadOnly(call.name())));
        }
        addSkipped(results, calls.size() - started);
        return new Execution(results, started);
    }

    private Execution runOneByOne(List<ToolCall> calls, int round, String traceId,
                                  ToolContext context, Map<String, String> operationIds,
                                  CancellationSignal signal) {
        List<ToolResult> results = new ArrayList<>(calls.size());
        int executed = 0;
        for (ToolCall call : calls) {
            // 每个工具执行前都检查：取消是「不再继续」，不是「跑完这一轮再说」
            if (signal.isCancelled()) {
                break;
            }
            results.add(await(submit(call, round, traceId, context, operationIds.get(call.id())),
                    call, round, traceId, signal, registry.isReadOnly(call.name())));
            executed++;
        }
        addSkipped(results, calls.size() - executed);
        return new Execution(results, executed);
    }

    /** 未执行的调用补占位，保证 results 与 calls 等长（回填循环按相同下标取） */
    private static void addSkipped(List<ToolResult> results, int count) {
        for (int i = 0; i < count; i++) {
            results.add(ToolResult.failed("已取消，未执行"));
        }
    }

    /** 把一次工具调用交给执行器去跑；先打「要调哪个工具、参数是什么」，跑完再打**耗时** */
    private CompletableFuture<ToolResult> submit(ToolCall call, int round, String traceId,
                                                 ToolContext context, String operationId) {
        log.info("【工具编排】第 {} 轮：要调用的工具是「{}」（call_id={}），模型给的参数={}，operationId={}，traceId={}",
                round, call.name(), call.id(), SensitiveMasker.mask(call.argumentsJson()),
                operationId, traceId);
        // 写操作的 operationId 必须落到**本次调用自己的**上下文里：
        // 同一轮可能调多个写工具，共用一个 context 会把 A 的 id 带给 B
        ToolContext callContext = operationId == null ? context : context.withOperationId(operationId);
        return CompletableFuture.supplyAsync(() -> {
            long startNanos = System.nanoTime();
            ToolResult result = runTool(call, callContext);
            log.info("【工具编排】第 {} 轮：工具「{}」执行完毕，耗时 {} ms，traceId={}",
                    round, call.name(), millisSince(startNanos), traceId);
            return result;
        }, toolExecutor);
    }

    /**
     * 等结果，带**超时兜底**。
     *
     * <p>取消语义按读写区分（`REQ-AGENT-0029`）：
     * <ul>
     *   <li><b>读工具</b>：立即放弃等待，结果直接丢弃（读没有副作用，不会留下半成品）；</li>
     *   <li><b>写工具</b>：必须等结算，否则可能「以为没执行、其实已经创建」。</li>
     * </ul>
     * 注意 {@link CompletableFuture#cancel(boolean)} 并不会中断已在线程池里跑的任务；
     * 对读工具来说这没关系——调用继续跑完，只是结果没人要。
     */
    private ToolResult await(CompletableFuture<ToolResult> future, ToolCall call, int round,
                             String traceId, CancellationSignal signal, boolean readOnly) {
        long configured = registry.timeoutMillis(call.name());
        long timeoutMs = configured > 0 ? configured : invokeTimeout.toMillis();
        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (true) {
            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0) {
                return timeoutResult(future, call, round, traceId, timeoutMs);
            }
            long waitNanos = readOnly
                    ? Math.min(remainingNanos, TimeUnit.MILLISECONDS.toNanos(CANCEL_POLL_MILLIS))
                    : remainingNanos;
            try {
                return future.get(waitNanos, TimeUnit.NANOSECONDS);
            } catch (TimeoutException e) {
                if (System.nanoTime() >= deadlineNanos) {
                    return timeoutResult(future, call, round, traceId, timeoutMs);
                }
                if (readOnly && signal.isCancelled()) {
                    future.cancel(false);
                    log.info("【取消】读工具「{}」在途，立即放弃等待（结果丢弃，traceId={}）",
                            call.name(), traceId);
                    return ToolResult.failed("已取消，结果丢弃");
                }
                // 读工具的轮询时间到：回去再看一眼取消位
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                future.cancel(true);
                return ToolResult.failed("工具执行被中断");
            } catch (ExecutionException e) {
                Throwable cause = e.getCause() == null ? e : e.getCause();
                log.warn("【工具编排】第 {} 轮：工具「{}」执行异常：{}（traceId={}）",
                        round, call.name(), cause.getMessage(), traceId);
                return ToolResult.failed("工具执行出错（" + cause.getMessage() + "）");
            }
        }
    }

    private ToolResult timeoutResult(CompletableFuture<ToolResult> future, ToolCall call,
                                     int round, String traceId, long timeoutMs) {
        future.cancel(true);
        log.warn("【工具编排】第 {} 轮：工具「{}」超时（>{}ms），按失败回填（traceId={}）",
                round, call.name(), timeoutMs, traceId);
        return ToolResult.failed("工具执行超时（超过 " + timeoutMs + " 毫秒）");
    }

    private static long millisSince(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    /** 工具名列表，让日志一眼看出本轮把哪些工具交给了模型 */
    private static String toolNames(List<ToolDefinition> definitions) {
        if (definitions.isEmpty()) {
            return "（无）";
        }
        return "[" + String.join("、", definitions.stream().map(ToolDefinition::name).toList()) + "]";
    }

    /** 日志里最多列多少个被隐藏的工具名，超出用省略号，避免一行日志几千字符 */
    private static final int MAX_HIDDEN_IN_LOG = 20;

    /**
     * 按「角色 + 权限」裁剪本轮下发给模型的工具定义（`REQ-AGENT-0018`）。
     *
     * <p>两道门是 **AND**：
     * <ul>
     *   <li><b>角色门</b>：{@link ToolGovernance#allows}——被 {@code role-tools} 限制的工具
     *       （目前是危险工具），只有名单里的角色能看到；</li>
     *   <li><b>权限门</b>：{@link ToolVisibility}——按 {@code visible-by} 规则判断数据范围。</li>
     * </ul>
     *
     * <p>这只影响「模型看不看得到」。执行前 {@code runTool} 还会再判一次角色，
     * 即使模型被诱导去调一个看不见的工具，也过不了执行侧。
     */
    private List<ToolDefinition> visibleDefinitions(ToolContext context, String traceId) {
        List<ToolDefinition> all = registry.definitions();
        List<ToolDefinition> visible = new ArrayList<>(all.size());
        List<String> hidden = new ArrayList<>();
        int roleBlocked = 0;
        int permissionBlocked = 0;

        for (ToolDefinition definition : all) {
            boolean roleOk = governance.allows(definition.name(), context.roles());
            boolean permissionOk = visibility.allows(definition.name(), context.permissions());
            if (roleOk && permissionOk) {
                visible.add(definition);
                continue;
            }
            hidden.add(definition.name());
            if (!roleOk) {
                roleBlocked++;
            } else {
                permissionBlocked++;
            }
        }

        if (hidden.isEmpty()) {
            // 全可见是常态（例如管理员），只留 debug，避免每次请求刷屏
            log.debug("【工具裁剪】traceId={}，全部 {} 个工具可见", traceId, all.size());
        } else {
            log.info("【工具裁剪】traceId={}，可见 {}/{}：角色拦下 {}、权限拦下 {}；隐藏={}",
                    traceId, visible.size(), all.size(), roleBlocked, permissionBlocked,
                    abbreviate(hidden));
        }
        return List.copyOf(visible);
    }

    /** 隐藏名单截断，避免超长日志 */
    private static String abbreviate(List<String> names) {
        if (names.size() <= MAX_HIDDEN_IN_LOG) {
            return names.toString();
        }
        return names.subList(0, MAX_HIDDEN_IN_LOG) + "…（共 " + names.size() + " 个）";
    }

    /**
     * 按名字找到工具并执行。三件事都要转成「失败的结果」回填，**不让整条对话失败**：
     * 未授权、未知工具、执行异常。
     */
    private ToolResult runTool(ToolCall call, ToolContext context) {
        long startNanos = System.nanoTime();
        String start = Instant.now().toString();
        ToolResult result = doRunTool(call, context);
        traceRecorder.record(context.userId(), context.traceId(),
                traceRecorder.rootSpanId(context.traceId()), "tool.call", start,
                millisSince(startNanos), result.ok() ? "ok" : "error", Map.of(
                        "tool", call.name(),
                        "ok", result.ok(),
                        "argsChars", call.argumentsJson() == null ? 0 : call.argumentsJson().length(),
                        "resultChars", result.content() == null ? 0 : result.content().length()));
        return result;
    }

    private ToolResult doRunTool(ToolCall call, ToolContext context) {
        if (!governance.allows(call.name(), context.roles())) {
            log.warn("【工具编排】未授权调用工具：name={}，用户角色={}", call.name(), context.roles());
            return ToolResult.failed(String.format(UNAUTHORIZED, call.name()));
        }
        Tool tool = registry.handler(call.name());
        if (tool == null) {
            log.warn("【工具编排】模型请求了未知工具：name={}", call.name());
            return ToolResult.failed("没有名为「" + call.name() + "」的工具");
        }
        try {
            // 身份**显式传进去**：工具在执行器线程上跑，ThreadLocal 取不到（见 ToolContext）
            return tool.invoke(call.argumentsJson(), context);
        } catch (RuntimeException e) {
            log.warn("【工具编排】工具执行异常：name={}，原因={}", call.name(), e.getMessage());
            return ToolResult.failed("工具执行出错（" + e.getMessage() + "）");
        }
    }

    /**
     * 技能正文/资料**不参与通用压缩预算**，但有**自己的独立预算**（`REQ-AGENT-0019`）。
     *
     * <p>它们是模型主动加载的「说明书」与模板，体量本身就由
     * {@code agent.skill.max-body-chars} / {@code max-resource-chars} 约束；
     * 如果套 2000 字的通用预算，模板会被裁成一句占位符（实测只剩 10 字），
     * 模型只能凭正文里的版式重画——L3 就失去意义了。
     *
     * <p>但「完全豁免」也不行：单次 32KB、总量不限，一个请求反复读资源就能撑爆上下文。
     * 所以给它们单独的总量闸，并且超限时**只截断、不做模型摘要**——模板里的占位符与样式不能被改写。
     */
    private static final java.util.Set<String> UNCOMPRESSED_TOOLS =
            java.util.Set.of(UseSkillTool.NAME, ReadSkillResourceTool.NAME);

    private String fit(String toolName, String content, int usedChars, int usedSkillChars) {
        if (content == null) {
            return "";
        }
        if (!optimizeEnabled) {
            return hardTruncate(content, LEGACY_RESULT_CHARS);
        }
        if (UNCOMPRESSED_TOOLS.contains(toolName)) {
            return fitSkillResource(content, usedSkillChars);
        }
        int cap = Math.min(maxResultChars, Math.max(0, maxTotalResultChars - usedChars));
        if (cap <= 0) {
            return SKIPPED_RESULT;
        }
        return content.length() <= cap ? content : compressor.compress(content, cap);
    }

    /**
     * Skill 资源的独立预算：单次上限与累计上限取先到者。
     *
     * <p>不做模型摘要：模板一旦被改写，占位符与样式就毁了，模型再照着写也还原不出来。
     * 超限就硬截断并带标记——至少让模型知道「这不是全文」。
     */
    private String fitSkillResource(String content, int usedSkillChars) {
        int cap = Math.min(maxSkillResultChars, Math.max(0, maxTotalSkillChars - usedSkillChars));
        if (cap <= 0) {
            return SKILL_SKIPPED;
        }
        return content.length() <= cap ? content : truncateWithMarker(content, cap, SKILL_TRUNCATED);
    }

    /**
     * 截到 {@code cap} 以内，并在末尾追加标记。
     *
     * <p>若截断点正好落在 UTF-16 代理对中间（emoji 等），回退一位——
     * 否则会留下半个字符，模型侧可能显示成乱码。
     */
    static String truncateWithMarker(String content, int cap, String marker) {
        if (cap <= marker.length()) {
            // 预算比标记还小属于配置异常；宁可比预算多几个字，也要把「被截断」说清楚
            return marker;
        }
        int keep = cap - marker.length();
        if (keep > 0 && Character.isHighSurrogate(content.charAt(keep - 1))) {
            keep--;
        }
        return content.substring(0, keep) + marker;
    }

    /**
     * 写操作结算（`REQ-AGENT-0029`）：把执行结果落到台账，并攒进取消报告。
     *
     * <p>只结算**真正执行过**的调用（{@code executed} 之前的）；没轮到的留给「未开始」。
     * 失败分两类：超时 / 5xx / 网络异常算**结果未知**（可能已生效，不能重试），
     * 业务错误算**失败**（确定未生效）。
     *
     * <p>注意：{@code operations} 与 {@code captured} 都是**列表**，同一工具多次调用会各记一条；
     * 续跑时再用「工具 + 参数摘要」把每次调用对回自己的 operationId。
     */
    private void settleWrites(List<ToolCall> calls, List<ToolResult> results, int executed,
                              Map<String, String> operationIds, String traceId, SessionKey key,
                              List<CancellationReport.Step> completed,
                              List<CancellationReport.Step> failed,
                              List<CancellationReport.Step> unknown,
                              List<CancellationReport.Captured> captured,
                              List<CancellationReport.Operation> operations) {
        for (int i = 0; i < executed; i++) {
            ToolCall call = calls.get(i);
            if (registry.isReadOnly(call.name())) {
                continue;
            }
            ToolResult result = results.get(i);
            String operationId = operationIds.get(call.id());
            String status;
            String objectId = null;
            if (result.ok()) {
                status = "succeeded";
                objectId = extractObjectId(result.content());
            } else {
                status = looksUnknown(result.content()) ? "unknown" : "failed";
            }
            writeJournal.record(operationId, traceId, key.userId(), key.conversationId(),
                    call.name(), call.argumentsJson(), status, objectId);
            CancellationReport.Step step = new CancellationReport.Step(
                    operationId, call.name(), status, objectId, stepSummary(status));
            switch (status) {
                case "succeeded" -> {
                    completed.add(step);
                    operations.add(new CancellationReport.Operation(
                            call.name(), operationId, WriteJournal.digest(call.argumentsJson())));
                    if (objectId != null && !objectId.isBlank()) {
                        captured.add(new CancellationReport.Captured(
                                call.name(), objectId, operationId));
                    }
                }
                case "unknown" -> unknown.add(step);
                default -> failed.add(step);
            }
            log.info("【工具编排】写操作结算：tool={}，operationId={}，status={}，objectId={}，traceId={}",
                    call.name(), operationId, status, objectId, traceId);
        }
    }

    /** 取消后未执行的写操作：台账补一条 `cancelled`，避免「started 之后再无下文」 */
    private void closeSkippedWrites(List<ToolCall> calls, Map<String, String> operationIds,
                                    String traceId, SessionKey key) {
        for (ToolCall call : calls) {
            String operationId = operationIds.get(call.id());
            if (operationId == null) {
                continue; // 只读工具没有台账
            }
            writeJournal.record(operationId, traceId, key.userId(), key.conversationId(),
                    call.name(), call.argumentsJson(), "cancelled", null);
        }
    }

    /** 本轮执行结果：results 与 calls 等长（未执行的用占位补齐），executed 是真正跑到工具的数量 */
    private record Execution(List<ToolResult> results, int executed) {
    }

    /** 取最后一条用户消息：内置计划触发与生成计划都用它 */
    private static String lastUserMessage(List<ContextMessage> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            ModelMessage message = messages.get(i).message();
            if ("user".equals(message.role()) && message.content() != null) {
                return message.content();
            }
        }
        return "";
    }

    /** 路由到子 Agent：直接执行，不再让主模型调用委派工具。 */
    private AskResult runSubAgent(RouteDecision decision, String message, ToolContext context,
                                  String traceId,
                                  java.util.function.Consumer<String> status) {
        if (!subAgentService.enabled()) {
            return null;
        }
        String agent = decision.agent();
        status.accept("正在委派子代理：" + agent);
        SubAgentResult result = subAgentService.run(agent, message, context, traceId);
        if ("ok".equals(result.status())
                && result.summary() != null && !result.summary().isBlank()) {
            log.info("【子代理】路由完成：agent={}，轮次={}，工具调用={}，耗时 {} ms，traceId={}",
                    agent, result.rounds(), result.toolCalls(), result.elapsedMs(), traceId);
            return new AskResult(result.summary(), null);
        }
        log.warn("【子代理】路由执行未完成：agent={}，status={}，摘要={}，traceId={}",
                agent, result.status(), result.summary(), traceId);
        if ("budget_exceeded".equals(result.status())) {
            // 预算已超，不能再降级去跑主模型
            return new AskResult(result.summary(), null);
        }
        status.accept("子代理未完成，改为直接查询");
        return null;
    }

    /** 路由到 Plan：内置流程直接执行；否则生成计划并等待审批。 */
    private AskResult runPlan(RouteDecision decision, SessionKey key, String userMessage,
                              ToolContext context, String traceId, CancellationSignal signal,
                              java.util.function.Consumer<String> status) {
        if (!planService.enabled()) {
            return null;
        }
        // 用户明确“先给方案”时，即使含“日报”等内置触发词，也走生成计划 + 审批
        Plan builtin = "explicit_plan".equals(decision.reason())
                ? null : planService.matchBuiltin(userMessage).orElse(null);
        if (builtin != null) {
            log.info("【计划】路由命中内置计划：name={}，planId={}，traceId={}",
                    builtin.name(), builtin.planId(), traceId);
            status.accept("正在执行内置计划：" + builtin.name());
            return executePlan(builtin, key, userMessage, context, traceId, signal, status);
        }
        try {
            status.accept("正在生成计划...");
            PlanSummary summary = planService.generateAndStore(key.userId(), key,
                    userMessage, context, traceId);
            return new AskResult("计划已生成，请确认后执行。", null, 0, null, summary);
        } catch (RuntimeException e) {
            log.warn("【计划】路由生成失败：reason={}，原因={}", decision.reason(), e.getMessage());
            status.accept("计划生成失败，改为直接处理");
            if ("explicit_plan".equals(decision.reason())) {
                return new AskResult("生成计划失败：" + e.getMessage(), null);
            }
            return null;
        }
    }

    /** 执行一份计划：工具执行复用本类，治理、追踪、台账语义不变 */
    private AskResult executePlan(Plan plan, SessionKey key, String userMessage,
                                  ToolContext context, String traceId, CancellationSignal signal,
                                  java.util.function.Consumer<String> status) {
        PlanExecutor.Result result = planService.execute(plan, key, userMessage, context, traceId,
                signal, status, this::invokeForPlan);
        return new AskResult(result.reply(), null, 0, null, result.summary());
    }

    /** 供 PlanExecutor 复用：走同一套治理与追踪，并带上稳定 operationId */
    public ToolResult invokeForPlan(ToolCall call, ToolContext context, String operationId) {
        ToolContext callContext = operationId == null ? context : context.withOperationId(operationId);
        return runTool(call, callContext);
    }

    /**
     * 批准/拒绝一份生成计划（`REQ-AGENT-0031`）。
     *
     * <p>计划由 {@code PlanStore} 原子消费，重复批准只会执行一次。
     */
    public AskResult confirmPlan(SessionKey key, String planId, boolean approve, String traceId,
                                 ToolContext context, CancellationSignal signal) {
        Plan plan = planService.take(key.userId(), planId).orElse(null);
        if (plan == null) {
            return new AskResult("该计划已处理或已过期。", null, 0, null,
                    new PlanSummary(planId, "", "", "EXPIRED", List.of()));
        }
        if (!approve) {
            log.info("【计划】用户拒绝：planId={}，userId={}", planId, key.userId());
            return new AskResult("已拒绝该计划，未执行任何步骤。", null, 0, null,
                    PlanService.rejected(plan));
        }
        String message = plan.sourceMessage() == null ? plan.goal() : plan.sourceMessage();
        log.info("【计划】用户批准，开始执行：planId={}，userId={}", planId, key.userId());
        PlanExecutor.Result result = planService.execute(plan, key, message, context, traceId,
                signal, null, this::invokeForPlan);
        return new AskResult(result.reply(), null, 0, null, result.summary());
    }

    /** 取消报告：未开始的**写**步骤（当前轮模型请求了但还没执行；读操作没有业务副作用，不列） */
    private List<CancellationReport.Step> notStarted(List<ToolCall> calls) {
        List<CancellationReport.Step> steps = new ArrayList<>();
        for (ToolCall call : calls) {
            if (registry.isReadOnly(call.name())) {
                continue;
            }
            steps.add(new CancellationReport.Step(null, call.name(), "not_started", null, "未执行"));
        }
        return steps;
    }

    /** 构造取消结果：不自动回滚，只如实报告 */
    private static AskResult cancelledResult(long reasoningMs,
                                             List<CancellationReport.Step> completed,
                                             List<CancellationReport.Step> failed,
                                             List<CancellationReport.Step> unknown,
                                             List<CancellationReport.Step> notStarted,
                                             List<CancellationReport.Captured> captured,
                                             List<CancellationReport.Operation> operations) {
        List<String> options = new ArrayList<>();
        if (!operations.isEmpty()) {
            options.add("continue");
        }
        if (!completed.isEmpty() || !unknown.isEmpty()) {
            options.add("revoke");
        }
        options.add("keep");
        CancellationReport.ResumeToken token = operations.isEmpty() && captured.isEmpty() ? null
                : new CancellationReport.ResumeToken(operations, captured);
        return new AskResult(CANCELLED_REPLY, null, reasoningMs,
                new CancellationReport(true, completed, failed, notStarted, unknown, options, token));
    }

    /** 续跑索引：上一次完成的「工具 + 参数摘要」→ operationId（同键取第一条） */
    private static Map<String, String> resumableOperations(CancellationReport.ResumeToken token) {
        if (token == null || token.operations().isEmpty()) {
            return Map.of();
        }
        Map<String, String> index = new LinkedHashMap<>();
        for (CancellationReport.Operation operation : token.operations()) {
            index.putIfAbsent(operation.tool() + ":" + operation.argsDigest(), operation.operationId());
        }
        return index;
    }

    /** 本次调用的续跑键：同工具、参数不同 → 不同键，不会被上一条的同名调用覆盖 */
    private static String operationKey(String tool, String argumentsJson) {
        return tool + ":" + WriteJournal.digest(argumentsJson);
    }

    /** 超时/5xx/网络异常的结果文本由 MCP 层给出「结果未知」 */
    private static boolean looksUnknown(String content) {
        return content != null && (content.contains("结果未知") || content.contains("超时"));
    }

    private static String stepSummary(String status) {
        return switch (status) {
            case "succeeded" -> "已完成";
            case "unknown" -> "结果未知";
            default -> "失败";
        };
    }

    /** 从工具结果里尽力提取对象 id（`id` 或 `data.id`），取不到就留空 */
    private static String extractObjectId(String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        try {
            com.fasterxml.jackson.databind.JsonNode root =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(content);
            com.fasterxml.jackson.databind.JsonNode id = root.path("id");
            if (!id.isMissingNode() && !id.isNull()) {
                return id.asText();
            }
            com.fasterxml.jackson.databind.JsonNode data = root.path("data");
            if (data.isObject()) {
                id = data.path("id");
                if (!id.isMissingNode() && !id.isNull()) {
                    return id.asText();
                }
            }
        } catch (Exception ignored) {
            // 非 JSON 结果（删除成功一句话等）没有 id 可提取
        }
        return null;
    }

    private static String hardTruncate(String content, int maxChars) {
        return content.length() <= maxChars ? content : content.substring(0, maxChars) + "…";
    }
}
