package cn.tedu.droneagent.bill;

import java.util.List;

/**
 * 区间账单汇总（管理端页面用）。
 *
 * @param requests          请求数（按 trace 去重）
 * @param promptTokens      实际输入 token 之和
 * @param cachedTokens      缓存命中 token 之和
 * @param hitRate           缓存命中率（%）
 * @param avgContextTokens  平均上下文大小（最后一轮估算）
 * @param fingerprints      出现过的前缀指纹数量（同配置应为 1）
 * @param byKind            分类 token 合计
 * @param byDay             按天趋势
 * @param topTools          工具调用次数 Top N
 */
public record BillSummary(
        int requests,
        long promptTokens,
        long cachedTokens,
        double hitRate,
        int avgContextTokens,
        int fingerprints,
        List<KindTokens> byKind,
        List<DayTokens> byDay,
        List<ToolUsage> topTools
) {

    public record KindTokens(String kind, long tokens) {
    }

    public record DayTokens(String day, long promptTokens, long cachedTokens) {
    }

    public record ToolUsage(String tool, int calls) {
    }
}
