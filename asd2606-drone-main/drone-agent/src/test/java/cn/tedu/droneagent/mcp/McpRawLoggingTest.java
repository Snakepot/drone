package cn.tedu.droneagent.mcp;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import cn.tedu.droneagent.tool.ToolContext;
import cn.tedu.droneagent.tool.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 原始报文日志的测试。
 *
 * <p>两条硬要求：
 * <ol>
 *   <li>开关打开时，**入参与结果原文**必须出现在日志里；</li>
 *   <li>无论开关如何，**Token / Authorization 绝不能出现**。</li>
 * </ol>
 */
class McpRawLoggingTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("原始入参与结果被打印；Token 不出现")
    void logsRawPayloadWithoutToken() {
        Logger logger = (Logger) LoggerFactory.getLogger(McpToolAdapter.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            StubTransport transport = new StubTransport();
            transport.reply = "{\"content\":[{\"type\":\"text\",\"text\":\"raw-result-value\"}],\"isError\":false}";
            McpToolAdapter adapter = new McpToolAdapter(
                    "drone__task_list", "task_list", "drone",
                    new McpClient("drone", transport, MAPPER), true, true);

            ToolResult result = adapter.invoke("{\"keyword\":\"raw-arg-value\"}",
                    new ToolContext(Set.of("admin"), Set.of(), "secret-token-value"));

            assertTrue(result.ok());

            String logs = appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .collect(Collectors.joining("\n"));
            assertTrue(logs.contains("raw-arg-value"), "应打印原始入参");
            assertTrue(logs.contains("raw-result-value"), "应打印原始结果");
            assertFalse(logs.contains("secret-token-value"), "绝不能打印 Token");
            assertFalse(logs.contains("Bearer"), "绝不能打印 Authorization");
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    @DisplayName("开关关闭时不打印【MCP-RAW】")
    void silentWhenDisabled() {
        Logger logger = (Logger) LoggerFactory.getLogger(McpToolAdapter.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            StubTransport transport = new StubTransport();
            transport.reply = "{\"content\":[{\"type\":\"text\",\"text\":\"should-not-appear\"}],\"isError\":false}";
            McpToolAdapter adapter = new McpToolAdapter(
                    "drone__task_list", "task_list", "drone",
                    new McpClient("drone", transport, MAPPER), false, false);

            adapter.invoke("{\"keyword\":\"also-hidden\"}", ToolContext.anonymous());

            String logs = appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .collect(Collectors.joining("\n"));
            assertFalse(logs.contains("【MCP-RAW】"));
            assertFalse(logs.contains("should-not-appear"));
        } finally {
            logger.detachAppender(appender);
        }
    }

    private static final class StubTransport implements McpTransport {
        private String reply;

        @Override
        public JsonNode request(JsonNode rpc) {
            return request(rpc, Map.of());
        }

        @Override
        public JsonNode request(JsonNode rpc, Map<String, String> headers) {
            ObjectNode message = MAPPER.createObjectNode();
            message.put("jsonrpc", "2.0");
            message.put("id", rpc.path("id").asInt());
            try {
                message.set("result", MAPPER.readTree(reply));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            return message;
        }

        @Override
        public void notify(JsonNode rpc) {
        }

        @Override
        public String sessionId() {
            return null;
        }
    }
}
