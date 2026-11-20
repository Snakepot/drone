package cn.tedu.droneagent.controller;

import cn.tedu.droneagent.artifact.ArtifactRef;
import cn.tedu.droneagent.bill.RequestBill;
import cn.tedu.droneagent.cancel.CancellationRegistry;
import cn.tedu.droneagent.rate.RateLimiter;
import cn.tedu.droneagent.cancel.CancellationReport;
import cn.tedu.droneagent.memory.SensitiveMasker;
import cn.tedu.droneagent.tool.PendingActionStore;
import cn.tedu.droneagent.dto.CancelRequest;
import cn.tedu.droneagent.common.JsonResult;
import cn.tedu.droneagent.common.LoginUser;
import cn.tedu.droneagent.common.RequirePermission;
import cn.tedu.droneagent.common.UserContext;
import cn.tedu.droneagent.dto.ChatRequest;
import cn.tedu.droneagent.dto.ChatResponse;
import cn.tedu.droneagent.dto.ConfirmRequest;
import cn.tedu.droneagent.dto.PendingConfirmation;
import cn.tedu.droneagent.dto.SessionSummary;
import cn.tedu.droneagent.memory.LongTermMemoryStore;
import cn.tedu.droneagent.memory.MemoryAuditLog;
import cn.tedu.droneagent.memory.MemoryIndex;
import cn.tedu.droneagent.memory.SessionKey;
import cn.tedu.droneagent.memory.SessionStore;
import cn.tedu.droneagent.service.ChatConfirmService;
import cn.tedu.droneagent.service.ChatStreamListener;
import cn.tedu.droneagent.tool.ToolContext;
import cn.tedu.droneagent.service.ConversationService;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 智能助手的对话与会话管理接口。
 *
 * <h2>两处「身份从哪来」的要点</h2>
 * <ol>
 *   <li><b>userId 一律取自 Token</b>（拦截器放进 {@link UserContext}），绝不接受客户端传入。
 *       会话键是「userId + conversationId」，拿别人的会话 id 只会落到自己名下，
 *       <b>越权在结构上就不成立</b>，不需要逐个接口再判一次归属。</li>
 *   <li><b>流式路径必须在请求线程上先把 userId 取出来</b>。异步任务跑在别的线程，
 *       那里 ThreadLocal 是空的——这是本项目反复出现的同一个坑。</li>
 * </ol>
 *
 * <h2>它在链路里的位置，以及它不负责什么</h2>
 * <p><b>位置</b>：HTTP 入口。往下依次是 {@link ConversationService}（编排一次对话）、
 * {@code ContextAssembler}（组装上下文）、{@link cn.tedu.droneagent.service.ChatService}
 * （调模型）；查列表与删会话则直接落到记忆存储上。
 *
 * <ul>
 *   <li><b>不写业务逻辑</b>：本类只做「取身份 → 拼会话键 → 交出去 → 包成统一响应」；
 *       一次对话怎么走、上下文怎么组，全在下面几层。</li>
 *   <li><b>不判权限</b>：{@code @RequirePermission("agent:chat")} 交给拦截器执行，
 *       本类只声明「需要这个权限」。</li>
 *   <li><b>不直接碰文件</b>：删会话时它是**转交**给存储层（正文、索引、审计各一个调用），
 *       自己不做任何文件操作。</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/chat")
public class ChatController {

    /** 单条流的超时（毫秒）。模型出字比普通接口慢得多，给足时间 */
    private static final long STREAM_TIMEOUT_MS = 300_000L;

    /** 并发流上限。聊天是少数人用的功能，线程数不该跟着请求量无限涨 */
    private static final int STREAM_THREADS = 8;

    private final ConversationService conversations;

    private final ChatConfirmService confirmations;

    private final SessionStore sessions;

    private final LongTermMemoryStore longTermMemory;

    private final MemoryIndex index;

    private final MemoryAuditLog auditLog;

    private final ObjectMapper objectMapper;

    /** 取消注册表（REQ-AGENT-0029） */
    private final CancellationRegistry cancellations;

    /** 待确认票据（REQ-AGENT-0029）：取消时作废、重启后可查询恢复 */
    private final PendingActionStore pendingStore;

    /** 限流（REQ-AGENT-0030）：每用户 + 全局 */
    private final RateLimiter rateLimiter;

    private final ExecutorService streamExecutor;

    public ChatController(ConversationService conversations, ChatConfirmService confirmations,
                          SessionStore sessions, LongTermMemoryStore longTermMemory, MemoryIndex index,
                          MemoryAuditLog auditLog, ObjectMapper objectMapper,
                          CancellationRegistry cancellations, PendingActionStore pendingStore,
                          RateLimiter rateLimiter) {
        this.conversations = conversations;
        this.cancellations = cancellations;
        this.pendingStore = pendingStore;
        this.rateLimiter = rateLimiter;
        this.confirmations = confirmations;
        this.sessions = sessions;
        this.longTermMemory = longTermMemory;
        this.index = index;
        this.auditLog = auditLog;
        this.objectMapper = objectMapper;
        AtomicInteger sequence = new AtomicInteger();
        this.streamExecutor = Executors.newFixedThreadPool(STREAM_THREADS, runnable -> {
            Thread thread = new Thread(runnable, "chat-stream-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    @PreDestroy
    public void shutdown() {
        streamExecutor.shutdownNow();
    }

    /**
     * 取消一次在途请求（`REQ-AGENT-0029`）。
     *
     * <p>只置位取消信号，立即返回；编排层在下一个检查点停止，**不会中断已发出的写请求**。
     * 请求已完成或不存在时返回 409（业务失败仍走 HTTP 200 + state）。
     */
    @RequirePermission("agent:chat")
    @PostMapping("/cancel")
    public JsonResult<Boolean> cancel(@RequestBody CancelRequest request) {
        if (request.conversationId() == null || request.conversationId().isBlank()) {
            throw new IllegalArgumentException("conversationId 不能为空");
        }
        long userId = currentUserId();
        boolean cancelled = cancellations.cancel(userId, request.conversationId());
        // 即使请求已结束/不在途，待确认票据也可能还挂着：取消一并作废
        int voided = pendingStore.removeByConversation(userId, request.conversationId());
        if (!cancelled && voided == 0) {
            return JsonResult.fail(409, "请求已完成或不存在，无需取消");
        }
        return JsonResult.ok(true);
    }

    /**
     * 查询当前会话的有效待确认票据（REQ-AGENT-0029）。
     *
     * <p>面板重启后用它恢复确认框——票据已经落盘，但前端不一定还记得 pendingId。
     * 只返回摘要，不返回消息正文与参数原文。
     */
    @RequirePermission("agent:chat")
    @GetMapping("/pending")
    public JsonResult<List<PendingConfirmation>> pending(@RequestParam String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            throw new IllegalArgumentException("conversationId 不能为空");
        }
        long userId = currentUserId();
        List<PendingConfirmation> pending = pendingStore.listByConversation(userId, conversationId)
                .stream()
                .map(action -> new PendingConfirmation(action.id(), action.toolName(),
                        action.toolName() + "（参数 " + SensitiveMasker.mask(action.argumentsJson()) + "）"))
                .toList();
        return JsonResult.ok(pending);
    }

    /** 一次性返回完整回复（若命中危险工具，则返回「待确认」） */
    @RequirePermission("agent:chat")
    @PostMapping
    public JsonResult<ChatResponse> chat(@RequestBody ChatRequest request) {
        SessionKey key = resolveKey(request);
        String message = requireMessage(request);
        RateLimiter.Decision decision = rateLimiter.tryAcquire(key.userId());
        if (!decision.allowed()) {
            return JsonResult.fail(429, RateLimiter.message(decision.reason()));
        }
        try {
            return JsonResult.ok(conversations.askOutcome(key, message, request.resumeToken()));
        } finally {
            decision.permit().close();
        }
    }

    /**
     * 确认或拒绝一次危险操作（`REQ-AGENT-0006`）。
     *
     * <p><b>幂等</b>：票据「消费即失效」，同一个 {@code pendingId} 重复提交只会执行一次，
     * 第二次返回「已处理或已过期」。
     */
    @RequirePermission("agent:chat")
    @PostMapping("/confirm")
    public JsonResult<ChatResponse> confirm(@RequestBody ConfirmRequest request) {
        SessionKey key = resolveKey(request.conversationId());
        if (request.pendingId() == null || request.pendingId().isBlank()) {
            throw new IllegalArgumentException("pendingId 不能为空");
        }
        if (request.approve() == null) {
            throw new IllegalArgumentException("approve 不能为空");
        }
        return JsonResult.ok(confirmations.confirm(key, request.pendingId(), request.approve()));
    }

    @RequirePermission("agent:chat")
    @PostMapping("/plan/confirm")
    public JsonResult<ChatResponse> confirmPlan(@RequestBody cn.tedu.droneagent.dto.PlanConfirmRequest request) {
        SessionKey key = resolveKey(request.conversationId());
        if (request.planId() == null || request.planId().isBlank()) {
            throw new IllegalArgumentException("planId 不能为空");
        }
        if (request.approve() == null) {
            throw new IllegalArgumentException("approve 不能为空");
        }
        return JsonResult.ok(conversations.confirmPlan(key, request.planId(), request.approve()));
    }

    /** 流式返回，逐段推给前端（SSE） */
    @RequirePermission("agent:chat")
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestBody ChatRequest request) {
        // 必须在这里（请求线程上）取身份：异步线程里 ThreadLocal 是空的
        SessionKey key = resolveKey(request);
        String message = requireMessage(request);
        LoginUser user = UserContext.get();
        // 身份必须在这里构造：异步线程里 ThreadLocal 是空的，工具要靠它转发 JWT
        ToolContext toolContext = user == null ? ToolContext.anonymous()
                : ToolContext.ofUser(user.getId(), user.getRoles(), user.getPermissions(), user.getBearerToken());

        RateLimiter.Decision decision = rateLimiter.tryAcquire(key.userId());
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MS);
        if (!decision.allowed()) {
            try {
                emitter.send(SseEmitter.event().data(toJson(
                        StreamEvent.error(RateLimiter.message(decision.reason())))));
            } catch (IOException | IllegalStateException ignored) {
                // 发不出去也没关系，直接结束
            }
            emitter.complete();
            return emitter;
        }
        RateLimiter.Permit permit = decision.permit();
        // 连接断开/超时也置位取消信号（REQ-AGENT-0029）：
        // 正常完成时 ConversationService 的 finally 已经清掉了登记，这里的 cancel 是空操作；
        // 只有「客户端走了、循环还在跑」时才会真正生效，让每轮/每个工具前的检查点停下来。
        emitter.onCompletion(() -> {
            permit.close();
            cancellations.cancel(key.userId(), key.conversationId());
        });
        emitter.onError(error -> {
            permit.close();
            cancellations.cancel(key.userId(), key.conversationId());
        });
        emitter.onTimeout(() -> {
            permit.close();
            cancellations.cancel(key.userId(), key.conversationId());
        });

        streamExecutor.execute(() -> {
            AtomicBoolean open = new AtomicBoolean(true);
            ChatStreamListener listener = new ChatStreamListener() {
                @Override
                public void onReasoning(String delta) {
                    emit(StreamEvent.reasoning(delta));
                }

                @Override
                public void onContent(String delta) {
                    emit(StreamEvent.content(delta));
                }

                @Override
                public void onStatus(String text) {
                    emit(StreamEvent.status(text));
                }

                /** 发送一条事件；客户端断开时抛出 {@link ClientGone}，让整条链路尽早停下 */
                private void emit(StreamEvent event) {
                    if (!open.get()) {
                        throw new ClientGone();
                    }
                    try {
                        emitter.send(SseEmitter.event().data(toJson(event)));
                    } catch (IOException | IllegalStateException e) {
                        open.set(false);
                        // 写失败是服务端能感知「客户端已断开」的唯一时机：置位取消，
                        // 让还在执行的工具循环在下一个检查点停下（REQ-AGENT-0029）
                        cancellations.cancel(key.userId(), key.conversationId());
                        log.info("【取消】客户端已断开，置位取消信号：conversationId={}",
                                key.conversationId());
                        throw new ClientGone();
                    }
                }
            };

            try {
                ConversationService.ChatStreamOutcome outcome =
                        conversations.streamAsk(key, message, toolContext, listener, request.resumeToken());
                if (open.get()) {
                    emitter.send(SseEmitter.event().data(toJson(StreamEvent.done(outcome))));
                }
                emitter.complete();
            } catch (ClientGone e) {
                // 客户端已经走了，不需要再发任何事件
                emitter.complete();
            } catch (Exception e) {
                // 流已经开始就不能再改 HTTP 状态码了，只能把原因作为一条事件发出去
                log.warn("流式对话失败：{}", e.getMessage());
                if (open.get()) {
                    try {
                        emitter.send(SseEmitter.event().data(toJson(StreamEvent.error(e.getMessage()))));
                    } catch (IOException | IllegalStateException ignored) {
                        // 连接已经断了，发不出去也没关系
                    }
                }
                emitter.complete();
            }
        });

        return emitter;
    }

    /**
     * 我自己的会话列表。
     *
     * <p>只读该用户的索引文件，**不读任何正文**；按归档顺序倒序返回。
     *
     * <p>注意：**还没归档过的会话不会出现在这里**（新开的会话要等它被淘汰或停机刷盘后才入索引）。
     * 这是当前实现的已知边界，不是越权问题——列表里出现的永远只有自己的。
     */
    @RequirePermission("agent:chat")
    @GetMapping("/sessions")
    public JsonResult<List<SessionSummary>> listSessions() {
        long userId = currentUserId();
        List<SessionSummary> summaries = index.list(userId).stream()
                .map(entry -> new SessionSummary(entry.conversationId(), entry.title(),
                        entry.startedAt(), entry.messageCount()))
                .toList();
        return JsonResult.ok(summaries);
    }

    /**
     * 删除一个会话：内存、正文、索引三处一起清。
     *
     * <p><b>先写审计再删。</b>审计写不下去就中止——删除是唯一让内容消失的动作，
     * 它不该成为一次没有留痕的操作。
     *
     * <p>幂等：删除不存在的会话同样返回成功（重复点删除、或换台设备再删，都不该报错）。
     */
    @RequirePermission("agent:chat")
    @DeleteMapping("/sessions/{conversationId}")
    public JsonResult<Void> deleteSession(@PathVariable String conversationId) {
        long userId = currentUserId();
        SessionKey key = new SessionKey(userId, conversationId);

        auditLog.recordDelete(userId, conversationId);

        boolean removedFromMemory = sessions.remove(key);
        boolean removedFromDisk = longTermMemory.delete(userId, conversationId);
        index.remove(userId, conversationId);

        log.info("删除会话：userId={}，conv={}，内存中={}，磁盘上={}",
                userId, conversationId, removedFromMemory, removedFromDisk);
        return JsonResult.<Void>ok(null);
    }

    private SessionKey resolveKey(ChatRequest request) {
        return resolveKey(request.conversationId());
    }

    /** 会话键一律从「Token 里的 userId + 请求里的 conversationId」拼，绝不接受客户端传 userId */
    private static SessionKey resolveKey(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            throw new IllegalArgumentException("conversationId 不能为空");
        }
        return new SessionKey(currentUserId(), conversationId);
    }

    private static String requireMessage(ChatRequest request) {
        if (request.message() == null || request.message().isBlank()) {
            throw new IllegalArgumentException("message 不能为空");
        }
        return request.message();
    }

    /** 登录人由拦截器放进 ThreadLocal。取不到说明拦截器没生效，属于配置错误，不该静默继续 */
    private static long currentUserId() {
        LoginUser user = UserContext.get();
        if (user == null || user.getId() == null) {
            throw new IllegalStateException("拿不到当前登录人，请检查鉴权拦截器是否生效");
        }
        return user.getId();
    }

    private String toJson(StreamEvent payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (IOException e) {
            throw new IllegalStateException("序列化流式负载失败", e);
        }
    }

    /**
     * 一个流式事件的负载。二选一：正常增量走 delta，出错走 error。
     */
    /** 客户端断开：沿调用链向上抛，编排层收到后停止读模型流 */
    private static final class ClientGone extends RuntimeException {
        private ClientGone() {
            super(null, null, false, false);
        }
    }

    /**
     * SSE 事件：按 {@code type} 区分，未用到的字段为 null（序列化时省略）。
     *
     * <pre>
     * reasoning {delta}
     * content   {delta}
     * status    {text}
     * done      {reply, reasoningMs, pending?, artifacts?}
     * error     {message}
     * </pre>
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record StreamEvent(String type, String delta, String text, String reply,
                               Long reasoningMs, PendingConfirmation pending,
                               List<ArtifactRef> artifacts, RequestBill bill,
                               CancellationReport report, String message,
                               cn.tedu.droneagent.plan.PlanSummary plan) {

        static StreamEvent reasoning(String delta) {
            return new StreamEvent("reasoning", delta, null, null, null, null, null, null, null,
                    null, null);
        }

        static StreamEvent content(String delta) {
            return new StreamEvent("content", delta, null, null, null, null, null, null, null,
                    null, null);
        }

        static StreamEvent status(String text) {
            return new StreamEvent("status", null, text, null, null, null, null, null, null,
                    null, null);
        }

        static StreamEvent done(ConversationService.ChatStreamOutcome outcome) {
            return new StreamEvent("done", null, null, outcome.reply(), outcome.reasoningMs(),
                    outcome.pending(), outcome.artifacts(), outcome.bill(), outcome.report(), null,
                    outcome.plan());
        }

        static StreamEvent error(String message) {
            return new StreamEvent("error", null, null, null, null, null, null, null, null,
                    message, null);
        }
    }
}
