package cn.tedu.droneagent.context;

import cn.tedu.droneagent.dto.ChatMessage;
import cn.tedu.droneagent.tool.ModelMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 「带计量的消息」：线格式消息本体 + 它在账单里的身份 + 估算 token。
 *
 * <h2>为什么不让 {@link ChatMessage} 直接带这些字段</h2>
 * {@code ChatMessage} 是**落盘**的会话记忆，只有 role + content。把「类别」「token」
 * 塞进去，等于把某次请求的运行态写进了长期记忆：换模型后 token 就是错的，
 * 不同请求的类别也可能不同。计量信息属于「这一次请求」，不属于「这段对话」。
 *
 * <h2>为什么不直接给 {@link ModelMessage} 加字段</h2>
 * {@code ModelMessage} 会被序列化成发给模型的 JSON。多出来的字段要么被发出去
 * （污染协议），要么在序列化时再排除（多一处容易漏的逻辑）。包装一层最干净。
 *
 * @param message  真正发给模型的那条消息
 * @param kind     账单分类
 * @param toolName 工具名；只有「工具结果」一类才有，其余为 {@code null}
 */
public record ContextMessage(ModelMessage message, MessageKind kind, String toolName) {

    public ContextMessage {
        Objects.requireNonNull(message, "message 不能为空");
        Objects.requireNonNull(kind, "kind 不能为空");
    }

    /** 会话记忆里的一条消息 → 带计量包装 */
    public static ContextMessage of(ChatMessage message, MessageKind kind) {
        return new ContextMessage(ModelMessage.of(message), kind, null);
    }

    /** 当前会话里的一条消息 */
    public static ContextMessage session(ModelMessage message) {
        return new ContextMessage(message, MessageKind.SESSION, null);
    }

    /** 助手请求调用工具的一帧 */
    public static ContextMessage toolCall(ModelMessage message) {
        return new ContextMessage(message, MessageKind.TOOL_CALL, null);
    }

    /** 一条工具结果；工具名用于账单里区分「贵在哪个工具」 */
    public static ContextMessage toolResult(ModelMessage message, String toolName) {
        return new ContextMessage(message, MessageKind.TOOL_RESULT, toolName);
    }

    /** 这条消息估算占多少 token（现算，不缓存——消息内容不会变，但算一次也很便宜） */
    public int tokens() {
        return TokenEstimator.estimateMessage(message, kind);
    }

    /** 批量拆回线格式消息（发给模型 / 存进待确认票据时用） */
    public static List<ModelMessage> unwrap(List<ContextMessage> messages) {
        List<ModelMessage> wire = new ArrayList<>(messages.size());
        for (ContextMessage message : messages) {
            wire.add(message.message());
        }
        return wire;
    }
}
