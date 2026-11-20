package cn.tedu.droneagent.service;

import cn.tedu.droneagent.bill.BillStore;
import cn.tedu.droneagent.budget.BudgetService;
import cn.tedu.droneagent.cancel.CancellationRegistry;
import cn.tedu.droneagent.cancel.CancellationReport;
import cn.tedu.droneagent.config.BudgetProperties;
import cn.tedu.droneagent.config.DeepSeekProperties;
import cn.tedu.droneagent.context.ContextMessage;
import cn.tedu.droneagent.config.MemoryProperties;
import cn.tedu.droneagent.artifact.ArtifactRef;
import cn.tedu.droneagent.artifact.ArtifactService;
import cn.tedu.droneagent.config.ToolProperties;
import cn.tedu.droneagent.dto.ChatResponse;
import cn.tedu.droneagent.dto.ChatMessage;
import cn.tedu.droneagent.memory.ContextAssembler;
import cn.tedu.droneagent.memory.LongTermMemoryStore;
import cn.tedu.droneagent.memory.LongTermSessionLoader;
import cn.tedu.droneagent.memory.MemoryArchiver;
import cn.tedu.droneagent.memory.MemoryIndex;
import cn.tedu.droneagent.memory.SessionBusyException;
import cn.tedu.droneagent.memory.SessionKey;
import cn.tedu.droneagent.memory.SessionLocks;
import cn.tedu.droneagent.memory.SessionStore;
import cn.tedu.droneagent.trace.TraceRecorder;
import cn.tedu.droneagent.prompt.TestPrompts;
import cn.tedu.droneagent.tool.ModelTurn;
import cn.tedu.droneagent.tool.Tool;
import cn.tedu.droneagent.tool.ToolCall;
import cn.tedu.droneagent.tool.ToolContext;
import cn.tedu.droneagent.tool.ToolDefinition;
import cn.tedu.droneagent.tool.ToolDescriptor;
import cn.tedu.droneagent.tool.ToolRegistry;
import cn.tedu.droneagent.tool.ToolResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.unit.DataSize;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ConversationService} 的单元测试。
 *
 * <p>对应验收条件：<b>AC-1</b>（跨请求上下文连续）、<b>AC-2</b>（会话互不串味）。
 * 模型调用被替换成桩件，所以这里不碰网络。
 */
class ConversationServiceTest {

    private static final long USER = 9009L;

    private static final Duration SHORT_LOCK_WAIT = Duration.ofMillis(200);

    @TempDir
    Path root;

    private ExecutorService executor;

    private SessionStore sessions;

    private MemoryProperties props;

    private MemoryIndex index;

    private LongTermMemoryStore body;

    private StubChatService stub;

    private ConversationService conversations;

    /**
     * 桩件：记录收到的上下文，按需在第一次调用时卡住。
     *
     * <p>「卡住」只对**第一次**调用生效，这样「另一条会话不该被挡住」才有区分度——
     * 若每次都卡，那条用例在互斥失效时也会通过。
     */
    private static final class StubChatService extends ChatService {

        private final List<List<ChatMessage>> receivedContexts = new ArrayList<>();

        private final CountDownLatch firstCallEntered = new CountDownLatch(1);

        private final CountDownLatch releaseFirstCall = new CountDownLatch(1);

        private final AtomicBoolean shouldBlockNext = new AtomicBoolean(false);

        private final String reply;

        private boolean abortOnContent;

        StubChatService(String reply) {
            super(new DeepSeekProperties("http://unused", "unused", "test-model", null, false),
                    TestPrompts.of("系统提示词"), new ObjectMapper());
            this.reply = reply;
        }

        void blockNextCall() {
            shouldBlockNext.set(true);
        }

        /** 让下一次流式调用在吐出正文后抛错，模拟客户端断开 */
        void abortOnContent() {
            abortOnContent = true;
        }

        @Override
        public ModelTurn streamStep(SessionKey key, List<ContextMessage> messages,
                                    List<ToolDefinition> tools, String traceId,
                                    java.util.Set<String> permissions, ChatStreamListener listener) {
            receivedContexts.add(messages.stream()
                    .map(m -> new ChatMessage(m.message().role(), m.message().content()))
                    .toList());
            awaitIfBlocking();
            listener.onContent(reply);
            if (abortOnContent) {
                throw new IllegalStateException("客户端断开");
            }
            return new ModelTurn(reply, List.of(), null, 0);
        }

        void awaitFirstCallEntered() throws InterruptedException {
            assertTrue(firstCallEntered.await(5, TimeUnit.SECONDS), "第一条请求应已进入模型调用");
        }

        void releaseFirstCall() {
            releaseFirstCall.countDown();
        }

        List<ChatMessage> lastContext() {
            return receivedContexts.get(receivedContexts.size() - 1);
        }

        @Override
        public String chat(SessionKey key, List<ContextMessage> context, java.util.Set<String> permissions) {
            return chat(key, context, permissions, "test-trace");
        }

        /** 主链路带 traceId（账单要和请求同一条 trace）；测试记录的是同一份上下文 */
        @Override
        public String chat(SessionKey key, List<ContextMessage> context,
                           java.util.Set<String> permissions, String traceId) {
            receivedContexts.add(asChatMessages(context));
            awaitIfBlocking();
            return reply;
        }

        /** 非流式路径现在走 chatStep（经 ToolCallingService，带 traceId）；同样记录上下文与阻塞 */
        @Override
        public ModelTurn chatStep(SessionKey key, List<ContextMessage> messages, List<ToolDefinition> tools,
                                  String traceId, java.util.Set<String> permissions) {
            receivedContexts.add(asChatMessages(messages));
            awaitIfBlocking();
            return ModelTurn.text(reply);
        }

        @Override
        public void streamChat(SessionKey key, List<ContextMessage> context,
                               java.util.Set<String> permissions, DeltaConsumer onDelta) {
            receivedContexts.add(asChatMessages(context));
            onDelta.accept(reply);
        }

        private static List<ChatMessage> asChatMessages(List<ContextMessage> context) {
            return context.stream()
                    .map(m -> new ChatMessage(m.message().role(), m.message().content()))
                    .toList();
        }

        /** 只阻塞**第一次**调用，这样「另一条会话不该被挡住」才有区分度 */
        private void awaitIfBlocking() {
            if (shouldBlockNext.compareAndSet(true, false)) {
                firstCallEntered.countDown();
                try {
                    releaseFirstCall.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    /** 测试用工具配置：预算/并行取一组固定值，只让 enabled 可变 */
    private static ToolProperties toolProperties(boolean enabled) {
        return new ToolProperties(enabled, 3, true, 4, Duration.ofSeconds(15),
                List.of(), true, 2000, 6000, 32768, 30000, 1, List.of(), Map.of(), List.of(), null, Duration.ofMinutes(5), null);
    }

    @BeforeEach
    void setUp() {
        executor = Executors.newSingleThreadExecutor(r -> new Thread(r, "test-archiver"));
        props = new MemoryProperties(String.valueOf(root), 100, Duration.ofSeconds(1800),
                200, DataSize.ofMegabytes(2), 10, 8000, true, 30, 10, 1500, 12000);
        body = new LongTermMemoryStore(root, props.fileMaxBytes());
        index = new MemoryIndex(root);
        MemoryArchiver archiver = new MemoryArchiver(body, index);
        sessions = new SessionStore(props, new LongTermSessionLoader(index, body), archiver, executor);
        stub = new StubChatService("回复内容");
        conversations = new ConversationService(sessions,
                new ContextAssembler(sessions, index, body, props), stub,
                new ToolCallingService(stub, registry()), toolProperties(true),
                new SessionLocks(), SHORT_LOCK_WAIT);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    private static List<String> contentsOf(SessionKey key, SessionStore store) {
        return store.getOrCreate(key).messages().stream().map(ChatMessage::content).toList();
    }

    @Test
    @DisplayName("AC-1 一次问答后，会话里既有用户那句也有助手那句")
    void askAppendsBothMessages() {
        SessionKey key = new SessionKey(USER, "current");

        assertEquals("回复内容", conversations.ask(key, "电池低温报警怎么处理"));

        assertEquals(List.of("电池低温报警怎么处理", "回复内容"), contentsOf(key, sessions));
    }

    @Test
    @DisplayName("AC-1 发给模型的上下文里包含「刚问的这句」，而不是落下一步")
    void askIncludesTheJustAskedMessage() {
        SessionKey key = new SessionKey(USER, "current");

        conversations.ask(key, "第一句");

        List<String> sent = stub.lastContext().stream().map(ChatMessage::content).toList();
        assertEquals(List.of("第一句"), sent, "上下文里应有刚问的这句（系统提示词由 ChatService 另加）");
    }

    @Test
    @DisplayName("AC-1 第二轮只发问题也能接上：上下文里带着上一轮的问答")
    void secondTurnCarriesPreviousTurn() {
        SessionKey key = new SessionKey(USER, "current");

        conversations.ask(key, "问题一");
        conversations.ask(key, "问题二");

        List<String> sent = stub.lastContext().stream().map(ChatMessage::content).toList();
        assertEquals(List.of("问题一", "回复内容", "问题二"), sent);
    }

    @Test
    @DisplayName("客户端中途断开时，已生成的那部分仍记进会话")
    void streamAskKeepsPartialReplyWhenClientAborts() {
        SessionKey key = new SessionKey(USER, "current");

        stub.abortOnContent();

        assertThrows(IllegalStateException.class,
                () -> conversations.streamAsk(key, "问题", ToolContext.anonymous(), ChatStreamListener.NOOP));

        assertEquals(List.of("问题", "回复内容"), contentsOf(key, sessions),
                "已生成的内容是真实的模型输出，不该因为客户端断开就丢掉");
    }

    @Test
    @DisplayName("AC-2 不同会话互不阻塞，各自的内容也不会串")
    void differentSessionsDoNotInterfere() {
        SessionKey first = new SessionKey(USER, "conv-a");
        SessionKey second = new SessionKey(USER, "conv-b");

        conversations.ask(first, "A 的问题");
        conversations.ask(second, "B 的问题");

        assertEquals(List.of("A 的问题", "回复内容"), contentsOf(first, sessions));
        assertEquals(List.of("B 的问题", "回复内容"), contentsOf(second, sessions));
    }

    @Test
    @DisplayName("同一会话已有请求在处理时，第二条被明确拒绝而不是无限排队")
    void busySessionIsRejected() throws Exception {
        SessionKey key = new SessionKey(USER, "current");
        stub.blockNextCall();

        Thread firstInFlight = new Thread(() -> conversations.ask(key, "第一句"));
        firstInFlight.start();
        stub.awaitFirstCallEntered();

        assertThrows(SessionBusyException.class, () -> conversations.ask(key, "第二句"),
                "等锁超时应拒绝，而不是把线程一直挂住");

        stub.releaseFirstCall();
        firstInFlight.join(5_000);
        assertFalse(firstInFlight.isAlive(), "第一条应能正常结束");
    }

    @Test
    @DisplayName("一条会话卡住时，别的会话照常可用")
    void busySessionDoesNotBlockOtherSessions() throws Exception {
        SessionKey busy = new SessionKey(USER, "busy");
        SessionKey other = new SessionKey(USER, "other");
        stub.blockNextCall();

        Thread firstInFlight = new Thread(() -> conversations.ask(busy, "让这条卡住"));
        firstInFlight.start();
        stub.awaitFirstCallEntered();

        assertEquals("回复内容", conversations.ask(other, "另一条会话应该照常"));

        stub.releaseFirstCall();
        firstInFlight.join(5_000);
    }

    @Test
    @DisplayName("续跑：注入已完成对象 ID；撤销时附带逆序补偿计划")
    void injectsResumeInstruction() {
        CancellationReport.ResumeToken token = new CancellationReport.ResumeToken(
                List.of(new CancellationReport.Operation("drone__user_add", "op-1", "digest-1")),
                List.of(new CancellationReport.Captured("drone__user_add", "123", "op-1")));

        conversations.askOutcome(new SessionKey(USER, "conv-resume"),
                "撤销刚才的创建", token);

        String instruction = stub.lastContext().get(0).content();
        assertTrue(instruction.contains("续跑"), instruction);
        assertTrue(instruction.contains("drone__user_add（ID=123）"), instruction);
        assertTrue(instruction.contains("撤销计划"), instruction);
        assertTrue(instruction.contains("drone__user_toggle"), instruction);
    }

    @Test
    @DisplayName("预算：日额度超限直接拒绝，不调用模型")
    void rejectsWhenDailyBudgetExceeded() {
        BudgetService budget = new BudgetService(
                new BudgetProperties(true, 1000, 100), userId -> 200L, traceId -> 0L);
        ConversationService budgeted = new ConversationService(sessions,
                new ContextAssembler(sessions, index, body, props), stub,
                new ToolCallingService(stub, registry()), toolProperties(true),
                new SessionLocks(), SHORT_LOCK_WAIT, ArtifactService.noop(), BillStore.noop(),
                TraceRecorder.noop(), budget, new CancellationRegistry());

        ChatResponse response = budgeted.askOutcome(new SessionKey(USER, "conv-budget"), "你好");

        assertTrue(response.reply().contains("额度"), response.reply());
        assertTrue(stub.receivedContexts.isEmpty(), "日额度超限不应调用模型");
    }

    @Test
    @DisplayName("AC-8 发生了工具调用的那次对话，会话里也只多两条（用户 + 最终答复），不含工具中间消息")
    void toolCallingLeavesOnlyTwoSessionMessages() {
        SessionKey key = new SessionKey(USER, "conv-tool");
        // 换一个「先要工具、再作答」的模型 + 一个总是成功的工具，走真实的 ToolCallingService
        ConversationService toolConversations = new ConversationService(sessions,
                new ContextAssembler(sessions, index, body, props), stub,
                new ToolCallingService(new OnceToolThenTextChatService(), registry(new FixedOkTool())),
                toolProperties(true),
                new SessionLocks(), SHORT_LOCK_WAIT);

        String reply = toolConversations.ask(key, "北京能起飞吗，天气怎么样");

        assertEquals("最终答复", reply);
        assertEquals(List.of("北京能起飞吗，天气怎么样", "最终答复"), contentsOf(key, sessions),
                "工具请求与工具结果都不该写进会话记忆");
    }

    @Test
    @DisplayName("T-6 工具调用关掉时，ask 退回一次性对话（行为与本需求之前一致）")
    void disabledToolCallingFallsBackToPlainChat() {
        SessionKey key = new SessionKey(USER, "conv-off");
        ConversationService off = new ConversationService(sessions,
                new ContextAssembler(sessions, index, body, props), stub,
                new ToolCallingService(stub, registry()),
                toolProperties(false),
                new SessionLocks(), SHORT_LOCK_WAIT);

        String reply = off.ask(key, "普通问题");

        assertEquals("回复内容", reply);
        assertEquals(List.of("普通问题", "回复内容"), contentsOf(key, sessions));
        // 关掉开关走的是 chat()，上下文照样带上刚问的那句
        assertEquals(List.of("普通问题"), stub.lastContext().stream().map(ChatMessage::content).toList());
    }

    /** 把若干桩工具包成一个注册处（元数据一并造好，保证校验通过） */
    private static ToolRegistry registry(Tool... tools) {
        List<ToolDescriptor> catalog = new ArrayList<>();
        for (Tool tool : tools) {
            catalog.add(new ToolDescriptor(tool.name(), "测试工具", "{}", true, true));
        }
        return new ToolRegistry(List.of(tools), catalog, new ObjectMapper());
    }

    /** AC-8 用：第一次要工具、第二次才作答的模型 */
    private static final class OnceToolThenTextChatService extends ChatService {
        private int calls;

        OnceToolThenTextChatService() {
            super(new DeepSeekProperties("http://unused", "k", "m", null, false),
                    TestPrompts.of("sys"), new ObjectMapper());
        }

        @Override
        public ModelTurn chatStep(SessionKey key, List<ContextMessage> messages, List<ToolDefinition> tools,
                                  String traceId, java.util.Set<String> permissions) {
            calls++;
            if (calls == 1) {
                return new ModelTurn(null,
                        List.of(new ToolCall("c1", "demo_tool", "{\"place\":\"北京\"}")));
            }
            return ModelTurn.text("最终答复");
        }
    }

    /** AC-8 用：总是成功的工具 */
    private static final class FixedOkTool implements Tool {

        @Override
        public String name() {
            return "demo_tool";
        }

        @Override
        public ToolResult invoke(String argumentsJson) {
            return ToolResult.ok("联网结果");
        }
    }

    @Test
    @DisplayName("有 HTML 产物：回复清洗、会话不存 HTML、响应带 artifacts")
    void capturesArtifactBeforePersisting() {
        SessionKey key = new SessionKey(USER, "artifact-conv");
        StubChatService chat = new StubChatService("摘要\n```html\n<html><title>T</title></html>\n```");
        ArtifactService artifactService = (userId, conversationId, reply) ->
                new ArtifactService.Capture("摘要\n（HTML 报告已生成）",
                        List.of(new ArtifactRef("artifact-12345678", "运营日报.html", 120)));
        ConversationService service = new ConversationService(sessions,
                new ContextAssembler(sessions, index, body, props), chat,
                new ToolCallingService(chat, registry()), toolProperties(false),
                new SessionLocks(), SHORT_LOCK_WAIT, artifactService);

        ChatResponse response = service.askOutcome(key, "出个 HTML 报告");

        assertEquals("摘要\n（HTML 报告已生成）", response.reply());
        assertEquals(1, response.artifacts().size());
        assertTrue(contentsOf(key, sessions).stream().noneMatch(c -> c.contains("<html>")),
                "会话里不能存 HTML 源码");
    }
}
