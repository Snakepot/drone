package cn.tedu.droneagent.context;

import cn.tedu.droneagent.dto.ChatMessage;
import cn.tedu.droneagent.tool.ModelMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 账单的汇总口径：分类、合计、工具归因、日志渲染。 */
class ContextBillTest {

    private static ContextMessage of(String role, String content, MessageKind kind) {
        return ContextMessage.of(new ChatMessage(role, content), kind);
    }

    @Test
    @DisplayName("合计 = 模板 22 + 所有消息 + 工具定义")
    void totalIsTemplatePlusMessagesPlusTools() {
        List<ContextMessage> messages = List.of(
                of("system", "系统提示词", MessageKind.SYSTEM),
                of("user", "你好", MessageKind.SESSION));
        ContextBill bill = new ContextBill(messages, 1200, 70);

        assertEquals(bill.messageTokens() + 1200 + TokenEstimator.TEMPLATE_TOKENS, bill.totalTokens());
        assertEquals(1, bill.byKind().get(MessageKind.SYSTEM).count());
        assertEquals(1, bill.byKind().get(MessageKind.SESSION).count());
        assertEquals(0, bill.byKind().get(MessageKind.TOOL_RESULT).count());
    }

    @Test
    @DisplayName("工具结果按工具名归因，能看出钱花在哪个工具上")
    void groupsToolResultsByToolName() {
        List<ContextMessage> messages = List.of(
                ContextMessage.toolResult(ModelMessage.tool("c1", "结果一"), "drone__task_list"),
                ContextMessage.toolResult(ModelMessage.tool("c2", "结果二"), "drone__task_list"),
                ContextMessage.toolResult(ModelMessage.tool("c3", "结果三"), "drone__alert_list"));
        ContextBill bill = new ContextBill(messages, 0, 0);

        assertEquals(2, bill.toolResultCounts().get("drone__task_list"));
        assertEquals(1, bill.toolResultCounts().get("drone__alert_list"));
    }

    @Test
    @DisplayName("日志里能看到每个类别与工具定义；没有工具时不出现该行")
    void describesCategories() {
        ContextBill withTools = new ContextBill(List.of(
                of("system", "系统", MessageKind.SYSTEM),
                of("user", "问题", MessageKind.SESSION)), 900, 73);

        String text = withTools.describe();
        assertTrue(text.contains("系统提示词"), text);
        assertTrue(text.contains("当前会话"), text);
        assertTrue(text.contains("工具定义 900×73"), text);
        assertTrue(text.contains("模板 22"), text);

        assertFalse(new ContextBill(List.of(), 0, 0).describe().contains("工具定义"));
    }
}
