package cn.tedu.droneagent.bill;

import java.util.List;
import java.util.Map;

/**
 * 一次**用户请求**（可能包含多轮工具调用）的账单摘要，用于 SSE {@code done} 事件。
 *
 * <p>与 {@link BillRecord} 的区别：Record 是「每次模型调用一条」，本类型是「这次问答合计」。
 * 面板显示的是本类型——用户关心的是「这一问花了多少」，不是内部调了几次模型。
 *
 * @param promptTokens   所有轮次实际输入 token 之和（真实成本）
 * @param contextTokens  最后一轮的上下文估算（当前上下文有多大）
 * @param cachedTokens   所有轮次缓存命中之和
 * @param hitRate        缓存命中率（%）
 * @param estimateTotal  最后一轮的估算合计
 * @param kindTokens     最后一轮的分类 token（反映当前上下文构成）
 * @param toolNames      本次请求用到的工具（去重）
 * @param rounds         模型调用轮数
 */
public record RequestBill(
        long promptTokens,
        int contextTokens,
        long cachedTokens,
        double hitRate,
        int estimateTotal,
        Map<String, Integer> kindTokens,
        List<String> toolNames,
        int rounds
) {

    public RequestBill {
        kindTokens = kindTokens == null ? Map.of() : Map.copyOf(kindTokens);
        toolNames = toolNames == null ? List.of() : List.copyOf(toolNames);
    }
}
