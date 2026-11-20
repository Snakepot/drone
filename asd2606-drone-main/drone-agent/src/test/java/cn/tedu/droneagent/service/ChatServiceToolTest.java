package cn.tedu.droneagent.service;

import cn.tedu.droneagent.config.DeepSeekProperties;
import cn.tedu.droneagent.context.ContextBill;
import cn.tedu.droneagent.context.ContextMessage;
import cn.tedu.droneagent.context.MessageKind;
import cn.tedu.droneagent.memory.SessionKey;
import cn.tedu.droneagent.prompt.TestPrompts;
import cn.tedu.droneagent.tool.ModelMessage;
import cn.tedu.droneagent.tool.ModelTurn;
import cn.tedu.droneagent.tool.ToolCall;
import cn.tedu.droneagent.tool.ToolDefinition;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ChatService} 工具调用协议部分的单元测试。
 *
 * <p>对应验收条件：`docs/sdd/specs/REQ-AGENT-0004-工具调用.md` 的
 * <b>AC-1</b>（模型能发起工具调用）与 <b>AC-7</b>（未配置 key 的行为）的协议侧。
 *
 * <h2>为什么只测「纯函数」而不真发请求</h2>
 * 发请求要连真模型，违反宪法第 5 节的 AIR。这里只钉住两件无需网络就能判定的事：
 * <b>请求体长什么样</b>（{@code buildToolBody}）与 <b>响应怎么解析</b>（{@code parseTurn}）。
 * 真正的往返在 T-8 端到端时手工验。
 */
class ChatServiceToolTest {

    private static final String SYSTEM_PROMPT = "你是无人机管理平台的助手";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 测试用的工具名。
     *
     * <p>这里刻意**不引用**任何真实工具类：本测试验的是「请求体与响应解析」，
     * 与具体工具无关。以前直接引用 {@code WeatherTool.NAME}，天气工具下线（REQ-AGENT-0011）后
     * 测试就编译不过了——这是「测试依赖实现细节」的典型代价。
     */
    private static final String DEMO_TOOL = "demo_tool";

    private final ChatService service = new ChatService(
            new DeepSeekProperties("http://unused", "unused", "test-model", null, false),
            TestPrompts.of(SYSTEM_PROMPT), MAPPER);

    private static ToolDefinition demoTool() {
        // 元数据现在来自配置；此处用等价的定义，模拟 ToolRegistry 的产出
        return new ToolDefinition(DEMO_TOOL,
                "查询指定城市的当前天气（气温、风速、降水），用于判断是否适合无人机起飞。",
                "{\"type\":\"object\",\"properties\":{\"place\":{\"type\":\"string\"}},\"required\":[\"place\"]}");
    }

    /** 模拟模型「要调工具」的响应：content 为 null，带一个 tool_calls */
    private static final String TOOL_CALL_RESPONSE = """
            {"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[
            {"id":"call_1","type":"function","function":{"name":"demo_tool","arguments":"{\\"place\\":\\"北京\\"}"}}]}}]}
            """;

    /** 模拟模型「直接作答」的响应 */
    private static final String TEXT_RESPONSE = """
            {"choices":[{"message":{"role":"assistant","content":"明天多云，适合起飞。"},"finish_reason":"stop"}]}
            """;

    @Test
    @DisplayName("请求体：系统提示词排第一，工具定义按 function 结构序列化，parameters 是对象")
    void bodyPutsSystemPromptFirstAndSerializesTools() throws Exception {
        String body = service.buildToolBody(List.of(ContextMessage.session(ModelMessage.user("北京明天天气"))),
                List.of(demoTool()));
        JsonNode root = MAPPER.readTree(body);

        assertEquals("system", root.path("messages").path(0).path("role").asText());
        assertEquals(SYSTEM_PROMPT, root.path("messages").path(0).path("content").asText());
        assertEquals("user", root.path("messages").path(1).path("role").asText());

        JsonNode tool = root.path("tools").path(0);
        assertEquals("function", tool.path("type").asText());
        assertEquals(DEMO_TOOL, tool.path("function").path("name").asText());
        // parameters 必须是对象（有 properties.place），不能是一整个字符串
        assertTrue(tool.path("function").path("parameters").path("properties").has("place"));

        assertFalse(root.path("stream").asBoolean());
    }

    @Test
    @DisplayName("请求体：助手带 tool_calls、工具结果带 tool_call_id，id 原样保留")
    void bodySerializesToolCallsAndToolResult() throws Exception {
        List<ContextMessage> messages = List.of(
                ContextMessage.toolCall(ModelMessage.assistantToolCalls(List.of(
                        new ToolCall("call_1", DEMO_TOOL, "{\"place\":\"北京\"}")))),
                ContextMessage.toolResult(
                        ModelMessage.tool("call_1", "「北京」当前天气：风速 12.3 km/h"), DEMO_TOOL));

        String body = service.buildToolBody(messages, List.of());
        JsonNode msgs = MAPPER.readTree(body).path("messages");

        // [0]=system, [1]=assistant(带 tool_calls), [2]=tool
        assertEquals("assistant", msgs.path(1).path("role").asText());
        assertEquals("call_1", msgs.path(1).path("tool_calls").path(0).path("id").asText());
        assertEquals("function", msgs.path(1).path("tool_calls").path(0).path("type").asText());
        assertEquals(DEMO_TOOL,
                msgs.path(1).path("tool_calls").path(0).path("function").path("name").asText());

        assertEquals("tool", msgs.path(2).path("role").asText());
        assertEquals("call_1", msgs.path(2).path("tool_call_id").asText());
        assertEquals("「北京」当前天气：风速 12.3 km/h", msgs.path(2).path("content").asText());
    }

    @Test
    @DisplayName("不带工具时，请求体里没有 tools 字段")
    void bodyWithoutToolsHasNoToolsField() throws Exception {
        String body = service.buildToolBody(List.of(ContextMessage.session(ModelMessage.user("你好"))), List.of());

        assertFalse(MAPPER.readTree(body).has("tools"));
    }

    @Test
    @DisplayName("解析响应：识别出 tool_calls 的 id / 名称 / 参数，正文为 null")
    void parseTurnReadsToolCalls() {
        ModelTurn turn = service.parseTurn(TOOL_CALL_RESPONSE);

        assertTrue(turn.hasToolCalls());
        assertEquals(1, turn.toolCalls().size());
        assertEquals("call_1", turn.toolCalls().get(0).id());
        assertEquals(DEMO_TOOL, turn.toolCalls().get(0).name());
        assertTrue(turn.toolCalls().get(0).argumentsJson().contains("北京"));
        assertNull(turn.content());
    }

    @Test
    @DisplayName("解析响应：普通正文没有 tool_calls")
    void parseTurnReadsPlainContent() {
        ModelTurn turn = service.parseTurn(TEXT_RESPONSE);

        assertFalse(turn.hasToolCalls());
        assertEquals("明天多云，适合起飞。", turn.content());
    }

    @Test
    @DisplayName("未配置 API-KEY 时，chatStep 直接报错，不发起请求")
    void chatStepRejectsWhenNotConfigured() {
        ChatService unconfigured = new ChatService(
                new DeepSeekProperties("http://unused", "", "test-model", null, false),
                TestPrompts.of(SYSTEM_PROMPT), MAPPER);

        IllegalStateException error = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class,
                () -> unconfigured.chatStep(new SessionKey(1L, "conv"),
                        List.of(ContextMessage.session(ModelMessage.user("hi"))), List.of()));

        assertTrue(error.getMessage().contains("DEEPSEEK_API_KEY"));
    }

    @Test
    @DisplayName("深度思考：带 tool_calls 的 assistant 帧必须回带 reasoning_content")
    void includesReasoningContent() {
        ModelMessage frame = ModelMessage.assistantToolCalls(
                List.of(new ToolCall("call_1", DEMO_TOOL, "{\"place\":\"北京\"}")), "先想想再查");

        String body = service.buildToolBody(List.of(ContextMessage.toolCall(frame)), List.of(demoTool()));

        assertTrue(body.contains("\"reasoning_content\":\"先想想再查\""), body);
    }

    @Test
    @DisplayName("流式请求带 include_usage；账单把系统提示词、会话与工具定义分开计")
    void streamRequestAsksForUsageAndBillsTools() throws Exception {
        ChatService.BuiltRequest built = service.buildRequest(
                List.of(ContextMessage.session(ModelMessage.user("你好"))),
                List.of(demoTool()), java.util.Set.of(), true);

        JsonNode root = MAPPER.readTree(built.body());
        assertTrue(root.path("stream").asBoolean());
        assertTrue(root.path("stream_options").path("include_usage").asBoolean(),
                "流式必须显式要 usage，否则账单拿不到实际值");

        ContextBill bill = built.bill();
        assertEquals(MessageKind.SYSTEM, bill.messages().get(0).kind(), "系统提示词应排第一并单独计");
        assertEquals(1, bill.byKind().get(MessageKind.SESSION).count());
        assertEquals(1, bill.byKind().get(MessageKind.SYSTEM).count());
        assertEquals(1, bill.toolCount());
        assertTrue(bill.toolsTokens() > 0, "工具定义不能漏出账单");
        assertTrue(bill.totalTokens() > bill.messageTokens());
    }

    @Test
    @DisplayName("前缀指纹：相同系统提示词与工具 → 指纹一致；追加会话消息不影响前缀")
    void prefixFingerprintIsStable() {
        ChatService.BuiltRequest first = service.buildRequest(
                List.of(ContextMessage.session(ModelMessage.user("你好"))),
                List.of(demoTool()), java.util.Set.of(), false);
        ChatService.BuiltRequest again = service.buildRequest(
                List.of(ContextMessage.session(ModelMessage.user("你好"))),
                List.of(demoTool()), java.util.Set.of(), false);
        ChatService.BuiltRequest longer = service.buildRequest(
                List.of(ContextMessage.session(ModelMessage.user("你好")),
                        ContextMessage.session(ModelMessage.assistant("在"))),
                List.of(demoTool()), java.util.Set.of(), false);

        assertEquals(first.prefixFingerprint(), again.prefixFingerprint());
        assertEquals(first.prefixFingerprint(), longer.prefixFingerprint(),
                "追加消息只延长后缀，不应改变「系统+工具」前缀");
        assertEquals(12, first.prefixFingerprint().length(), "取 SHA-256 前 12 位，够排障又不刷屏");
    }

    @Test
    @DisplayName("工具结果在账单里带工具名，能看出钱花在哪个工具上")
    void billKeepsToolNameOnResults() {
        ChatService.BuiltRequest built = service.buildRequest(
                List.of(ContextMessage.toolResult(
                        ModelMessage.tool("call_1", "查询结果"), DEMO_TOOL)),
                List.of(), java.util.Set.of(), false);

        assertEquals(1, built.bill().toolResultCounts().get(DEMO_TOOL));
        assertTrue(built.bill().describe().contains("工具结果"));
    }
}
