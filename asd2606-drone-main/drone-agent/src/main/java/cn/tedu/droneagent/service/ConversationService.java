package cn.tedu.droneagent.service;

import cn.tedu.droneagent.common.LoginUser;
import cn.tedu.droneagent.common.TraceIds;
import cn.tedu.droneagent.common.UserContext;
import cn.tedu.droneagent.config.ToolProperties;
import cn.tedu.droneagent.artifact.ArtifactRef;
import cn.tedu.droneagent.bill.BillStore;
import cn.tedu.droneagent.budget.BudgetService;
import cn.tedu.droneagent.cancel.CancellationRegistry;
import cn.tedu.droneagent.cancel.CancellationReport;
import cn.tedu.droneagent.cancel.CompensationPlanner;
import cn.tedu.droneagent.cancel.CancellationSignal;
import cn.tedu.droneagent.bill.RequestBill;
import cn.tedu.droneagent.artifact.ArtifactService;
import cn.tedu.droneagent.context.ContextMessage;
import cn.tedu.droneagent.context.MessageKind;
import cn.tedu.droneagent.dto.ChatMessage;
import cn.tedu.droneagent.dto.ChatResponse;
import cn.tedu.droneagent.dto.PendingConfirmation;
import cn.tedu.droneagent.memory.ContextAssembler;
import cn.tedu.droneagent.memory.SessionBusyException;
import cn.tedu.droneagent.memory.SessionKey;
import cn.tedu.droneagent.memory.SessionLocks;
import cn.tedu.droneagent.memory.SessionStore;
import cn.tedu.droneagent.tool.ModelTurn;
import cn.tedu.droneagent.tool.ToolContext;
import cn.tedu.droneagent.trace.TraceRecorder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 一次对话的完整流程：**写用户消息 → 组装上下文 → 调模型 → 写助手回复**。
 *
 * <h2>为什么上下文要在写完用户消息之后才组装</h2>
 * 组装的结果里要包含用户刚说的这句话。先组装就会漏掉它，
 * 模型会表现出「没听见你刚说的」。
 *
 * <h2>为什么整段流程要独占该会话</h2>
 * 见 {@link SessionLocks}：两条并发消息会各自读到半截历史、互相覆盖写回。
 * 这里把「读—调—写」整段包在一次独占里，代价是同一会话的第二条要等，
 * 所以等待超时后直接告诉调用方「正忙」，而不是把线程无限挂住。
 *
 * <h2>它不负责什么</h2>
 * <ul>
 *   <li><b>不管模型怎么调</b>：请求体怎么拼、怎么读流是 {@link ChatService} 的事；
 *       要不要触发工具调用、怎么循环是 {@link ToolCallingService} 的事。</li>
 *   <li><b>不管上下文带哪些内容</b>：会话内取几条、要不要注入长期记忆，
 *       是 {@link ContextAssembler} 的事。</li>
 *   <li><b>不管落盘</b>：会话被淘汰或过期时的归档由记忆子系统自己在后台做；
 *       本类写完内存就返回，不等落盘完成。</li>
 *   <li><b>不做鉴权</b>：能调到这里说明权限已经过了（见 {@code AuthInterceptor}）。</li>
 * </ul>
 */
@Slf4j
@Service
public class ConversationService {

    private static final String USER_ROLE = "user";

    private static final String ASSISTANT_ROLE = "assistant";

    private final SessionStore sessions;

    private final ContextAssembler contextAssembler;

    /** 仅流式路径用：{@link #streamAsk} 直接读模型流，不经工具编排 */
    private final ChatService chatService;

    /** 非流式路径用：{@link #ask} 走它，可能触发工具调用 */
    private final ToolCallingService toolCallingService;

    /** 工具调用总开关：关掉时 {@link #ask} 退回「一次性对话」 */
    private final ToolProperties toolProperties;

    private final SessionLocks locks;

    /** 等锁的超时。测试会传一个很短的值，免得为验证「正忙」真等 30 秒 */
    private final Duration lockWait;

    /** 把回复里的 HTML 抽成可下载产物；测试可用 noop */
    private final ArtifactService artifactService;

    /** 账单存储（`REQ-AGENT-0025`）：流式请求结束时取本次摘要，随 done 下发 */
    private final BillStore billStore;

    /** 链路追踪（`REQ-AGENT-0026`）；测试用 noop */
    private final TraceRecorder traceRecorder;

    /** 预算执行（`REQ-AGENT-0028`）：入口检查日额度；测试用 noop */
    private final BudgetService budgetService;

    /** 取消注册表（`REQ-AGENT-0029`）：必须与 Controller 共用同一个实例，否则取消置位编排层看不到 */
    private final CancellationRegistry cancellationRegistry;

    /**
     * 显式标注 {@code @Autowired}：本类有两个构造方法，不标的话 Spring 无法判断用哪个，
     * 会报「No default constructor found」。短超时那个仅供测试使用。
     */
    public ConversationService(SessionStore sessions, ContextAssembler contextAssembler,
                               ChatService chatService, ToolCallingService toolCallingService,
                               ToolProperties toolProperties, SessionLocks locks,
                               ArtifactService artifactService, BillStore billStore) {
        this(sessions, contextAssembler, chatService, toolCallingService, toolProperties, locks,
                SessionLocks.DEFAULT_WAIT, artifactService, billStore, TraceRecorder.noop(),
                BudgetService.noop(), new CancellationRegistry());
    }

    /** 兼容旧装配：不带预算 */
    public ConversationService(SessionStore sessions, ContextAssembler contextAssembler,
                               ChatService chatService, ToolCallingService toolCallingService,
                               ToolProperties toolProperties, SessionLocks locks,
                               ArtifactService artifactService, BillStore billStore,
                               TraceRecorder traceRecorder) {
        this(sessions, contextAssembler, chatService, toolCallingService, toolProperties, locks,
                SessionLocks.DEFAULT_WAIT, artifactService, billStore, traceRecorder,
                BudgetService.noop(), new CancellationRegistry());
    }

    @Autowired
    public ConversationService(SessionStore sessions, ContextAssembler contextAssembler,
                               ChatService chatService, ToolCallingService toolCallingService,
                               ToolProperties toolProperties, SessionLocks locks,
                               ArtifactService artifactService, BillStore billStore,
                               TraceRecorder traceRecorder, BudgetService budgetService,
                               CancellationRegistry cancellationRegistry) {
        this(sessions, contextAssembler, chatService, toolCallingService, toolProperties, locks,
                SessionLocks.DEFAULT_WAIT, artifactService, billStore, traceRecorder, budgetService,
                cancellationRegistry);
    }

    /** 测试用：不接产物 */
    ConversationService(SessionStore sessions, ContextAssembler contextAssembler,
                        ChatService chatService, ToolCallingService toolCallingService,
                        ToolProperties toolProperties, SessionLocks locks, Duration lockWait) {
        this(sessions, contextAssembler, chatService, toolCallingService, toolProperties, locks,
                lockWait, ArtifactService.noop(), BillStore.noop(), TraceRecorder.noop(),
                BudgetService.noop(), new CancellationRegistry());
    }

    /** 测试用：接产物、不接账单 */
    ConversationService(SessionStore sessions, ContextAssembler contextAssembler,
                        ChatService chatService, ToolCallingService toolCallingService,
                        ToolProperties toolProperties, SessionLocks locks, Duration lockWait,
                        ArtifactService artifactService) {
        this(sessions, contextAssembler, chatService, toolCallingService, toolProperties, locks,
                lockWait, artifactService, BillStore.noop(), TraceRecorder.noop(),
                BudgetService.noop(), new CancellationRegistry());
    }

    ConversationService(SessionStore sessions, ContextAssembler contextAssembler,
                        ChatService chatService, ToolCallingService toolCallingService,
                        ToolProperties toolProperties, SessionLocks locks, Duration lockWait,
                        ArtifactService artifactService, BillStore billStore,
                        TraceRecorder traceRecorder, BudgetService budgetService,
                        CancellationRegistry cancellationRegistry) {
        this.sessions = sessions;
        this.contextAssembler = contextAssembler;
        this.chatService = chatService;
        this.toolCallingService = toolCallingService;
        this.toolProperties = toolProperties;
        this.locks = locks;
        this.lockWait = lockWait;
        this.artifactService = artifactService;
        this.billStore = billStore;
        this.traceRecorder = traceRecorder == null ? TraceRecorder.noop() : traceRecorder;
        this.budgetService = budgetService == null ? BudgetService.noop() : budgetService;
        this.cancellationRegistry = cancellationRegistry == null
                ? new CancellationRegistry() : cancellationRegistry;
    }

    /** 只要答复正文的便捷版（供测试与不需要「待确认」的场景） */
    public String ask(SessionKey key, String message) {
        return askOutcome(key, message).reply();
    }

    /**
     * 问一句，返回「答复或待确认」。
     *
     * <p>四步：写用户消息 → 组装上下文 → 走工具编排 → 写助手回复。整段独占该会话。
     *
     * <p><b>只把最终答复写回会话</b>：「待确认」那一路还没执行，暂不入会话；确认后续跑时再补。
     *
     * @param key     会话标识（用户 + 会话）
     * @param message 用户这一轮说的话
     * @throws SessionBusyException  同一会话上已有请求在处理，且等待超时
     * @throws IllegalStateException 未配置 API-KEY、模型不可用或返回非 200
     */
    public ChatResponse askOutcome(SessionKey key, String message) {
        return askOutcome(key, message, null);
    }

    /** 带续跑令牌的请求（REQ-AGENT-0029） */
    public ChatResponse askOutcome(SessionKey key, String message,
                                   CancellationReport.ResumeToken resumeToken) {
        // 一次 /chat 一个 traceId：本请求往下（编排、模型、工具）的所有日志都带它，便于串起一条链路
        String traceId = TraceIds.newId();
        long startNanos = System.nanoTime();
        log.info("【对话】收到请求：traceId={}，userId={}，conversationId={}，消息长度={}",
                traceId, key.userId(), key.conversationId(), message.length());
        acquire(key);
        long traceStartNanos = System.nanoTime();
        String traceStart = Instant.now().toString();
        String rootSpanId = traceRecorder.beginRoot(key.userId(), traceId);
        CancellationSignal signal = cancellationRegistry.register(key.userId(), key.conversationId(), traceId);
        try {
            BudgetService.Check budget = budgetService.checkDaily(key.userId());
            if (budget.exceeded()) {
                log.warn("【预算】拒绝请求（日额度）：userId={}，conversationId={}，已用 {}，上限 {}",
                        key.userId(), key.conversationId(), budget.used(), budget.limit());
                traceRecorder.finishRoot(key.userId(), traceId, rootSpanId, traceStart,
                        (System.nanoTime() - traceStartNanos) / 1_000_000, "budget_exceeded",
                        java.util.Map.of("scope", "daily", "used", budget.used(),
                                "limit", budget.limit()));
                return new ChatResponse(BudgetService.dailyReply(budget), null, List.of(), traceId);
            }

            // 第一步：把用户这句话写进会话。
            // 必须写在组装之前 —— 上下文里要包含刚问的这句，否则模型像没听见
            sessions.append(key, new ChatMessage(USER_ROLE, message));

            // 第二步：组装发给模型的上下文（会话内最近若干条 + 该用户其他会话的长期记忆）
            List<ContextMessage> context = withResume(
                    contextAssembler.assemble(key), resumeToken, message.contains("撤销"));

            // 第三步：默认走工具调用编排。角色必须在**请求线程上**取好传下去 ——
            // 工具是在执行器线程上跑的，那里 ThreadLocal 取不到（本项目反复踩过的坑）。
            // 总开关关掉时退回「一次性对话」，行为与本需求之前完全一致
            ToolContext toolContext = currentToolContext(traceId);
            ToolCallingService.AskResult result;
            if (toolProperties.enabled()) {
                result = toolCallingService.askOutcome(key, context, traceId, toolContext, signal,
                        resumeToken);
            } else if (signal.isCancelled()) {
                result = new ToolCallingService.AskResult(ToolCallingService.CANCELLED_REPLY, null);
            } else {
                long modelStartNanos = System.nanoTime();
                String modelStart = Instant.now().toString();
                String reply = chatService.chat(key, context, toolContext.permissions(), traceId);
                traceRecorder.record(key.userId(), traceId, rootSpanId, "model.call", modelStart,
                        (System.nanoTime() - modelStartNanos) / 1_000_000, "ok",
                        Map.of("mode", "single", "contentChars", reply.length()));
                result = new ToolCallingService.AskResult(reply, null);
            }

            // 第四步：把回复里的 HTML 抽成可下载产物。
            // 注意：写回会话的是**清洗后的回复**（不含 HTML 源码），
            // 否则整份十几 KB 的报告会长期占据上下文，后续每轮重复计费。
            long artifactStartNanos = System.nanoTime();
            String artifactStart = Instant.now().toString();
            ArtifactService.Capture capture =
                    artifactService.capture(key.userId(), key.conversationId(), result.reply());
            traceRecorder.record(key.userId(), traceId, rootSpanId, "artifact.save", artifactStart,
                    (System.nanoTime() - artifactStartNanos) / 1_000_000, "ok",
                    Map.of("artifacts", capture.artifacts().size(), "replyChars", capture.reply().length()));
            sessions.append(key, new ChatMessage(ASSISTANT_ROLE, capture.reply()));

            long elapsedMs = (System.nanoTime() - traceStartNanos) / 1_000_000;
            traceRecorder.finishRoot(key.userId(), traceId, rootSpanId, traceStart, elapsedMs, "ok",
                    Map.of("conversationId", key.conversationId(),
                            "contextMessages", context.size(),
                            "replyChars", capture.reply().length(),
                            "artifacts", capture.artifacts().size(),
                            "pending", result.pending() != null));
            log.info("【对话】完成：traceId={}，userId={}，conversationId={}，上下文={}条，耗时 {} ms，回复长度={}，待确认={}",
                    traceId, key.userId(), key.conversationId(), context.size(),
                    (System.nanoTime() - startNanos) / 1_000_000, result.reply().length(),
                    result.pending() != null);
            return new ChatResponse(capture.reply(), result.pending(), capture.artifacts(), traceId,
                    result.plan());
        } catch (RuntimeException e) {
            // 失败也要留下「出去」的那一条：日志里只有「收到请求」没有「完成」，
            // 读的人分不清是还在跑、还是早就挂了
            String error = e.getMessage() == null ? "" : e.getMessage();
            if (error.length() > 200) {
                error = error.substring(0, 200);
            }
            traceRecorder.finishRoot(key.userId(), traceId, rootSpanId, traceStart,
                    (System.nanoTime() - traceStartNanos) / 1_000_000, "error",
                    Map.of("conversationId", key.conversationId(), "error", error));
            log.warn("【对话】失败：traceId={}，userId={}，conversationId={}，原因={}",
                    traceId, key.userId(), key.conversationId(), e.getMessage());
            throw e;
        } finally {
            cancellationRegistry.clear(key.userId(), key.conversationId());
            locks.release(key);
        }
    }

    /**
     * 批准/拒绝一份生成计划（`REQ-AGENT-0031`）。
     *
     * <p>批准后立即执行；拒绝只作废计划。两条路径都会把助手的答复写回会话。
     */
    public ChatResponse confirmPlan(SessionKey key, String planId, boolean approve) {
        String traceId = TraceIds.newId();
        long startNanos = System.nanoTime();
        log.info("【计划】收到审批：traceId={}，userId={}，conversationId={}，planId={}，approve={}",
                traceId, key.userId(), key.conversationId(), planId, approve);
        acquire(key);
        long traceStartNanos = System.nanoTime();
        String traceStart = Instant.now().toString();
        String rootSpanId = traceRecorder.beginRoot(key.userId(), traceId);
        CancellationSignal signal = cancellationRegistry.register(key.userId(), key.conversationId(), traceId);
        try {
            BudgetService.Check budget = budgetService.checkDaily(key.userId());
            if (budget.exceeded()) {
                log.warn("【计划】拒绝审批（日额度）：userId={}，已用 {}，上限 {}",
                        key.userId(), budget.used(), budget.limit());
                return new ChatResponse(BudgetService.dailyReply(budget), null, List.of(), traceId);
            }
            ToolContext toolContext = currentToolContext(traceId);
            ToolCallingService.AskResult result = toolCallingService.confirmPlan(
                    key, planId, approve, traceId, toolContext, signal);

            ArtifactService.Capture capture =
                    artifactService.capture(key.userId(), key.conversationId(), result.reply());
            if (!capture.reply().isEmpty()) {
                sessions.append(key, new ChatMessage(ASSISTANT_ROLE, capture.reply()));
            }
            traceRecorder.finishRoot(key.userId(), traceId, rootSpanId, traceStart,
                    (System.nanoTime() - traceStartNanos) / 1_000_000, "ok",
                    Map.of("conversationId", key.conversationId(),
                            "planId", planId, "approve", approve,
                            "replyChars", capture.reply().length()));
            log.info("【计划】审批完成：traceId={}，planId={}，耗时 {} ms，回复 {} 字",
                    traceId, planId, (System.nanoTime() - startNanos) / 1_000_000,
                    capture.reply().length());
            return new ChatResponse(capture.reply(), null, capture.artifacts(), traceId,
                    result.plan());
        } finally {
            cancellationRegistry.clear(key.userId(), key.conversationId());
            locks.release(key);
        }
    }

    /**
     * 调用方身份（角色 + 权限码），在**请求线程**上取好再传下去。
     *
     * <p>工具是在**执行器线程**上跑的，那里 {@link UserContext}（ThreadLocal）是空的——
     * 角色与权限码必须在请求线程取好、**显式传参**，否则「工具按身份收窄数据」就无从谈起。
     */
    private static ToolContext currentToolContext(String traceId) {
        LoginUser user = UserContext.get();
        return user == null ? ToolContext.anonymous().withTraceId(traceId)
                : ToolContext.ofUser(user.getId(), user.getRoles(), user.getPermissions(), user.getBearerToken())
                        .withTraceId(traceId);
    }

    /**
     * 一次流式对话的结果。
     *
     * @param reply       最终回复（已去掉被抽取的 HTML）
     * @param reasoningMs 最后一轮的思考用时（请求发出 → 首个正文增量）
     * @param pending     命中危险工具时的待确认
     * @param artifacts   本次生成的 HTML 产物
     */
    public record ChatStreamOutcome(String reply, long reasoningMs,
                                    PendingConfirmation pending, List<ArtifactRef> artifacts,
                                    RequestBill bill, CancellationReport report,
                                    cn.tedu.droneagent.plan.PlanSummary plan) {
        public ChatStreamOutcome {
            artifacts = artifacts == null ? List.of() : List.copyOf(artifacts);
        }

        /** 兼容旧调用：没有账单、没有取消报告、没有计划 */
        public ChatStreamOutcome(String reply, long reasoningMs, PendingConfirmation pending,
                                 List<ArtifactRef> artifacts, RequestBill bill) {
            this(reply, reasoningMs, pending, artifacts, bill, null, null);
        }

        /** 兼容旧调用：没有计划 */
        public ChatStreamOutcome(String reply, long reasoningMs, PendingConfirmation pending,
                                 List<ArtifactRef> artifacts, RequestBill bill,
                                 CancellationReport report) {
            this(reply, reasoningMs, pending, artifacts, bill, report, null);
        }

        public ChatStreamOutcome(String reply, long reasoningMs, PendingConfirmation pending,
                                 List<ArtifactRef> artifacts) {
            this(reply, reasoningMs, pending, artifacts, null, null, null);
        }
    }

    /**
     * 问一句，**边生成边回调**。
     *
     * <p>与 {@link #askOutcome} 的区别只在「怎么调模型」：这里是流式，
     * 思考与正文增量通过 {@link ChatStreamListener} 实时交给上层；编排、治理、
     * 产物抽取与持久化**完全复用**同一套。
     *
     * <p>客户端中途断开时，**已经生成的那部分仍会记进会话**——那是真实的模型输出，
     * 留着对后续追问有用。
     *
     * @param toolContext 调用方身份；**必须由 Controller 在请求线程上构建**——
     *                    异步线程里 ThreadLocal 已被清理，工具要靠它把 JWT 转发给下游
     * @param listener    增量回调；传 null 表示不展示
     */
    public ChatStreamOutcome streamAsk(SessionKey key, String message, ToolContext toolContext,
                                       ChatStreamListener listener) {
        return streamAsk(key, message, toolContext, listener, null);
    }

    /** 带续跑令牌的流式请求（REQ-AGENT-0029） */
    public ChatStreamOutcome streamAsk(SessionKey key, String message, ToolContext toolContext,
                                       ChatStreamListener listener,
                                       CancellationReport.ResumeToken resumeToken) {
        java.util.Set<String> permissions = toolContext.permissions();
        String traceId = TraceIds.newId();
        log.info("【对话·流式】收到请求：traceId={}，userId={}，conversationId={}，消息长度={}，权限 {} 项",
                traceId, key.userId(), key.conversationId(), message.length(), permissions.size());
        acquire(key);
        long traceStartNanos = System.nanoTime();
        String traceStart = Instant.now().toString();
        String rootSpanId = traceRecorder.beginRoot(key.userId(), traceId);
        CancellationSignal signal = cancellationRegistry.register(key.userId(), key.conversationId(), traceId);
        BudgetService.Check budget = budgetService.checkDaily(key.userId());
        if (budget.exceeded()) {
            log.warn("【预算】拒绝请求（日额度）：userId={}，conversationId={}，已用 {}，上限 {}",
                    key.userId(), key.conversationId(), budget.used(), budget.limit());
            traceRecorder.finishRoot(key.userId(), traceId, rootSpanId, traceStart,
                    (System.nanoTime() - traceStartNanos) / 1_000_000, "budget_exceeded",
                    java.util.Map.of("scope", "daily", "used", budget.used(), "limit", budget.limit()));
            return new ChatStreamOutcome(BudgetService.dailyReply(budget), 0, null, List.of(), null);
        }
        StringBuilder partial = new StringBuilder();
        // 包一层：先攒一份已生成的正文，客户端中途断开时仍能写回会话
        ChatStreamListener sink = new ChatStreamListener() {
            @Override
            public void onReasoning(String delta) {
                if (listener != null) {
                    listener.onReasoning(delta);
                }
            }

            @Override
            public void onContent(String delta) {
                partial.append(delta);
                if (listener != null) {
                    listener.onContent(delta);
                }
            }

            @Override
            public void onStatus(String text) {
                if (listener != null) {
                    listener.onStatus(text);
                }
            }
        };
        try {
            // 第一步：写用户消息（与一次性调用同理，必须在组装之前）
            sessions.append(key, new ChatMessage(USER_ROLE, message));

            // 第二步：组装上下文（身份由调用方传进来）
            List<ContextMessage> context = withResume(
                    contextAssembler.assemble(key), resumeToken, message.contains("撤销"));

            // 第三步：流式跑完整个工具循环（或纯对话）
            // traceId 在这里才生成，补进身份对象后再交给执行器线程（REQ-AGENT-0026）
            ToolContext traceContext = toolContext.withTraceId(traceId);
            ToolCallingService.AskResult result;
            if (toolProperties.enabled()) {
                result = toolCallingService.askOutcomeStreaming(key, context, traceId, traceContext,
                        sink, signal, resumeToken);
            } else if (signal.isCancelled()) {
                result = new ToolCallingService.AskResult(ToolCallingService.CANCELLED_REPLY, null);
            } else {
                ModelTurn turn = chatService.streamStep(key, context, List.of(), traceId,
                        permissions, sink);
                result = new ToolCallingService.AskResult(turn.content(), null, turn.reasoningMs());
            }

            // 第四步：与 /chat 一致——先抽产物，再持久化**清洗后的回复**
            long artifactStartNanos = System.nanoTime();
            String artifactStart = Instant.now().toString();
            ArtifactService.Capture capture =
                    artifactService.capture(key.userId(), key.conversationId(), result.reply());
            traceRecorder.record(key.userId(), traceId, rootSpanId, "artifact.save", artifactStart,
                    (System.nanoTime() - artifactStartNanos) / 1_000_000, "ok",
                    Map.of("artifacts", capture.artifacts().size(), "replyChars", capture.reply().length()));
            if (!capture.reply().isEmpty()) {
                sessions.append(key, new ChatMessage(ASSISTANT_ROLE, capture.reply()));
            }

            traceRecorder.finishRoot(key.userId(), traceId, rootSpanId, traceStart,
                    (System.nanoTime() - traceStartNanos) / 1_000_000, "ok",
                    Map.of("conversationId", key.conversationId(),
                            "contextMessages", context.size(),
                            "replyChars", capture.reply().length(),
                            "artifacts", capture.artifacts().size(),
                            "reasoningMs", result.reasoningMs(),
                            "pending", result.pending() != null));
            log.info("【对话·流式】完成：traceId={}，userId={}，conversationId={}，"
                            + "回复 {} 字，思考 {} ms，产物 {} 个",
                    traceId, key.userId(), key.conversationId(), capture.reply().length(),
                    result.reasoningMs(), capture.artifacts().size());
            return new ChatStreamOutcome(capture.reply(), result.reasoningMs(),
                    result.pending(), capture.artifacts(), billStore.summary(traceId), result.report(),
                    result.plan());
        } catch (RuntimeException e) {
            String error = e.getMessage() == null ? "" : e.getMessage();
            if (error.length() > 200) {
                error = error.substring(0, 200);
            }
            traceRecorder.finishRoot(key.userId(), traceId, rootSpanId, traceStart,
                    (System.nanoTime() - traceStartNanos) / 1_000_000, "error",
                    Map.of("conversationId", key.conversationId(),
                            "partialChars", partial.length(), "error", error));
            // 客户端断开或流中断：已经生成的部分是真实输出，留下来对后续追问有用
            if (!partial.isEmpty()) {
                sessions.append(key, new ChatMessage(ASSISTANT_ROLE, partial.toString()));
                log.warn("【对话·流式】中断，已保存已生成的 {} 字：traceId={}，userId={}，原因={}",
                        partial.length(), traceId, key.userId(), e.getMessage());
            } else {
                log.warn("【对话·流式】失败：traceId={}，userId={}，conversationId={}，原因={}",
                        traceId, key.userId(), key.conversationId(), e.getMessage());
            }
            throw e;
        } finally {
            cancellationRegistry.clear(key.userId(), key.conversationId());
            locks.release(key);
        }
    }

    /**
     * 续跑指令注入（REQ-AGENT-0029）：把已完成的写操作与对象 ID 告诉模型，
     * 避免它重复创建；撤销时也据此先查询再改。
     */
    private static List<ContextMessage> withResume(List<ContextMessage> context,
                                                   CancellationReport.ResumeToken token,
                                                   boolean revoke) {
        if (token == null || token.captured().isEmpty()) {
            return context;
        }
        StringBuilder text = new StringBuilder("【续跑】此前已完成的写操作：");
        token.captured().forEach(step ->
                text.append(step.tool()).append("（ID=").append(step.objectId()).append("）、"));
        text.append("。请基于已有结果继续剩余步骤；已完成的写操作不要重复创建，直接使用已有 ID。");
        if (revoke) {
            text.append("\n【撤销计划（逆序，先查询确认，再执行；危险操作需用户确认）】");
            for (CompensationPlanner.Action action : CompensationPlanner.plan(token.captured())) {
                text.append("\n- ").append(action.sourceTool()).append("：");
                if (action.tool() == null) {
                    text.append(action.note());
                } else {
                    text.append("调用 ").append(action.tool())
                            .append("，参数 ").append(action.argsJson())
                            .append("（").append(action.note()).append("）");
                }
            }
        }
        List<ContextMessage> withResume = new java.util.ArrayList<>(context.size() + 1);
        withResume.add(ContextMessage.of(new ChatMessage("system", text.toString()),
                MessageKind.RESUME));
        withResume.addAll(context);
        return withResume;
    }

    private void acquire(SessionKey key) {
        try {
            if (!locks.tryAcquire(key, lockWait)) {
                throw new SessionBusyException("该会话正在处理上一条消息，请稍后再发");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SessionBusyException("等待会话空闲时被中断，请重试");
        }
    }
}
