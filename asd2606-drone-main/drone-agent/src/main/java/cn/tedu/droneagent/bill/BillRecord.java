package cn.tedu.droneagent.bill;

import java.util.List;
import java.util.Map;

/**
 * 一次**模型调用**的账单记录（`REQ-AGENT-0025`）。
 *
 * <h2>为什么只存元数据</h2>
 * 账单页面要回答的是「花了多少、花在哪」，不需要对话正文。
 * 不存正文/工具参数/Token，既减小体积，也避免把用户数据复制到另一个目录。
 *
 * @param ts             调用时间（ISO-8601，带时区）
 * @param traceId        请求链路 id；同一请求的多轮共用
 * @param userId         用户 id
 * @param conversationId 会话 id
 * @param round          本请求内的第几次模型调用（从 1 开始）
 * @param model          模型名
 * @param fingerprint    前缀指纹（系统提示词 + 工具定义）
 * @param estimateTotal  本次上下文估算合计
 * @param kindTokens     分类 token（系统/会话/工具调用/工具结果/摘要/工具定义）
 * @param toolsTokens    工具定义 token
 * @param toolCount      工具数量
 * @param promptTokens   模型实际输入 token；没有 usage 时为 null
 * @param cachedTokens   缓存命中 token；没有 usage 时为 null
 * @param hitRate        缓存命中率（%）；没有 usage 时为 null
 * @param latencyMs      本次模型调用耗时（毫秒）
 * @param toolNames      到本次调用为止已执行的工具名
 */
public record BillRecord(
        String ts,
        String traceId,
        long userId,
        String conversationId,
        int round,
        String model,
        String fingerprint,
        int estimateTotal,
        Map<String, Integer> kindTokens,
        int toolsTokens,
        int toolCount,
        Integer promptTokens,
        Integer cachedTokens,
        Double hitRate,
        long latencyMs,
        List<String> toolNames
) {

    public BillRecord {
        kindTokens = kindTokens == null ? Map.of() : Map.copyOf(kindTokens);
        toolNames = toolNames == null ? List.of() : List.copyOf(toolNames);
    }

    /** 落盘前补上轮次（轮次由 {@link BillStore} 按 trace 递增） */
    public BillRecord withRound(int newRound) {
        return new BillRecord(ts, traceId, userId, conversationId, newRound, model, fingerprint,
                estimateTotal, kindTokens, toolsTokens, toolCount, promptTokens, cachedTokens,
                hitRate, latencyMs, toolNames);
    }
}
