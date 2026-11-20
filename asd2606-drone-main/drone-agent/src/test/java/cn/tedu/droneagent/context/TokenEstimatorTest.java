package cn.tedu.droneagent.context;

import cn.tedu.droneagent.tool.ModelMessage;
import cn.tedu.droneagent.tool.ToolCall;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 估算系数的「标定用例」：数字来自 2026-10-23 对 deepseek-flash 的实测。
 *
 * <p>这些断言的作用不是追求计费级精确，而是**防止系数被无意改坏**：
 * 一旦有人把 0.62 调成 0.5，账单会系统性低估，而日志里看不出来。
 */
class TokenEstimatorTest {

    /** 与实测样本完全相同的 112 个汉字 */
    private static final String CN_112 = "无人机设备任务告警航线禁飞区".repeat(8);

    @Test
    @DisplayName("112 个汉字 ≈ 69 token（实测 112 字对应 73，整段口径下取 0.62）")
    void estimatesChinese() {
        assertEquals(69, TokenEstimator.estimateText(CN_112));
    }

    @Test
    @DisplayName("100 个 ASCII 字符 ≈ 21 token（实测约 0.21/字符）")
    void estimatesAscii() {
        assertEquals(21, TokenEstimator.estimateText("a".repeat(100)));
    }

    @Test
    @DisplayName("空串与 null 都是 0，不抛异常")
    void emptyTextIsZero() {
        assertEquals(0, TokenEstimator.estimateText(null));
        assertEquals(0, TokenEstimator.estimateText(""));
    }

    @Test
    @DisplayName("工具定义 JSON：固定开销 450 + 0.34/字符（29,616 字符 ≈ 10,519 token）")
    void estimatesJsonWithItsOwnRatio() {
        assertEquals(484, TokenEstimator.estimateJson("a".repeat(100)));
        assertEquals(10519, TokenEstimator.estimateJson("a".repeat(29616)));
        assertEquals(0, TokenEstimator.estimateJson(""), "没有工具时不能凭空加固定开销");
    }

    @Test
    @DisplayName("结构化文本（HTML/JSON）：非汉字按 0.34（100 个 ASCII → 34）")
    void estimatesStructuredText() {
        assertEquals(34, TokenEstimator.estimateStructuredText("a".repeat(100)));
    }

    @Test
    @DisplayName("同样内容：工具结果按结构化口径，会话按自然语言口径")
    void messageKindSelectsRatio() {
        ModelMessage result = ModelMessage.tool("c1", "a".repeat(100));

        int asSession = TokenEstimator.estimateMessage(result, MessageKind.SESSION);
        int asToolResult = TokenEstimator.estimateMessage(result, MessageKind.TOOL_RESULT);

        assertEquals(TokenEstimator.PER_MESSAGE_TOKENS + 21, asSession);
        assertEquals(TokenEstimator.PER_MESSAGE_TOKENS + 34, asToolResult);
    }

    @Test
    @DisplayName("一条消息 = 固定开销 4 + 正文")
    void estimatesMessageWithOverhead() {
        assertEquals(TokenEstimator.PER_MESSAGE_TOKENS + 1,
                TokenEstimator.estimateMessage(ModelMessage.user("你好")));
    }

    @Test
    @DisplayName("工具调用帧要把函数名与参数算进去，否则会低估")
    void estimatesToolCallFrame() {
        int plain = TokenEstimator.estimateMessage(ModelMessage.assistant(""));
        int withCall = TokenEstimator.estimateMessage(ModelMessage.assistantToolCalls(
                List.of(new ToolCall("c1", "drone__task_list", "{\"pageSize\": 50}"))));

        assertTrue(withCall > plain + 8, "函数名与参数不能漏算");
    }

    @Test
    @DisplayName("整段请求的估算与实测口径一致（模板 22 + 消息 + 工具定义）")
    void wholeRequestMatchesCalibration() {
        // 实测样本：system「你是助手。」+ user「实验」+ user 112 汉字 → 107 token
        List<ContextMessage> context = List.of(
                ContextMessage.of(new cn.tedu.droneagent.dto.ChatMessage("system", "你是助手。"), MessageKind.SYSTEM),
                ContextMessage.of(new cn.tedu.droneagent.dto.ChatMessage("user", "实验"), MessageKind.SESSION),
                ContextMessage.of(new cn.tedu.droneagent.dto.ChatMessage("user", CN_112), MessageKind.SESSION));

        ContextBill bill = new ContextBill(context, 0, 0);

        assertEquals(107, bill.totalTokens(), "与 2026-10-23 实测的 107 对齐");
    }
}
