package cn.tedu.droneagent.service;

import cn.tedu.droneagent.budget.BudgetService;
import cn.tedu.droneagent.cancel.CancellationReport;
import cn.tedu.droneagent.cancel.CancellationSignal;
import cn.tedu.droneagent.config.BudgetProperties;
import cn.tedu.droneagent.config.DeepSeekProperties;
import cn.tedu.droneagent.config.SubAgentProperties;
import cn.tedu.droneagent.config.ToolProperties;
import cn.tedu.droneagent.config.ToolVisibilityRule;
import cn.tedu.droneagent.context.ContextMessage;
import cn.tedu.droneagent.context.MessageKind;
import cn.tedu.droneagent.dto.ChatMessage;
import cn.tedu.droneagent.journal.WriteJournal;
import cn.tedu.droneagent.memory.SessionKey;
import cn.tedu.droneagent.plan.Plan;
import cn.tedu.droneagent.plan.PlanCatalog;
import cn.tedu.droneagent.plan.PlanExecutor;
import cn.tedu.droneagent.plan.PlanPlanner;
import cn.tedu.droneagent.plan.PlanService;
import cn.tedu.droneagent.plan.PlanStep;
import cn.tedu.droneagent.plan.PlanStore;
import cn.tedu.droneagent.prompt.PromptCatalog;
import cn.tedu.droneagent.prompt.TestPrompts;
import cn.tedu.droneagent.prompt.ToolResultEnvelope;
import cn.tedu.droneagent.route.TaskRouter;
import cn.tedu.droneagent.skill.SkillCatalog;
import cn.tedu.droneagent.subagent.SubAgentCatalog;
import cn.tedu.droneagent.subagent.SubAgentDefinition;
import cn.tedu.droneagent.subagent.SubAgentRunner;
import cn.tedu.droneagent.subagent.SubAgentService;
import cn.tedu.droneagent.tool.ToolContext;
import cn.tedu.droneagent.tool.ModelMessage;
import cn.tedu.droneagent.tool.ModelTurn;
import cn.tedu.droneagent.tool.PendingActionStore;
import cn.tedu.droneagent.tool.Tool;
import cn.tedu.droneagent.tool.ToolAuditLog;
import cn.tedu.droneagent.tool.ToolCall;
import cn.tedu.droneagent.tool.ToolDefinition;
import cn.tedu.droneagent.tool.ToolDescriptor;
import cn.tedu.droneagent.tool.ToolGovernance;
import cn.tedu.droneagent.tool.ToolRegistry;
import cn.tedu.droneagent.tool.ToolResult;
import cn.tedu.droneagent.tool.ToolResultCompressor;
import cn.tedu.droneagent.trace.TraceRecorder;
import cn.tedu.droneagent.trace.TraceSpan;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ToolCallingService} 的单元测试。
 *
 * <p>对应验收条件：`docs/sdd/specs/REQ-AGENT-0004-工具调用.md` 的
 * <b>AC-1</b>（模型发起工具调用并据结果作答）、<b>AC-2 / AC-3</b>（不需要工具时不调用）、
 * <b>AC-5</b>（轮次上限），以及方案里的「结果截断」。
 *
 * <p>模型与工具都用**手写替身**（子类 / 实现类）打桩，不引 Mockito、不发真实网络请求。
 */
class ToolCallingServiceTest {

    private static final SessionKey KEY = new SessionKey(1L, "conv-1");

    private static ModelTurn toolTurn(String toolName, String argsJson) {
        return new ModelTurn(null, List.of(new ToolCall("call_1", toolName, argsJson)));
    }

    private static List<ContextMessage> userSays(String text) {
        return List.of(ContextMessage.of(new ChatMessage("user", text), MessageKind.SESSION));
    }

    // ---------------- 替身 ----------------

    /** 按脚本依次返回响应的 ChatService；并记录每次调用收到的消息与工具定义（**做快照**，否则会被后续轮次改到） */
    private static final class ScriptedChatService extends ChatService {

        private final Deque<ModelTurn> script;

        private final List<List<ModelMessage>> calls = new ArrayList<>();

        /** 与 calls 同序，但保留账单分类（工具调用 / 工具结果），供分类断言用 */
        private final List<List<ContextMessage>> framed = new ArrayList<>();

        private final List<List<ToolDefinition>> toolsPerCall = new ArrayList<>();

        ScriptedChatService(ModelTurn... turns) {
            super(new DeepSeekProperties("http://unused", "k", "m", null, false),
                    TestPrompts.of("sys"), new ObjectMapper());
            this.script = new ArrayDeque<>(List.of(turns));
        }

        @Override
        public ModelTurn chatStep(SessionKey key, List<ContextMessage> messages, List<ToolDefinition> tools,
                                  String traceId, java.util.Set<String> permissions) {
            calls.add(ContextMessage.unwrap(messages));
            framed.add(List.copyOf(messages));
            toolsPerCall.add(List.copyOf(tools));
            if (script.isEmpty()) {
                throw new IllegalStateException("脚本已用尽：模型被多调了一次");
            }
            return script.poll();
        }
    }

    /** 流式桩：按脚本返回回合，并把思考/正文回调给监听器 */
    private static final class ScriptedStreamChatService extends ChatService {

        private final Deque<ModelTurn> script;

        ScriptedStreamChatService(ModelTurn... turns) {
            super(new DeepSeekProperties("http://unused", "k", "m", null, false),
                    TestPrompts.of("sys"), new ObjectMapper());
            this.script = new ArrayDeque<>(List.of(turns));
        }

        @Override
        public ModelTurn streamStep(SessionKey key, List<ContextMessage> messages,
                                    List<ToolDefinition> tools, String traceId,
                                    java.util.Set<String> permissions, ChatStreamListener listener) {
            ModelTurn turn = script.poll();
            if (turn.reasoningContent() != null) {
                listener.onReasoning(turn.reasoningContent());
            }
            if (turn.content() != null) {
                listener.onContent(turn.content());
            }
            return turn;
        }
    }

    /** 固定返回某个结果的工具，并记录被调用时的参数 */
    private static final class StubTool implements Tool {

        private final String toolName;

        private final ToolResult result;

        private final List<String> invocations = new ArrayList<>();

        StubTool(String toolName, ToolResult result) {
            this.toolName = toolName;
            this.result = result;
        }

        @Override
        public String name() {
            return toolName;
        }

        @Override
        public ToolResult invoke(String argumentsJson) {
            invocations.add(argumentsJson);
            return result;
        }
    }

    /** 一个「要两个同时在跑才通得过」的工具：顺序执行时必然超时 → 据此区分并行/串行 */
    private static final class BarrierTool implements Tool {

        private final String toolName;

        private final CyclicBarrier barrier;

        BarrierTool(String toolName, CyclicBarrier barrier) {
            this.toolName = toolName;
            this.barrier = barrier;
        }

        @Override
        public String name() {
            return toolName;
        }

        @Override
        public ToolResult invoke(String argumentsJson) {
            try {
                barrier.await(1, TimeUnit.SECONDS);
                return ToolResult.ok(toolName + "-ok");
            } catch (Exception e) {
                return ToolResult.failed(toolName + "-未能并发");
            }
        }
    }

    /** 会睡一会儿的工具，用于验证超时兜底 */
    private static Tool sleepingTool(String name, long millis) {
        return new Tool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public ToolResult invoke(String argumentsJson) {
                try {
                    Thread.sleep(millis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return ToolResult.ok(name + "-ok");
            }
        };
    }

    /** 一轮里请求调用多个工具 */
    private static ModelTurn toolTurnMulti(String... toolNames) {
        List<ToolCall> calls = new ArrayList<>();
        for (int i = 0; i < toolNames.length; i++) {
            calls.add(new ToolCall("call_" + i, toolNames[i], "{}"));
        }
        return new ModelTurn(null, calls);
    }

    /** 取会话消息里所有「工具结果帧」的内容，按顺序 */
    private static List<String> toolMessageContents(List<ModelMessage> messages) {
        return messages.stream()
                .filter(m -> "tool".equals(m.role()))
                .map(ModelMessage::content)
                .toList();
    }

    /** 剥掉「数据信封」，取出里面真正的工具结果（信封是 REQ-AGENT-0009 加的，不该参与长度断言） */
    private static String inner(String enveloped) {
        return enveloped.replace(ToolResultEnvelope.OPEN, "")
                .replace(ToolResultEnvelope.CLOSE, "").strip();
    }

    /** 把若干桩工具包成一个注册处（元数据一并造好，保证校验通过）；默认标为「可并行」 */
    private static ToolRegistry registry(Tool... tools) {
        return registry(true, tools);
    }

    /** 同上，但可指定「是否只读 / 可否并行」 */
    private static ToolRegistry registry(boolean parallelSafe, Tool... tools) {
        List<ToolDescriptor> catalog = new ArrayList<>();
        for (Tool tool : tools) {
            catalog.add(new ToolDescriptor(tool.name(), "测试工具", "{}", parallelSafe, parallelSafe));
        }
        return new ToolRegistry(List.of(tools), catalog, new ObjectMapper());
    }

    // ---------------- 用例 ----------------

    @Test
    @DisplayName("AC-1：模型要工具 → 执行 → 回填 → 再问 → 返回最终答复")
    void callsToolThenAnswers() {
        StubTool tool = new StubTool("demo_tool", ToolResult.ok("联网结果：多云"));
        ScriptedChatService chat = new ScriptedChatService(
                toolTurn("demo_tool", "{\"query\":\"北京天气\"}"),
                ModelTurn.text("明天多云，可以起飞。"));

        String reply = new ToolCallingService(chat, registry(tool)).ask(KEY, userSays("北京天气怎么样"));

        assertEquals("明天多云，可以起飞。", reply);
        assertEquals(1, tool.invocations.size());
        assertEquals("{\"query\":\"北京天气\"}", tool.invocations.get(0));
        assertEquals(2, chat.calls.size());

        // 第二次调用应带上「助手请求(含 tool_calls)」与「工具结果」两帧
        List<ModelMessage> second = chat.calls.get(1);
        assertTrue(second.stream().anyMatch(
                m -> "assistant".equals(m.role()) && m.toolCalls() != null && !m.toolCalls().isEmpty()));
        assertTrue(second.stream().anyMatch(
                m -> "tool".equals(m.role()) && inner(m.content()).equals("联网结果：多云")));
        assertTrue(second.stream().anyMatch(
                m -> "tool".equals(m.role()) && m.content().startsWith(ToolResultEnvelope.OPEN)),
                "工具结果应被包成数据信封（REQ-AGENT-0009）");
    }

    @Test
    @DisplayName("AC-2 / AC-3：模型不肯用工具时，一次调用直接返回，工具不被执行")
    void answersWithoutToolWhenNotNeeded() {
        StubTool tool = new StubTool("demo_tool", ToolResult.ok("x"));
        ScriptedChatService chat = new ScriptedChatService(ModelTurn.text("M300 建议 500 小时保养。"));

        String reply = new ToolCallingService(chat, registry(tool)).ask(KEY, userSays("M300 保养周期"));

        assertEquals("M300 建议 500 小时保养。", reply);
        assertTrue(tool.invocations.isEmpty());
        assertEquals(1, chat.calls.size());
        // 工具确实**提供给**了模型，只是它自己决定不用
        assertEquals(1, chat.toolsPerCall.get(0).size());
    }

    @Test
    @DisplayName("AC-5：连续要工具超过上限 → 不再给工具，收尾作答，循环必然终止")
    void stopsAfterRoundLimit() {
        StubTool tool = new StubTool("demo_tool", ToolResult.ok("结果"));
        ScriptedChatService chat = new ScriptedChatService(
                toolTurn("demo_tool", "{}"),
                toolTurn("demo_tool", "{}"),
                toolTurn("demo_tool", "{}"),
                ModelTurn.text("基于已查到的结果：明天多云。"));

        String reply = new ToolCallingService(chat, registry(tool), 3).ask(KEY, userSays("天气"));

        assertEquals("基于已查到的结果：明天多云。", reply);
        assertEquals(4, chat.calls.size());
        assertEquals(3, tool.invocations.size());
        // 前 3 次给工具，第 4 次（收尾）不给
        assertFalse(chat.toolsPerCall.get(2).isEmpty());
        assertTrue(chat.toolsPerCall.get(3).isEmpty());
    }

    @Test
    @DisplayName("模型请求未知工具：回填「没有该工具」，不抛异常")
    void unknownToolIsReported() {
        StubTool tool = new StubTool("demo_tool", ToolResult.ok("x"));
        ScriptedChatService chat = new ScriptedChatService(
                toolTurn("unknown_tool", "{}"),
                ModelTurn.text("好的。"));

        String reply = new ToolCallingService(chat, registry(tool)).ask(KEY, userSays("hi"));

        assertEquals("好的。", reply);
        assertTrue(chat.calls.get(1).stream().anyMatch(
                m -> "tool".equals(m.role()) && m.content().contains("没有名为")));
    }

    @Test
    @DisplayName("工具失败：把失败文本回填，模型据此如实作答（AC-4 的编排侧）")
    void toolFailureIsFedBack() {
        StubTool tool = new StubTool("demo_tool", ToolResult.failed("天气查询暂时不可用（超时）"));
        ScriptedChatService chat = new ScriptedChatService(
                toolTurn("demo_tool", "{}"),
                ModelTurn.text("暂时查不到天气。"));

        String reply = new ToolCallingService(chat, registry(tool)).ask(KEY, userSays("天气"));

        assertEquals("暂时查不到天气。", reply);
        assertTrue(chat.calls.get(1).stream().anyMatch(
                m -> "tool".equals(m.role()) && m.content().contains("暂时不可用")));
    }

    @Test
    @DisplayName("工具结果超过兜底长度时被截断，避免撑爆下一轮 prompt")
    void truncatesOversizedToolResult() {
        String huge = "雨".repeat(ToolCallingService.LEGACY_RESULT_CHARS + 100);
        StubTool tool = new StubTool("demo_tool", ToolResult.ok(huge));
        ScriptedChatService chat = new ScriptedChatService(
                toolTurn("demo_tool", "{}"),
                ModelTurn.text("done"));

        new ToolCallingService(chat, registry(tool)).ask(KEY, userSays("天气"));

        ModelMessage toolMessage = chat.calls.get(1).stream()
                .filter(m -> "tool".equals(m.role()))
                .findFirst()
                .orElseThrow();
        assertEquals(ToolCallingService.LEGACY_RESULT_CHARS + 1, inner(toolMessage.content()).length());
        assertTrue(inner(toolMessage.content()).endsWith("…"));
    }

    @Test
    @DisplayName("AC-2 本轮全为只读工具 → 并发执行（栅栏证明：两个得同时在跑才通得过）")
    void parallelSafeToolsRunConcurrently() {
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            ScriptedChatService chat = new ScriptedChatService(
                    toolTurnMulti("tool_a", "tool_b"), ModelTurn.text("done"));
            ToolCallingService service = new ToolCallingService(chat,
                    registry(new BarrierTool("tool_a", barrier), new BarrierTool("tool_b", barrier)),
                    pool, true, Duration.ofSeconds(3), 3);

            service.ask(KEY, userSays("并行"));

            List<String> contents = toolMessageContents(chat.calls.get(1));
            assertTrue(contents.stream().allMatch(c -> c.contains("-ok")),
                    "两个工具应并发通过栅栏，实际=" + contents);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("AC-5 关闭并行 → 逐个执行（栅栏通不过，证明没有同时跑）")
    void sequentialWhenParallelDisabled() {
        CyclicBarrier barrier = new CyclicBarrier(2);
        ScriptedChatService chat = new ScriptedChatService(
                toolTurnMulti("tool_a", "tool_b"), ModelTurn.text("done"));
        // 顺序构造（parallelEnabled=false、不并行）
        ToolCallingService service = new ToolCallingService(chat,
                registry(new BarrierTool("tool_a", barrier), new BarrierTool("tool_b", barrier)), 3);

        service.ask(KEY, userSays("并行"));

        List<String> contents = toolMessageContents(chat.calls.get(1));
        assertTrue(contents.stream().allMatch(c -> c.contains("未能并发")),
                "顺序执行时栅栏不该被满足，实际=" + contents);
    }

    @Test
    @DisplayName("AC-2 变体：本轮有「非只读」工具 → 即便开了并行也逐个执行")
    void nonParallelSafeToolsRunSequentially() {
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            ScriptedChatService chat = new ScriptedChatService(
                    toolTurnMulti("tool_a", "tool_b"), ModelTurn.text("done"));
            ToolCallingService service = new ToolCallingService(chat,
                    registry(false, new BarrierTool("tool_a", barrier), new BarrierTool("tool_b", barrier)),
                    pool, true, Duration.ofSeconds(3), 3);

            service.ask(KEY, userSays("并行"));

            List<String> contents = toolMessageContents(chat.calls.get(1));
            assertTrue(contents.stream().allMatch(c -> c.contains("未能并发")),
                    "有非只读工具时应逐个执行，实际=" + contents);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("AC-3 工具超时 → 只回填该工具失败，其他工具不受影响，对话继续")
    void toolTimeoutIsFedBack() {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            ScriptedChatService chat = new ScriptedChatService(
                    toolTurnMulti("slow_tool", "fast_tool"), ModelTurn.text("已尽力"));
            ToolCallingService service = new ToolCallingService(chat,
                    registry(sleepingTool("slow_tool", 2000),
                            new StubTool("fast_tool", ToolResult.ok("快工具结果"))),
                    pool, true, Duration.ofMillis(200), 3);

            String reply = service.ask(KEY, userSays("天气"));

            assertEquals("已尽力", reply);
            List<String> contents = toolMessageContents(chat.calls.get(1));
            assertTrue(contents.get(0).contains("超时"), "慢工具应按超时失败，实际=" + contents.get(0));
            assertTrue(contents.get(1).contains("快工具结果"), "快工具应不受影响，实际=" + contents.get(1));
        } finally {
            pool.shutdownNow();
        }
    }

    /** 测试用工具配置：并行关（走顺序路径），只让「优化开关 + 预算」可变 */
    private static ToolProperties toolProperties(boolean optimize, int maxResultChars, int maxTotalResultChars) {
        return toolProperties(optimize, maxResultChars, maxTotalResultChars, 32768, 30000);
    }

    /** 同上，并指定 Skill 资源的单次 / 累计预算 */
    private static ToolProperties toolProperties(boolean optimize, int maxResultChars, int maxTotalResultChars,
                                                 int maxSkillResultChars, int maxTotalSkillChars) {
        return new ToolProperties(true, 3, false, 4, Duration.ofSeconds(15),
                List.of(), optimize, maxResultChars, maxTotalResultChars,
                maxSkillResultChars, maxTotalSkillChars, 1, List.of(), Map.of(), List.of(),
                null, Duration.ofMinutes(5), null);
    }

    @Test
    @DisplayName("链路追踪：模型与工具各产生一条 span，工具名进 attrs")
    void recordsModelAndToolSpans(@TempDir Path dir) {
        ScriptedChatService chat = new ScriptedChatService(
                toolTurn("fixed_ok", "{}"), ModelTurn.text("完成"));
        TraceRecorder recorder = new TraceRecorder(dir, Duration.ofDays(1), new ObjectMapper(), true);
        ToolCallingService service = new ToolCallingService(chat,
                registry(new StubTool("fixed_ok", ToolResult.ok("ok"))),
                Runnable::run, noCompressor(), noGovernance(), freshStore(), ToolAuditLog.noop(),
                toolProperties(true, 2000, 6000), recorder);
        String traceId = "trace-1234";
        ToolContext context = ToolContext.ofUser(1L, Set.of("admin"), Set.of(), null)
                .withTraceId(traceId);
        recorder.beginRoot(1L, traceId);

        service.askOutcome(KEY, userSays("天气"), traceId, context);

        List<TraceSpan> spans = recorder.find(1L, traceId);
        assertTrue(spans.stream().anyMatch(span -> "model.call".equals(span.name())),
                "应记录模型调用 span，实际=" + spans);
        assertTrue(spans.stream().anyMatch(span -> "tool.call".equals(span.name())
                        && "fixed_ok".equals(span.attrs().get("tool"))),
                "应记录工具调用 span，实际=" + spans);
    }

    @Test
    @DisplayName("AC-4 工具结果总量超预算：先到者按单次上限截断，后到者只留省略标记；不报 500")
    void totalResultBudgetTruncatesAndSkips() {
        String big = "雨".repeat(100);
        ScriptedChatService chat = new ScriptedChatService(
                toolTurnMulti("tool_a", "tool_b"), ModelTurn.text("based on above"));
        ToolCallingService service = new ToolCallingService(chat,
                registry(new StubTool("tool_a", ToolResult.ok(big)),
                        new StubTool("tool_b", ToolResult.ok(big))),
                Runnable::run, noCompressor(), noGovernance(), freshStore(), ToolAuditLog.noop(), toolProperties(true, 50, 50));

        String reply = service.ask(KEY, userSays("x"));

        assertEquals("based on above", reply);
        List<String> contents = toolMessageContents(chat.calls.get(1));
        assertEquals(50, inner(contents.get(0)).length(), "第一条应压到单次上限 50 以内");
        assertEquals(ToolCallingService.SKIPPED_RESULT, inner(contents.get(1)), "预算用尽，第二条只留省略标记");
    }

    @Test
    @DisplayName("AC-6 关闭优化 → 回到旧行为：单次 4000 截断、总量不设限")
    void optimizeDisabledFallsBackToLegacy() {
        String big = "雨".repeat(5000);
        ScriptedChatService chat = new ScriptedChatService(
                toolTurnMulti("tool_a", "tool_b"), ModelTurn.text("done"));
        // 6 参构造 = 优化关闭
        ToolCallingService service = new ToolCallingService(chat,
                registry(new StubTool("tool_a", ToolResult.ok(big)),
                        new StubTool("tool_b", ToolResult.ok(big))),
                Runnable::run, false, Duration.ofSeconds(15), 3);

        service.ask(KEY, userSays("x"));

        List<String> contents = toolMessageContents(chat.calls.get(1));
        assertEquals(ToolCallingService.LEGACY_RESULT_CHARS + 1, inner(contents.get(0)).length());
        assertEquals(ToolCallingService.LEGACY_RESULT_CHARS + 1, inner(contents.get(1)).length(),
                "总量不设限，第二条同样按 4000 截断，而不是被省略");
    }

    /** 不启用模型摘要的压缩器（只做结构化裁剪 + 硬截断），供测试 */
    private static ToolResultCompressor noCompressor() {
        return new ToolResultCompressor(null);
    }

    /** 什么都不管的治理（无危险工具、无角色限制），供与治理无关的用例使用 */
    private static ToolGovernance noGovernance() {
        return new ToolGovernance(List.of(), List.of(), Map.of());
    }

    /** 每次给一个新的待确认存储，避免用例之间互相影响 */
    private static PendingActionStore freshStore() {
        return new PendingActionStore(Duration.ofMinutes(5));
    }

    /** 第一次失败、之后成功的工具，用来验证「受控重试」 */
    private static Tool failOnceThenOk(String name) {
        AtomicInteger calls = new AtomicInteger();
        return new Tool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public ToolResult invoke(String argumentsJson) {
                return calls.incrementAndGet() == 1
                        ? ToolResult.failed("第一次失败")
                        : ToolResult.ok(name + "-ok");
            }
        };
    }

    @Test
    @DisplayName("AC-1 工具失败后不立即收场，允许一次重试；重试成功则据其作答")
    void retriesOnceAfterFailure() {
        ScriptedChatService chat = new ScriptedChatService(
                toolTurn("tool_a", "{}"), toolTurn("tool_a", "{}"), ModelTurn.text("重试后成功"));
        ToolCallingService service = new ToolCallingService(chat,
                registry(failOnceThenOk("tool_a")),
                Runnable::run, noCompressor(), noGovernance(), freshStore(), ToolAuditLog.noop(), toolProperties(true, 2000, 6000));

        String reply = service.ask(KEY, userSays("查天气"));

        assertEquals("重试后成功", reply);
        assertTrue(toolMessageContents(chat.calls.get(2)).stream().anyMatch(c -> c.contains("-ok")),
                "重试应拿到成功结果");
    }

    @Test
    @DisplayName("AC-2 失败已用满重试预算 → 收回工具、逼模型收尾；不再无限重试")
    void stopsRetryingAfterBudget() {
        ScriptedChatService chat = new ScriptedChatService(
                toolTurn("tool_a", "{}"), toolTurn("tool_a", "{}"), ModelTurn.text("我尽力了"));
        ToolCallingService service = new ToolCallingService(chat,
                registry(new StubTool("tool_a", ToolResult.failed("一直失败"))),
                Runnable::run, noCompressor(), noGovernance(), freshStore(), ToolAuditLog.noop(), toolProperties(true, 2000, 6000));

        String reply = service.ask(KEY, userSays("查天气"));

        assertEquals("我尽力了", reply);
        assertEquals(3, chat.calls.size());
        assertTrue(chat.toolsPerCall.get(2).isEmpty(), "第 3 步（失败已用满预算）不该再提供工具");
    }

    /** 一个「删除设备」的危险工具桩 */
    private static Tool deleteDeviceTool(java.util.concurrent.atomic.AtomicInteger executed) {
        return new Tool() {
            @Override
            public String name() {
                return "delete_device";
            }

            @Override
            public ToolResult invoke(String argumentsJson) {
                executed.incrementAndGet();
                return ToolResult.ok("已删除");
            }
        };
    }

    private static ToolGovernance governanceAllowingAdminOnly() {
        return new ToolGovernance(
                List.of(new ToolDescriptor("delete_device", "删除设备", "{}", false, false)),
                List.of("delete_device"),
                Map.of("admin", List.of("delete_device")));
    }

    @Test
    @DisplayName("AC-1 角色不在允许名单 → 工具不执行，回填「无权限」")
    void unauthorizedToolIsRefused() {
        AtomicInteger executed = new AtomicInteger();
        ScriptedChatService chat = new ScriptedChatService(
                toolTurn("delete_device", "{}"), ModelTurn.text("好的"));
        ToolCallingService service = new ToolCallingService(chat, registry(deleteDeviceTool(executed)),
                governanceAllowingAdminOnly(), freshStore());

        String reply = service.askOutcome(KEY, userSays("删掉设备"), "t", ToolContext.ofRoles(Set.of("pilot"))).reply();

        assertEquals("好的", reply);
        assertEquals(0, executed.get(), "无权限时不该执行工具");
        assertTrue(toolMessageContents(chat.calls.get(1)).get(0).contains("没有执行"), "应回填「无权限」");
    }

    @Test
    @DisplayName("AC-2 危险工具不立即执行，返回「待确认」")
    void dangerousToolPausesForConfirmation() {
        AtomicInteger executed = new AtomicInteger();
        ScriptedChatService chat = new ScriptedChatService(
                toolTurn("delete_device", "{\"id\":12}"), ModelTurn.text("不该走到这里"));
        ToolCallingService service = new ToolCallingService(chat, registry(deleteDeviceTool(executed)),
                governanceAllowingAdminOnly(), freshStore());

        ToolCallingService.AskResult result =
                service.askOutcome(KEY, userSays("删掉设备12"), "t", ToolContext.ofRoles(Set.of("admin")));

        assertNotNull(result.pending(), "危险工具应返回待确认");
        assertEquals("delete_device", result.pending().tool());
        assertEquals(0, executed.get(), "确认之前不该执行");
        assertEquals(1, chat.calls.size(), "暂停后不该再调模型");
    }

    @Test
    @DisplayName("AC-9 每次都要确认：连续两次危险操作，两次都返回待确认")
    void alwaysRequiresConfirmation() {
        AtomicInteger executed = new AtomicInteger();
        ScriptedChatService chat = new ScriptedChatService(
                toolTurn("delete_device", "{}"), toolTurn("delete_device", "{}"));
        ToolCallingService service = new ToolCallingService(chat, registry(deleteDeviceTool(executed)),
                governanceAllowingAdminOnly(), freshStore());

        assertNotNull(service.askOutcome(KEY, userSays("删"), "t1", ToolContext.ofRoles(Set.of("admin"))).pending());
        assertNotNull(service.askOutcome(KEY, userSays("再删一次"), "t2", ToolContext.ofRoles(Set.of("admin"))).pending(),
                "刚确认过也要再确认（本期策略：每次都确认）");
    }

    @Test
    @DisplayName("回归（REQ-AGENT-0006）：同轮多个工具时，暂停后每个 tool_call 都要有结果")
    void resumeAnswersEveryCallInPausedRound() {
        ScriptedChatService chat = new ScriptedChatService(
                new ModelTurn(null, List.of(
                        new ToolCall("c1", "read_device", "{}"),
                        new ToolCall("c2", "delete_device", "{}")), "r"),
                ModelTurn.text("好的"));
        PendingActionStore store = freshStore();
        ToolCallingService service = new ToolCallingService(chat,
                registry(new StubTool("read_device", ToolResult.ok("设备信息")),
                        deleteDeviceTool(new AtomicInteger())),
                governanceAllowingAdminOnly(), store);

        ToolCallingService.AskResult paused = service.askOutcome(
                KEY, userSays("查一下再删"), "t1", ToolContext.ofRoles(Set.of("admin")));
        assertNotNull(paused.pending());

        PendingActionStore.PendingAction action = store.take(paused.pending().pendingId(), KEY.userId())
                .orElseThrow();
        service.resume(KEY, action, false, "t2", ToolContext.ofRoles(Set.of("admin")));

        List<ModelMessage> messages = chat.calls.get(1);
        ModelMessage frame = messages.stream()
                .filter(m -> "assistant".equals(m.role()) && m.toolCalls() != null && !m.toolCalls().isEmpty())
                .reduce((first, second) -> second).orElseThrow();
        assertEquals(2, frame.toolCalls().size(), "必须回放整轮的两条调用");
        assertEquals(2, messages.stream().filter(m -> "tool".equals(m.role())).count(),
                "每个 tool_call 都必须有对应的 tool 结果");
        assertTrue(messages.stream()
                        .filter(m -> "tool".equals(m.role()))
                        .anyMatch(m -> m.content().contains("未执行")),
                "同轮被跳过的工具应如实回填「未执行」");
    }

    @Test
    @DisplayName("回归（REQ-AGENT-0006）：确认续跑必须回放模型原始的 assistant 帧（原 id + reasoning）")
    void resumeReplaysOriginalAssistantFrame() {
        AtomicInteger executed = new AtomicInteger();
        // 模型第一次就带 reasoning 请求了危险工具
        ScriptedChatService chat = new ScriptedChatService(
                new ModelTurn(null, List.of(new ToolCall("call_1", "delete_device", "{}")), "reasoning-abc"),
                ModelTurn.text("好的，已取消"));
        PendingActionStore store = freshStore();
        ToolCallingService service = new ToolCallingService(chat, registry(deleteDeviceTool(executed)),
                governanceAllowingAdminOnly(), store);

        ToolCallingService.AskResult paused = service.askOutcome(
                KEY, userSays("删掉设备"), "t1", ToolContext.ofRoles(Set.of("admin")));
        assertNotNull(paused.pending(), "危险工具应先暂停");

        PendingActionStore.PendingAction action = store.take(paused.pending().pendingId(), KEY.userId())
                .orElseThrow(() -> new IllegalStateException("票据应可取出"));
        service.resume(KEY, action, false, "t2", ToolContext.ofRoles(Set.of("admin")));

        // 续跑时发给模型的最后一条 assistant 帧
        List<ModelMessage> resumeMessages = chat.calls.get(1);
        ModelMessage frame = resumeMessages.stream()
                .filter(m -> "assistant".equals(m.role()) && m.toolCalls() != null && !m.toolCalls().isEmpty())
                .reduce((first, second) -> second)
                .orElseThrow(() -> new IllegalStateException("续跑必须带 assistant 工具帧"));

        assertEquals("call_1", frame.toolCalls().get(0).id(),
                "必须复用模型下发的原始 tool_call id，不能现造 confirm_ 帧");
        assertEquals("reasoning-abc", frame.reasoningContent(),
                "必须原样回放 reasoning_content");
        assertEquals(0, executed.get(), "用户拒绝时不该执行工具");
    }

    @Test
    @DisplayName("技能正文/资料不参与工具结果压缩（模板不能被压成一句占位符）")
    void skillResourcesBypassCompression() {
        String big = "x".repeat(5000);
        StubTool tool = new StubTool("read_skill_resource", ToolResult.ok(big));
        ScriptedChatService chat = new ScriptedChatService(
                toolTurn("read_skill_resource", "{}"), ModelTurn.text("ok"));
        ToolCallingService service = new ToolCallingService(chat, registry(tool),
                Runnable::run, noCompressor(), noGovernance(), freshStore(),
                ToolAuditLog.noop(), toolProperties(true, 2000, 6000));

        service.ask(KEY, userSays("读模板"));

        String content = toolMessageContents(chat.calls.get(1)).get(0);
        assertEquals(5000, inner(content).length(), "技能资料应原样回填，不被压缩");
    }

    @Test
    @DisplayName("模型输出伪工具文本（DSML）时，替换为兜底话术，不把标记给用户")
    void replacesPseudoToolText() {
        ScriptedChatService chat = new ScriptedChatService(
                ModelTurn.text("<｜｜DSML｜｜ calls><｜｜DSML｜｜ invoke name=\"x\">"));
        ToolCallingService service = new ToolCallingService(chat, registry(),
                Runnable::run, noCompressor(), noGovernance(), freshStore(),
                ToolAuditLog.noop(), toolProperties(true, 2000, 6000));

        String reply = service.ask(KEY, userSays("hi"));

        assertFalse(reply.contains("DSML"));
        assertTrue(reply.contains("没有整理出完整结果"));
    }

    @Test
    @DisplayName("流式编排：思考/正文/工具状态按顺序回调，产出最后一轮用时")
    void streamsThroughToolRounds() {
        ScriptedStreamChatService chat = new ScriptedStreamChatService(
                new ModelTurn(null, List.of(new ToolCall("c1", "demo_tool", "{}")), "先查", 900),
                new ModelTurn("完成", List.of(), "再答", 1234));
        List<String> events = new ArrayList<>();
        List<String> statuses = new ArrayList<>();
        ChatStreamListener listener = new ChatStreamListener() {
            @Override
            public void onReasoning(String delta) {
                events.add("r:" + delta);
            }

            @Override
            public void onContent(String delta) {
                events.add("c:" + delta);
            }

            @Override
            public void onStatus(String text) {
                statuses.add(text);
            }
        };
        ToolCallingService service = new ToolCallingService(chat,
                registry(new StubTool("demo_tool", ToolResult.ok("ok"))),
                Runnable::run, noCompressor(), noGovernance(), freshStore(),
                ToolAuditLog.noop(), toolProperties(true, 2000, 6000));

        ToolCallingService.AskResult result = service.askOutcomeStreaming(
                KEY, userSays("hi"), "t", ToolContext.ofRoles(Set.of("admin")), listener);

        assertEquals("完成", result.reply());
        assertEquals(1234, result.reasoningMs());
        assertEquals(List.of("r:先查", "r:再答", "c:完成"), events, "跨轮思考都回调，正文在其后");
        assertTrue(statuses.stream().anyMatch(s -> s.contains("demo_tool")), statuses.toString());
    }

    @Test
    @DisplayName("流式路径下危险工具同样暂停并返回 pending")
    void streamingStillPausesDangerousTools() {
        ScriptedStreamChatService chat = new ScriptedStreamChatService(
                new ModelTurn(null, List.of(new ToolCall("c1", "delete_device", "{}")), "想删", 500));
        ToolCallingService service = new ToolCallingService(chat,
                registry(deleteDeviceTool(new AtomicInteger())),
                governanceAllowingAdminOnly(), freshStore());

        ToolCallingService.AskResult result = service.askOutcomeStreaming(
                KEY, userSays("删"), "t", ToolContext.ofRoles(Set.of("admin")),
                ChatStreamListener.NOOP);

        assertNotNull(result.pending());
        assertEquals("delete_device", result.pending().tool());
    }

    @Test
    @DisplayName("工具裁剪：权限不足的工具不下发，权限满足的保留")
    void hidesToolsWithoutPermission() {
        StubTool taskList = new StubTool("drone__task_list", ToolResult.ok("任务"));
        StubTool userList = new StubTool("drone__user_list", ToolResult.ok("员工"));
        ScriptedChatService chat = new ScriptedChatService(ModelTurn.text("好"));

        ToolProperties props = new ToolProperties(true, 3, false, 4, Duration.ofSeconds(15),
                List.of(), true, 2000, 6000, 32768, 30000, 1, List.of(), Map.of(),
                List.of(new ToolVisibilityRule("drone__user_*", List.of("staff:list"))),
                null, Duration.ofMinutes(5), null);
        ToolCallingService service = new ToolCallingService(chat, registry(taskList, userList),
                Runnable::run, noCompressor(), noGovernance(), freshStore(), ToolAuditLog.noop(), props);

        service.askOutcome(KEY, userSays("看看任务"), "t",
                new ToolContext(Set.of("pilot"), Set.of("task:list")));

        List<String> offered = chat.toolsPerCall.get(0).stream().map(ToolDefinition::name).toList();
        assertEquals(List.of("drone__task_list"), offered, "没有 staff:list 就不该看到员工工具");
    }

    @Test
    @DisplayName("工具裁剪：危险工具只授予 admin，飞手连定义都看不到")
    void hidesDangerousToolsFromOtherRoles() {
        StubTool delete = new StubTool("delete_device", ToolResult.ok("已删"));
        StubTool read = new StubTool("demo_tool", ToolResult.ok("读"));
        ScriptedChatService chat = new ScriptedChatService(ModelTurn.text("好"));
        ToolGovernance adminOnly = new ToolGovernance(
                List.of(new ToolDescriptor("delete_device", "删除设备", "{}", false, false)),
                List.of("delete_device"),
                Map.of("admin", List.of("delete_device")));

        ToolCallingService service = new ToolCallingService(chat, registry(delete, read),
                Runnable::run, noCompressor(), adminOnly, freshStore(), ToolAuditLog.noop(),
                toolProperties(true, 2000, 6000));

        service.askOutcome(KEY, userSays("删掉设备"), "t",
                new ToolContext(Set.of("pilot"), Set.of("device:list")));

        List<String> offered = chat.toolsPerCall.get(0).stream().map(ToolDefinition::name).toList();
        assertEquals(List.of("demo_tool"), offered, "危险工具不在 role-tools 名单里的角色看不到");
    }

    @Test
    @DisplayName("Skill 资源：12.3KB 日报模板在预算内完整返回，不被压缩")
    void skillTemplateStaysIntact() {
        String template = "<html>" + "x".repeat(12340) + "</html>";
        StubTool read = new StubTool("read_skill_resource", ToolResult.ok(template));
        ScriptedChatService chat = new ScriptedChatService(
                toolTurn("read_skill_resource", "{}"), ModelTurn.text("好"));
        ToolCallingService service = new ToolCallingService(chat, registry(read),
                Runnable::run, noCompressor(), noGovernance(), freshStore(), ToolAuditLog.noop(),
                toolProperties(true, 2000, 6000));

        service.ask(KEY, userSays("读模板"));

        assertEquals(template, inner(toolMessageContents(chat.calls.get(1)).get(0)),
                "模板必须原样返回，不能被通用预算压坏");
    }

    @Test
    @DisplayName("Skill 资源：单次超上限时硬截断，带标记且长度不超上限")
    void skillResourceIsTruncatedAtCap() {
        StubTool read = new StubTool("read_skill_resource", ToolResult.ok("y".repeat(5000)));
        ScriptedChatService chat = new ScriptedChatService(
                toolTurn("read_skill_resource", "{}"), ModelTurn.text("好"));
        ToolCallingService service = new ToolCallingService(chat, registry(read),
                Runnable::run, noCompressor(), noGovernance(), freshStore(), ToolAuditLog.noop(),
                toolProperties(true, 2000, 6000, 1000, 30000));

        service.ask(KEY, userSays("读大文件"));

        String result = inner(toolMessageContents(chat.calls.get(1)).get(0));
        assertTrue(result.length() <= 1000, "长度应受单次上限约束，实际=" + result.length());
        assertTrue(result.endsWith(ToolCallingService.SKILL_TRUNCATED), "必须告诉模型内容被截断");
    }

    @Test
    @DisplayName("Skill 资源：累计预算只给剩余额度，第二条被截到剩余")
    void skillBudgetIsCumulative() {
        StubTool body = new StubTool("use_skill", ToolResult.ok("z".repeat(15000)));
        StubTool read = new StubTool("read_skill_resource", ToolResult.ok("z".repeat(15000)));
        ScriptedChatService chat = new ScriptedChatService(
                toolTurnMulti("use_skill", "read_skill_resource"), ModelTurn.text("好"));
        ToolCallingService service = new ToolCallingService(chat, registry(body, read),
                Runnable::run, noCompressor(), noGovernance(), freshStore(), ToolAuditLog.noop(),
                toolProperties(true, 2000, 6000, 32768, 20000));

        service.ask(KEY, userSays("连读两份"));

        List<String> results = toolMessageContents(chat.calls.get(1)).stream().map(ToolCallingServiceTest::inner).toList();
        assertEquals(15000, results.get(0).length(), "第一条在剩余额度内，应完整");
        assertTrue(results.get(1).length() <= 5000, "第二条只能用剩下的 5000，实际=" + results.get(1).length());
        assertTrue(results.get(1).endsWith(ToolCallingService.SKILL_TRUNCATED));
    }

    @Test
    @DisplayName("Skill 资源：累计预算用尽时返回占位，而不是空串")
    void skillBudgetExhaustedReturnsPlaceholder() {
        StubTool body = new StubTool("use_skill", ToolResult.ok("a".repeat(2000)));
        StubTool read = new StubTool("read_skill_resource", ToolResult.ok("b".repeat(2000)));
        ScriptedChatService chat = new ScriptedChatService(
                toolTurnMulti("use_skill", "read_skill_resource"), ModelTurn.text("好"));
        ToolCallingService service = new ToolCallingService(chat, registry(body, read),
                Runnable::run, noCompressor(), noGovernance(), freshStore(), ToolAuditLog.noop(),
                toolProperties(true, 2000, 6000, 32768, 1000));

        service.ask(KEY, userSays("连读两份"));

        List<String> results = toolMessageContents(chat.calls.get(1)).stream().map(ToolCallingServiceTest::inner).toList();
        assertEquals(1000, results.get(0).length(), "第一条吃满累计预算");
        assertEquals(ToolCallingService.SKILL_SKIPPED, results.get(1), "用尽时给占位，不能让模型以为资源是空的");
    }

    @Test
    @DisplayName("截断不切开代理对：emoji 不会留下半个字符")
    void truncationDoesNotSplitSurrogatePair() {
        String content = "a".repeat(8) + "😀" + "b";
        String result = ToolCallingService.truncateWithMarker(content, 10, "M");

        assertEquals(9, result.length(), "回退一位后 8 个字符 + 标记");
        assertFalse(Character.isHighSurrogate(result.charAt(result.length() - 2)));
    }

    @Test
    @DisplayName("取消：模型返回后立即停止，工具不执行")
    void cancellationAfterModelStopsBeforeTools() {
        CancellationSignal signal = new CancellationSignal();
        StubTool tool = new StubTool("demo_tool", ToolResult.ok("结果"));
        ChatService chat = new ChatService(
                new DeepSeekProperties("http://unused", "k", "m", null, false),
                TestPrompts.of("sys"), new ObjectMapper()) {
            @Override
            public ModelTurn chatStep(SessionKey key, List<ContextMessage> messages,
                                      List<ToolDefinition> tools, String traceId,
                                      java.util.Set<String> permissions) {
                signal.cancel();
                return toolTurn("demo_tool", "{}");
            }
        };

        ToolCallingService.AskResult result = new ToolCallingService(chat, registry(false, tool))
                .askOutcome(KEY, userSays("查"), "t", ToolContext.anonymous(), signal);

        assertTrue(result.reply().contains("已取消"), result.reply());
        assertEquals(0, tool.invocations.size(), "取消后不应执行工具");
        assertNotNull(result.report());
        assertTrue(result.report().cancelled());
        assertTrue(result.report().notStarted().stream()
                        .anyMatch(step -> "demo_tool".equals(step.tool())),
                "本轮未执行的工具应出现在 notStarted 中");
    }

    @Test
    @DisplayName("取消：请求开始前已取消，不调用模型")
    void cancellationBeforeStartSkipsModel() {
        CancellationSignal signal = new CancellationSignal();
        signal.cancel();
        ScriptedChatService chat = new ScriptedChatService();

        ToolCallingService.AskResult result = new ToolCallingService(chat,
                registry(new StubTool("demo_tool", ToolResult.ok("结果"))))
                .askOutcome(KEY, userSays("查"), "t", ToolContext.anonymous(), signal);

        assertTrue(result.reply().contains("已取消"), result.reply());
        assertTrue(chat.calls.isEmpty(), "已取消就不应再调模型");
    }

    @Test
    @DisplayName("预算：单请求超限后停止工具循环，只调一次模型且不执行工具")
    void stopsWhenRequestBudgetExceeded() {
        StubTool tool = new StubTool("demo_tool", ToolResult.ok("结果"));
        ScriptedChatService chat = new ScriptedChatService(
                toolTurn("demo_tool", "{}"), ModelTurn.text("不该走到第二轮"));

        BudgetService budget = new BudgetService(
                new BudgetProperties(true, 100, 1000), userId -> 0L, traceId -> 200L);
        ToolCallingService service = new ToolCallingService(chat, registry(tool),
                Runnable::run, noCompressor(), noGovernance(), freshStore(), ToolAuditLog.noop(),
                toolProperties(true, 2000, 6000), TraceRecorder.noop(), budget);

        String reply = service.ask(KEY, userSays("查一下"));

        assertTrue(reply.contains("预算"), reply);
        assertEquals(1, chat.calls.size(), "超限后不应再调模型");
        assertEquals(0, tool.invocations.size(), "超限后不应执行工具");
    }

    @Test
    @DisplayName("账单分类：工具结果帧标记 TOOL_RESULT 并带工具名")
    void tagsToolResultWithToolName() {
        StubTool tool = new StubTool("demo_tool", ToolResult.ok("结果"));
        ScriptedChatService chat = new ScriptedChatService(
                toolTurn("demo_tool", "{}"), ModelTurn.text("好"));

        new ToolCallingService(chat, registry(tool)).ask(KEY, userSays("查一下"));

        List<ContextMessage> second = chat.framed.get(1);
        ContextMessage frame = second.stream()
                .filter(message -> message.kind() == MessageKind.TOOL_CALL)
                .findFirst().orElseThrow();
        ContextMessage result = second.stream()
                .filter(message -> message.kind() == MessageKind.TOOL_RESULT)
                .findFirst().orElseThrow();
        assertNull(frame.toolName(), "工具调用请求帧没有「结果工具名」");
        assertEquals("demo_tool", result.toolName(), "工具结果必须带工具名，账单才能归因");
    }

    @Test
    @DisplayName("取消：报告列出已完成的写操作与未开始的写操作，并给出 resumeToken")
    void cancellationReportCarriesCompletedWritesAndResumeToken(@TempDir Path dir) throws Exception {
        CancellationSignal signal = new CancellationSignal();
        Tool userAdd = new Tool() {
            @Override
            public String name() {
                return "user_add";
            }

            @Override
            public ToolResult invoke(String argumentsJson) {
                // 第一个写操作执行完时用户点了取消：第二个不应再开始
                signal.cancel();
                return ToolResult.ok("{\"state\":200,\"data\":{\"id\":123}}");
            }
        };
        StubTool deviceAdd = new StubTool("device_add", ToolResult.ok("{\"id\":456}"));
        ScriptedChatService chat = new ScriptedChatService(
                toolTurnMulti("user_add", "device_add"));

        ToolCallingService service = new ToolCallingService(chat, registry(false, userAdd, deviceAdd),
                Runnable::run, noCompressor(), noGovernance(), freshStore(), ToolAuditLog.noop(),
                toolProperties(true, 2000, 6000), TraceRecorder.noop(), BudgetService.noop(),
                new WriteJournal(dir, new ObjectMapper()));

        ToolCallingService.AskResult result = service.askOutcome(KEY, userSays("建用户再建设备"),
                "t", ToolContext.anonymous(), signal);

        assertTrue(result.reply().contains("已取消"), result.reply());
        assertTrue(deviceAdd.invocations.isEmpty(), "取消后第二个写工具不应执行");
        assertEquals(1, result.report().completed().size(), "只应报告已完成的 user_add");
        assertEquals("user_add", result.report().completed().get(0).tool());
        assertEquals("123", result.report().completed().get(0).objectId());
        assertTrue(result.report().notStarted().stream()
                        .anyMatch(step -> "device_add".equals(step.tool())),
                "未执行的 device_add 应进入 notStarted");
        assertNotNull(result.report().resumeToken(), "有已完成的写操作就必须给续跑令牌");
        assertTrue(result.report().resumeToken().operations().stream()
                .anyMatch(op -> "user_add".equals(op.tool())));
        assertEquals("123", result.report().resumeToken().captured().stream()
                .filter(step -> "user_add".equals(step.tool()))
                .findFirst().orElseThrow().objectId());

        String journal = Files.readString(dir.resolve("operations-" + LocalDate.now() + ".jsonl"));
        assertTrue(journal.contains("\"status\":\"succeeded\""), "已执行的写操作要有终态");
        assertTrue(journal.contains("\"status\":\"cancelled\""),
                "未执行的写操作也要收尾，不能停在 started");
    }

    @Test
    @DisplayName("取消：超时的写操作记为结果未知，不冒充已完成")
    void cancellationReportMarksTimeoutAsUnknown() {
        CancellationSignal signal = new CancellationSignal();
        Tool deviceAdd = new Tool() {
            @Override
            public String name() {
                return "device_add";
            }

            @Override
            public ToolResult invoke(String argumentsJson) {
                signal.cancel();
                return ToolResult.failed("工具执行超时（超过 15000 毫秒）");
            }
        };
        ScriptedChatService chat = new ScriptedChatService(toolTurn("device_add", "{}"));

        ToolCallingService service = new ToolCallingService(chat, registry(false, deviceAdd),
                Runnable::run, noCompressor(), noGovernance(), freshStore(), ToolAuditLog.noop(),
                toolProperties(true, 2000, 6000));

        ToolCallingService.AskResult result = service.askOutcome(KEY, userSays("建设备"),
                "t", ToolContext.anonymous(), signal);

        assertTrue(result.report().completed().isEmpty(), "超时不能算已完成");
        assertEquals(1, result.report().unknown().size(), "超时必须归入结果未知");
        assertEquals("device_add", result.report().unknown().get(0).tool());
        assertTrue(result.report().failed().isEmpty());
        assertNull(result.report().resumeToken(), "没有成功的写操作就没有续跑令牌");
    }

    @Test
    @DisplayName("取消：业务失败的写操作记为失败，与「结果未知」分开")
    void cancellationReportMarksBusinessFailure() {
        CancellationSignal signal = new CancellationSignal();
        Tool userAdd = new Tool() {
            @Override
            public String name() {
                return "user_add";
            }

            @Override
            public ToolResult invoke(String argumentsJson) {
                signal.cancel();
                return ToolResult.failed("用户名已存在");
            }
        };
        ScriptedChatService chat = new ScriptedChatService(toolTurn("user_add", "{}"));

        ToolCallingService service = new ToolCallingService(chat, registry(false, userAdd),
                Runnable::run, noCompressor(), noGovernance(), freshStore(), ToolAuditLog.noop(),
                toolProperties(true, 2000, 6000));

        ToolCallingService.AskResult result = service.askOutcome(KEY, userSays("建用户"),
                "t", ToolContext.anonymous(), signal);

        assertEquals(1, result.report().failed().size(), "业务错误确定未生效，应记为失败");
        assertTrue(result.report().unknown().isEmpty());
        assertTrue(result.report().completed().isEmpty());
    }

    @Test
    @DisplayName("写操作：operationId 注入执行上下文，续跑时复用同一个")
    void operationIdIsInjectedAndReusedOnResume() {
        List<String> seen = new ArrayList<>();
        Tool userAdd = new Tool() {
            @Override
            public String name() {
                return "user_add";
            }

            @Override
            public ToolResult invoke(String argumentsJson) {
                return invoke(argumentsJson, ToolContext.anonymous());
            }

            @Override
            public ToolResult invoke(String argumentsJson, ToolContext context) {
                seen.add(context.operationId());
                return ToolResult.ok("{\"id\":123}");
            }
        };

        ScriptedChatService first = new ScriptedChatService(toolTurn("user_add", "{}"),
                ModelTurn.text("好"));
        new ToolCallingService(first, registry(false, userAdd)).ask(KEY, userSays("建用户"));

        assertNotNull(seen.get(0), "写操作的 operationId 必须落进工具上下文");
        assertTrue(seen.get(0).startsWith("op-"));

        ScriptedChatService second = new ScriptedChatService(toolTurn("user_add", "{}"),
                ModelTurn.text("好"));
        CancellationReport.ResumeToken token = new CancellationReport.ResumeToken(
                List.of(new CancellationReport.Operation(
                        "user_add", "op-fixed", WriteJournal.digest("{}"))),
                List.of());
        new ToolCallingService(second, registry(false, userAdd)).askOutcome(KEY, userSays("继续"),
                "t2", ToolContext.anonymous(), CancellationSignal.never(), token);

        assertEquals("op-fixed", seen.get(1), "续跑必须复用 token 里的 operationId，MCP 才能幂等");
    }

    @Test
    @DisplayName("写台账：同一个 operationId 先记 started，执行完再记终态")
    void writeJournalRecordsStartedAndFinalStatus(@TempDir Path dir) throws Exception {
        StubTool userAdd = new StubTool("user_add", ToolResult.ok("{\"id\":123}"));
        ScriptedChatService chat = new ScriptedChatService(toolTurn("user_add", "{}"),
                ModelTurn.text("好"));

        ToolCallingService service = new ToolCallingService(chat, registry(false, userAdd),
                Runnable::run, noCompressor(), noGovernance(), freshStore(), ToolAuditLog.noop(),
                toolProperties(true, 2000, 6000), TraceRecorder.noop(), BudgetService.noop(),
                new WriteJournal(dir, new ObjectMapper()));

        service.ask(KEY, userSays("建用户"));

        String journal = Files.readString(dir.resolve("operations-" + LocalDate.now() + ".jsonl"));
        assertTrue(journal.contains("\"started\""), "执行前应记 started");
        assertTrue(journal.contains("\"succeeded\""), "执行后应记终态");
        assertTrue(journal.contains("\"objectId\":\"123\""), "台账应带对象 ID，供撤销追溯");
    }

    @Test
    @DisplayName("续跑令牌：同一工具不同参数各记一条，不互相覆盖")
    void resumeTokenKeepsEachCallForTheSameTool() {
        CancellationSignal signal = new CancellationSignal();
        List<String> args = new ArrayList<>();
        Tool userAdd = new Tool() {
            @Override
            public String name() {
                return "user_add";
            }

            @Override
            public ToolResult invoke(String argumentsJson) {
                args.add(argumentsJson);
                if (args.size() == 2) {
                    signal.cancel();
                }
                return ToolResult.ok("{\"data\":{\"id\":" + (100 + args.size()) + "}}");
            }
        };
        ModelTurn turn = new ModelTurn(null, List.of(
                new ToolCall("c1", "user_add", "{\"name\":\"A\"}"),
                new ToolCall("c2", "user_add", "{\"name\":\"B\"}")));
        ScriptedChatService chat = new ScriptedChatService(turn);
        ToolCallingService service = new ToolCallingService(chat, registry(false, userAdd),
                Runnable::run, noCompressor(), noGovernance(), freshStore(), ToolAuditLog.noop(),
                toolProperties(true, 2000, 6000));

        ToolCallingService.AskResult result = service.askOutcome(KEY, userSays("建两个用户"),
                "t", ToolContext.anonymous(), signal);

        assertEquals(2, result.report().completed().size(), "两次调用都应完成");
        assertNotNull(result.report().resumeToken(), "有成功的写操作就要给续跑令牌");
        assertEquals(2, result.report().resumeToken().captured().size(), "两次创建的对象 ID 都要保留");
        assertEquals(2, result.report().resumeToken().operations().stream()
                .map(CancellationReport.Operation::argsDigest).distinct().count(),
                "参数不同必须是两条独立记录");
        assertEquals("101", result.report().resumeToken().captured().get(0).objectId());
        assertEquals("102", result.report().resumeToken().captured().get(1).objectId());
    }

    @Test
    @DisplayName("取消：在途读工具立即放弃等待，不等到 15s 超时")
    void cancellationAbandonsInFlightRead() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            CancellationSignal signal = new CancellationSignal();
            Tool slowRead = sleepingTool("slow_read", 2_000);
            ScriptedChatService chat = new ScriptedChatService(toolTurn("slow_read", "{}"));
            ToolCallingService service = new ToolCallingService(chat, registry(slowRead), pool,
                    false, Duration.ofSeconds(15), 3);
            Thread canceller = new Thread(() -> {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                signal.cancel();
            });
            canceller.start();

            long startNanos = System.nanoTime();
            ToolCallingService.AskResult result = service.askOutcome(KEY, userSays("读数据"),
                    "t", ToolContext.anonymous(), signal);
            long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;

            assertTrue(result.reply().contains("已取消"), result.reply());
            assertTrue(elapsedMs < 1_000, "应放弃等待而不是等到超时，实际耗时 " + elapsedMs + "ms");
            canceller.join(1_000);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("Plan：命中内置计划时严格按计划执行，不走 ReAct 模型循环")
    void builtinPlanExecutesWithoutApproval() {
        ExecutorService planPool = Executors.newSingleThreadExecutor();
        try {
            StubTool tool = new StubTool("demo_tool", ToolResult.ok("ok"));
            ToolRegistry registry = registry(false, tool);
            Plan plan = new Plan(null, "test-plan", Plan.Origin.BUILTIN, 1, "goal", null,
                    List.of("测试计划"), List.of(planStep("demo_tool")));
            PlanCatalog catalog = PlanCatalog.fromPlans(List.of(plan), registry,
                    SkillCatalog.empty(), new ObjectMapper());
            ScriptedChatService chat = new ScriptedChatService();
            PlanExecutor executor = new PlanExecutor(chat, SkillCatalog.empty(), registry,
                    noGovernance(), new ObjectMapper(), planPool, 1, WriteJournal.noop());
            PlanService planService = new PlanService(catalog, PlanStore.noop(), null, executor);
            ToolCallingService service = withPlan(chat, registry, planService);

            ToolCallingService.AskResult result = service.askOutcome(KEY, userSays("执行测试计划"),
                    "t", ToolContext.anonymous());

            assertEquals(1, tool.invocations.size());
            assertNotNull(result.plan());
            assertEquals("COMPLETED", result.plan().status());
            assertTrue(chat.calls.isEmpty(), "内置计划不应再走 ReAct 模型循环");
        } finally {
            planPool.shutdownNow();
        }
    }

    @Test
    @DisplayName("Plan：生成计划先返回待审批；批准后才执行")
    void generatedPlanRequiresApproval() {
        ExecutorService planPool = Executors.newSingleThreadExecutor();
        try {
            StubTool tool = new StubTool("demo_tool", ToolResult.ok("ok"));
            ToolRegistry registry = registry(false, tool);
            ObjectMapper mapper = new ObjectMapper();
            PlanCatalog catalog = PlanCatalog.fromPlans(List.of(), registry,
                    SkillCatalog.empty(), mapper);
            String json = "{\"name\":\"demo\",\"goal\":\"g\",\"version\":1,\"steps\":["
                    + "{\"id\":\"s1\",\"title\":\"查\",\"kind\":\"TOOL\",\"tool\":\"demo_tool\","
                    + "\"args\":{},\"dependsOn\":[]}]}";
            ChatService planChat = new ChatService(
                    new DeepSeekProperties("http://unused", "k", "m", null, false),
                    TestPrompts.of("sys"), mapper) {
                @Override
                public String chat(SessionKey key, List<ContextMessage> messages,
                                   java.util.Set<String> permissions, String traceId) {
                    return json;
                }
            };
            PromptCatalog prompts = PromptCatalog.load("classpath*:prompts/**/*.md");
            PlanPlanner planner = new PlanPlanner(planChat, registry, noGovernance(), catalog,
                    prompts, mapper);
            PlanExecutor executor = new PlanExecutor(planChat, SkillCatalog.empty(), registry,
                    noGovernance(), mapper, planPool, 1, WriteJournal.noop());
            PlanService planService = new PlanService(catalog, PlanStore.noop(), planner, executor);
            ToolCallingService service = withPlan(planChat, registry, planService);

            ToolCallingService.AskResult pending = service.askOutcome(KEY,
                    userSays("先给方案：用 demo_tool 查一下"), "t", ToolContext.anonymous());

            assertNotNull(pending.plan());
            assertEquals("PENDING_APPROVAL", pending.plan().status());
            assertTrue(tool.invocations.isEmpty(), "未批准前不应执行任何工具");

            ToolCallingService.AskResult done = service.confirmPlan(KEY,
                    pending.plan().planId(), true, "t2", ToolContext.anonymous(),
                    new CancellationSignal());

            assertEquals(1, tool.invocations.size(), "批准后应执行计划步骤");
            assertNotNull(done.plan());
            assertEquals("COMPLETED", done.plan().status());
        } finally {
            planPool.shutdownNow();
        }
    }

    @Test
    @DisplayName("路由：复杂只读任务直接走子代理，不进入主模型 ReAct")
    void routesComplexReadonlyToSubAgent() {
        ExecutorService planPool = Executors.newSingleThreadExecutor();
        try {
            StubTool tool = new StubTool("demo_tool", ToolResult.ok("data"));
            ToolRegistry registry = registry(tool);
            SubAgentDefinition definition = new SubAgentDefinition("ops-analyst", "运营分析",
                    List.of("demo_tool"), 2, 30, true, "你是测试子代理，只返回摘要。");
            SubAgentCatalog catalog = SubAgentCatalog.fromDefinitions(List.of(definition));
            ScriptedChatService subChat = new ScriptedChatService(ModelTurn.text("子代理摘要"));
            SubAgentRunner runner = new SubAgentRunner(subChat, () -> registry,
                    () -> noGovernance(), TraceRecorder.noop(), BudgetService.noop(),
                    new SubAgentProperties(30000, 0), planPool);
            SubAgentService subService = new SubAgentService(catalog, runner);
            PlanCatalog plans = PlanCatalog.fromPlans(List.of(), registry, SkillCatalog.empty(),
                    new ObjectMapper());
            TaskRouter router = new TaskRouter(plans, true);
            ScriptedChatService mainChat = new ScriptedChatService();

            ToolCallingService service = new ToolCallingService(mainChat, registry, Runnable::run,
                    noCompressor(), noGovernance(), freshStore(), ToolAuditLog.noop(),
                    toolProperties(true, 2000, 6000), TraceRecorder.noop(), BudgetService.noop(),
                    WriteJournal.noop(), PlanService.noop(), router, subService);

            ToolCallingService.AskResult result = service.askOutcome(KEY,
                    userSays("对比本周和上周的任务完成率和告警数量"), "t", ToolContext.anonymous());

            assertEquals("子代理摘要", result.reply());
            assertTrue(mainChat.calls.isEmpty(), "路由到子代理后不应再调主模型");
        } finally {
            planPool.shutdownNow();
        }
    }

    private static PlanStep planStep(String tool) {
        return new PlanStep("s1", "步骤", PlanStep.Kind.TOOL, tool, Map.of(), List.of(),
                null, null);
    }

    private static ToolCallingService withPlan(ChatService chat, ToolRegistry registry,
                                               PlanService planService) {
        return new ToolCallingService(chat, registry, Runnable::run, noCompressor(),
                noGovernance(), freshStore(), ToolAuditLog.noop(),
                toolProperties(true, 2000, 6000), TraceRecorder.noop(), BudgetService.noop(),
                WriteJournal.noop(), planService);
    }
}
