package cn.tedu.droneagent.plan;

import cn.tedu.droneagent.cancel.CancellationSignal;
import cn.tedu.droneagent.config.DeepSeekProperties;
import cn.tedu.droneagent.context.ContextMessage;
import cn.tedu.droneagent.memory.SessionKey;
import cn.tedu.droneagent.prompt.TestPrompts;
import cn.tedu.droneagent.service.ChatService;
import cn.tedu.droneagent.skill.SkillCatalog;
import cn.tedu.droneagent.tool.Tool;
import cn.tedu.droneagent.tool.ToolContext;
import cn.tedu.droneagent.tool.ToolDescriptor;
import cn.tedu.droneagent.tool.ToolGovernance;
import cn.tedu.droneagent.tool.ToolRegistry;
import cn.tedu.droneagent.tool.ToolResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanExecutorTest {

    private static final SessionKey KEY = new SessionKey(1L, "conv-1");

    private final ExecutorService pool = Executors.newFixedThreadPool(2);

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
    }

    @Test
    @DisplayName("按依赖执行，最后由 synthesize 产出答复")
    void executesStepsAndSynthesizes() {
        List<String> calls = new ArrayList<>();
        ToolRegistry registry = registry(calls, false, "a", "b");
        Plan plan = new Plan("p-1", "test", Plan.Origin.BUILTIN, 1, "goal", null, List.of(),
                List.of(
                        step("s1", "a", List.of()),
                        step("s2", "b", List.of()),
                        new PlanStep("s3", "汇总", PlanStep.Kind.SYNTHESIZE, null, Map.of(),
                                List.of("s1", "s2"), null, "汇总结果")));

        PlanExecutor executor = executor(registry, new ToolGovernance(descriptors(false, "a", "b"),
                List.of(), Map.of()));

        PlanExecutor.Result result = executor.execute(plan, KEY, "测试请求", ToolContext.anonymous(),
                "t-1", new CancellationSignal(), null, invoker(registry));

        assertEquals("final-reply", result.reply());
        assertEquals("COMPLETED", result.summary().status());
        assertTrue(result.summary().steps().stream()
                .allMatch(step -> "DONE".equals(step.status())));
        assertEquals(2, calls.size());
    }

    @Test
    @DisplayName("某步失败：后续步骤不再执行，计划标记失败")
    void failureStopsPlan() {
        List<String> calls = new ArrayList<>();
        ToolRegistry registry = registry(calls, true, "a", "b");
        Plan plan = new Plan("p-2", "test", Plan.Origin.BUILTIN, 1, "goal", null, List.of(),
                List.of(
                        step("s1", "a", List.of()),
                        step("s2", "b", List.of("s1"))));
        PlanExecutor executor = executor(registry, new ToolGovernance(descriptors(true, "a", "b"),
                List.of(), Map.of()));

        PlanExecutor.Result result = executor.execute(plan, KEY, "测试", ToolContext.anonymous(),
                "t-2", new CancellationSignal(), null, invoker(registry));

        assertEquals("FAILED", result.summary().status());
        assertEquals(1, calls.size(), "s1 失败后 s2 不应执行");
        assertEquals("SKIPPED", result.summary().steps().get(1).status());
    }

    @Test
    @DisplayName("执行前已取消：一步都不执行")
    void cancelledBeforeStart() {
        List<String> calls = new ArrayList<>();
        ToolRegistry registry = registry(calls, false, "a");
        Plan plan = new Plan("p-3", "test", Plan.Origin.BUILTIN, 1, "goal", null, List.of(),
                List.of(step("s1", "a", List.of())));
        CancellationSignal signal = new CancellationSignal();
        signal.cancel();

        PlanExecutor.Result result = executor(registry, new ToolGovernance(descriptors(false, "a"),
                List.of(), Map.of())).execute(plan, KEY, "测试", ToolContext.anonymous(),
                "t-3", signal, null, invoker(registry));

        assertEquals("CANCELLED", result.summary().status());
        assertTrue(calls.isEmpty());
    }

    @Test
    @DisplayName("危险工具：计划执行直接拒绝，不绕过确认")
    void rejectsDangerousTool() {
        List<String> calls = new ArrayList<>();
        ToolRegistry registry = registry(calls, false, "c");
        Plan plan = new Plan("p-4", "test", Plan.Origin.GENERATED, 1, "goal", null, List.of(),
                List.of(step("s1", "c", List.of())));
        ToolGovernance governance = new ToolGovernance(descriptors(false, "c"),
                List.of("c"), Map.of("admin", List.of("c")));

        PlanExecutor.Result result = executor(registry, governance).execute(plan, KEY, "测试",
                ToolContext.ofRoles(Set.of("admin")), "t-4", new CancellationSignal(), null,
                invoker(registry));

        assertEquals("FAILED", result.summary().status());
        assertTrue(calls.isEmpty(), "危险工具不应被执行");
        assertTrue(result.reply().contains("危险工具"), result.reply());
    }

    @Test
    @DisplayName("参数引用：后续步骤能取到前一步 data.id")
    void resolvesStepOutputReference() {
        List<String> deviceArgs = new ArrayList<>();
        Tool userAdd = new Tool() {
            @Override
            public String name() {
                return "user_add";
            }

            @Override
            public ToolResult invoke(String argumentsJson) {
                return ToolResult.ok("{\"state\":200,\"data\":{\"id\":123}}");
            }
        };
        Tool deviceAdd = new Tool() {
            @Override
            public String name() {
                return "device_add";
            }

            @Override
            public ToolResult invoke(String argumentsJson) {
                deviceArgs.add(argumentsJson);
                return ToolResult.ok("{\"id\":456}");
            }
        };
        List<ToolDescriptor> descriptors = List.of(
                new ToolDescriptor("user_add", "新增用户", "{}", false, false),
                new ToolDescriptor("device_add", "新增设备", "{}", false, false));
        ToolRegistry registry = new ToolRegistry(List.of(userAdd, deviceAdd), descriptors,
                new ObjectMapper());
        Plan plan = new Plan("p-5", "test", Plan.Origin.GENERATED, 1, "goal", "建用户再配设备",
                List.of(), List.of(
                new PlanStep("s1", "建用户", PlanStep.Kind.TOOL, "user_add", Map.of(),
                        List.of(), null, null),
                new PlanStep("s2", "配设备", PlanStep.Kind.TOOL, "device_add",
                        Map.of("owner", "${s1.id}"), List.of("s1"), null, null)));
        PlanExecutor executor = new PlanExecutor(new FinalReplyChatService(), SkillCatalog.empty(),
                registry, new ToolGovernance(descriptors, List.of(), Map.of()),
                new ObjectMapper(), pool, 2, cn.tedu.droneagent.journal.WriteJournal.noop());

        PlanExecutor.Result result = executor.execute(plan, KEY, "建用户再配设备",
                ToolContext.anonymous(), "t-5", new CancellationSignal(), null, invoker(registry));

        assertEquals("COMPLETED", result.summary().status());
        assertEquals(1, deviceArgs.size());
        assertTrue(deviceArgs.get(0).contains("123"), deviceArgs.get(0));
    }

    @Test
    @DisplayName("日期占位符：周边界解析；未知占位符直接失败")
    void resolvesWeekPlaceholdersAndRejectsUnknown() {
        List<String> args = new ArrayList<>();
        Tool tool = new Tool() {
            @Override
            public String name() {
                return "analytics";
            }

            @Override
            public ToolResult invoke(String argumentsJson) {
                args.add(argumentsJson);
                return ToolResult.ok("{}");
            }
        };
        List<ToolDescriptor> descriptors = List.of(
                new ToolDescriptor("analytics", "analytics", "{}", false, false));
        ToolRegistry registry = new ToolRegistry(List.of(tool), descriptors, new ObjectMapper());
        PlanExecutor executor = new PlanExecutor(new FinalReplyChatService(), SkillCatalog.empty(),
                registry, new ToolGovernance(descriptors, List.of(), Map.of()),
                new ObjectMapper(), pool, 1, cn.tedu.droneagent.journal.WriteJournal.noop());

        Plan valid = new Plan("p-6", "test", Plan.Origin.GENERATED, 1, "goal", "周对比",
                List.of(), List.of(new PlanStep("s1", "查", PlanStep.Kind.TOOL, "analytics",
                Map.of("from", "${week.this.start}", "to", "${week.this.end}"),
                List.of(), null, null)));
        PlanExecutor.Result ok = executor.execute(valid, KEY, "周对比", ToolContext.anonymous(),
                "t-6", new CancellationSignal(), null, invoker(registry));
        assertEquals("COMPLETED", ok.summary().status());
        assertTrue(args.get(0).contains("00:00:00"), args.get(0));
        assertTrue(!args.get(0).contains("${"), args.get(0));

        Plan unknown = new Plan("p-7", "test", Plan.Origin.GENERATED, 1, "goal", "未知占位符",
                List.of(), List.of(new PlanStep("s1", "查", PlanStep.Kind.TOOL, "analytics",
                Map.of("from", "${week.foo}"), List.of(), null, null)));
        PlanExecutor.Result failed = executor.execute(unknown, KEY, "未知占位符",
                ToolContext.anonymous(), "t-7", new CancellationSignal(), null, invoker(registry));
        assertEquals("FAILED", failed.summary().status());
        assertEquals(1, args.size(), "未知占位符不应发起工具调用");
    }

    private PlanExecutor executor(ToolRegistry registry, ToolGovernance governance) {
        return new PlanExecutor(new FinalReplyChatService(), SkillCatalog.empty(), registry,
                governance, new ObjectMapper(), pool, 2,
                cn.tedu.droneagent.journal.WriteJournal.noop());
    }

    private static PlanToolInvoker invoker(ToolRegistry registry) {
        return (call, context, operationId) ->
                registry.handler(call.name()).invoke(call.argumentsJson(), context);
    }

    private static PlanStep step(String id, String tool, List<String> dependsOn) {
        return new PlanStep(id, id, PlanStep.Kind.TOOL, tool, Map.of(), dependsOn, null, null);
    }

    private static ToolRegistry registry(List<String> calls, boolean failFirst, String... names) {
        List<Tool> tools = new ArrayList<>();
        List<ToolDescriptor> descriptors = new ArrayList<>();
        for (String name : names) {
            tools.add(new Tool() {
                @Override
                public String name() {
                    return name;
                }

                @Override
                public ToolResult invoke(String argumentsJson) {
                    calls.add(name);
                    boolean fail = failFirst && calls.size() == 1;
                    return fail ? ToolResult.failed("boom") : ToolResult.ok("ok-" + name);
                }
            });
            descriptors.add(new ToolDescriptor(name, name, "{}", true, true));
        }
        return new ToolRegistry(tools, descriptors, new ObjectMapper());
    }

    private static List<ToolDescriptor> descriptors(boolean readOnly, String... names) {
        List<ToolDescriptor> descriptors = new ArrayList<>();
        for (String name : names) {
            descriptors.add(new ToolDescriptor(name, name, "{}", readOnly, readOnly));
        }
        return descriptors;
    }

    private static final class FinalReplyChatService extends ChatService {
        FinalReplyChatService() {
            super(new DeepSeekProperties("http://unused", "k", "m", null, false),
                    TestPrompts.of("sys"), new ObjectMapper());
        }

        @Override
        public String chat(SessionKey key, List<ContextMessage> context, Set<String> permissions,
                           String traceId) {
            return "final-reply";
        }
    }
}
