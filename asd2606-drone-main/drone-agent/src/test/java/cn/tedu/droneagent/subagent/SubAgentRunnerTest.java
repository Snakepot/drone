package cn.tedu.droneagent.subagent;

import cn.tedu.droneagent.budget.BudgetService;
import cn.tedu.droneagent.config.BudgetProperties;
import cn.tedu.droneagent.config.DeepSeekProperties;
import cn.tedu.droneagent.config.SubAgentProperties;
import cn.tedu.droneagent.context.ContextMessage;
import cn.tedu.droneagent.memory.SessionKey;
import cn.tedu.droneagent.prompt.TestPrompts;
import cn.tedu.droneagent.service.ChatService;
import cn.tedu.droneagent.tool.ModelMessage;
import cn.tedu.droneagent.tool.ModelTurn;
import cn.tedu.droneagent.tool.Tool;
import cn.tedu.droneagent.tool.ToolCall;
import cn.tedu.droneagent.tool.ToolContext;
import cn.tedu.droneagent.tool.ToolDescriptor;
import cn.tedu.droneagent.tool.ToolDefinition;
import cn.tedu.droneagent.tool.ToolGovernance;
import cn.tedu.droneagent.tool.ToolRegistry;
import cn.tedu.droneagent.tool.ToolResult;
import cn.tedu.droneagent.trace.TraceRecorder;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SubAgentRunnerTest {

    private final ExecutorService pool = Executors.newFixedThreadPool(2);

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
    }

    @Test
    @DisplayName("独立上下文：只带子 Agent 提示词与任务，并返回摘要")
    void runsInIsolatedContext() {
        List<String> invocations = new ArrayList<>();
        ToolRegistry registry = registry(invocations);
        ScriptedChatService chat = new ScriptedChatService(
                toolTurn("read_tool"), ModelTurn.text("分析完成：A 比 B 高 10%"));
        SubAgentRunner runner = runner(chat, registry);

        SubAgentResult result = runner.run(definition(List.of("read_tool"), 2),
                "对比 A 和 B", ToolContext.anonymous(), "trace-1");

        assertEquals("ok", result.status());
        assertTrue(result.summary().contains("分析完成"));
        assertEquals(2, result.rounds());
        assertEquals(1, result.toolCalls());
        assertEquals(List.of("read_tool"), result.toolsUsed());
        assertEquals(1, invocations.size());

        List<ModelMessage> firstCall = chat.calls.get(0);
        assertEquals(2, firstCall.size(), "子 Agent 只应看到 system + task");
        assertEquals("system", firstCall.get(0).role());
        assertEquals("user", firstCall.get(1).role());
        assertEquals("对比 A 和 B", firstCall.get(1).content());
    }

    @Test
    @DisplayName("白名单外/写工具：不执行，错误回填给子 Agent")
    void rejectsNonAllowlistedAndWriteTools() {
        List<String> invocations = new ArrayList<>();
        ToolRegistry registry = registry(invocations);
        ScriptedChatService chat = new ScriptedChatService(
                toolTurn("write_tool"), ModelTurn.text("无法完成：工具不可用"));
        SubAgentRunner runner = runner(chat, registry);

        SubAgentResult result = runner.run(definition(List.of("read_tool"), 2),
                "写一条数据", ToolContext.anonymous(), "trace-2");

        assertEquals("ok", result.status());
        assertEquals(0, invocations.size(), "写工具不应被执行");
        assertTrue(result.summary().contains("无法完成"));
    }

    @Test
    @DisplayName("达到轮次上限：停止并返回未完成")
    void stopsAtMaxRounds() {
        List<String> invocations = new ArrayList<>();
        ToolRegistry registry = registry(invocations);
        ScriptedChatService chat = new ScriptedChatService(
                toolTurn("read_tool"), toolTurn("read_tool"), toolTurn("read_tool"));
        SubAgentRunner runner = runner(chat, registry);

        SubAgentResult result = runner.run(definition(List.of("read_tool"), 2),
                "一直查", ToolContext.anonymous(), "trace-3");

        assertEquals("max_rounds", result.status());
        assertTrue(result.summary().contains("轮次上限"));
        assertEquals(2, invocations.size(), "只应执行 maxRounds 次");
    }

    @Test
    @DisplayName("子 Agent 定义：P0 必须只读、工具白名单非空")
    void validatesDefinition() {
        SubAgentCatalog valid = SubAgentCatalog.fromDefinitions(List.of(
                definition(List.of("read_tool"), 2)));
        assertTrue(valid.find("test-agent").isPresent());

        assertThrows(IllegalArgumentException.class, () -> SubAgentCatalog.fromDefinitions(
                List.of(new SubAgentDefinition("bad", "d", List.of("read_tool"), 2, 30,
                        false, "测试提示词"))));
        assertThrows(IllegalArgumentException.class, () -> SubAgentCatalog.fromDefinitions(
                List.of(new SubAgentDefinition("bad", "d", List.of(), 2, 30,
                        true, "测试提示词"))));
    }

    @Test
    @DisplayName("Markdown 定义：frontmatter + 正文可加载")
    void loadsMarkdownDefinition() {
        SubAgentCatalog catalog = SubAgentCatalog.load("classpath*:subagents/*.md");
        SubAgentDefinition definition = catalog.find("ops-analyst").orElseThrow();

        assertEquals(6, definition.tools().size());
        assertTrue(definition.readOnly());
        assertEquals(4, definition.maxRounds());
        assertEquals(60, definition.timeoutSeconds());
        assertTrue(definition.prompt().contains("运营分析子代理"));
    }

    @Test
    @DisplayName("预算不足：启动前直接拒绝，不调用模型")
    void refusesWhenRemainingBudgetBelowThreshold() {
        List<String> invocations = new ArrayList<>();
        ToolRegistry registry = registry(invocations);
        ScriptedChatService chat = new ScriptedChatService();
        BudgetService budget = new BudgetService(new BudgetProperties(true, 100, 1000),
                userId -> 0L, traceId -> 95L);
        SubAgentRunner runner = new SubAgentRunner(chat, () -> registry, this::governance,
                TraceRecorder.noop(), budget, new SubAgentProperties(30000, 15), pool);

        SubAgentResult result = runner.run(definition(List.of("read_tool"), 2),
                "对比运营", ToolContext.anonymous(), "trace-budget");

        assertEquals("budget_exceeded", result.status());
        assertEquals(0, result.rounds());
        assertTrue(chat.calls.isEmpty(), "预算不足时不应调用模型");
    }

    private SubAgentRunner runner(ChatService chat, ToolRegistry registry) {
        return new SubAgentRunner(chat, () -> registry, this::governance,
                TraceRecorder.noop(), BudgetService.noop(),
                new SubAgentProperties(30000, 0), pool);
    }

    private ToolGovernance governance() {
        return new ToolGovernance(descriptors(), List.of(), Map.of());
    }

    private static SubAgentDefinition definition(List<String> tools, int maxRounds) {
        return new SubAgentDefinition("test-agent", "测试", tools, maxRounds, 30, true,
                "你是测试子代理，只返回摘要。");
    }

    private static ModelTurn toolTurn(String tool) {
        return new ModelTurn(null, List.of(new ToolCall("call-1", tool, "{}")));
    }

    private static ToolRegistry registry(List<String> invocations) {
        List<Tool> tools = List.of(tool("read_tool", true, invocations),
                tool("write_tool", false, invocations));
        return new ToolRegistry(tools, descriptors(), new ObjectMapper());
    }

    private static Tool tool(String name, boolean readOnly, List<String> invocations) {
        return new Tool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public ToolResult invoke(String argumentsJson) {
                invocations.add(name);
                return ToolResult.ok("{\"ok\":true}");
            }
        };
    }

    private static List<ToolDescriptor> descriptors() {
        return List.of(
                new ToolDescriptor("read_tool", "只读", "{}", true, true),
                new ToolDescriptor("write_tool", "写", "{}", false, false));
    }

    private static final class ScriptedChatService extends ChatService {
        private final Deque<ModelTurn> script;
        private final List<List<ModelMessage>> calls = new ArrayList<>();

        ScriptedChatService(ModelTurn... turns) {
            super(new DeepSeekProperties("http://unused", "k", "m", null, false),
                    TestPrompts.of("sys"), new ObjectMapper());
            this.script = new ArrayDeque<>(List.of(turns));
        }

        @Override
        public ModelTurn chatStep(SessionKey key, List<ContextMessage> messages,
                                  List<ToolDefinition> tools, String traceId,
                                  Set<String> permissions) {
            calls.add(ContextMessage.unwrap(messages));
            return script.poll();
        }
    }
}
