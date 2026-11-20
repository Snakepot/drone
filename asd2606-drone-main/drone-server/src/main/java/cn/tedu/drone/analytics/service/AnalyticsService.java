package cn.tedu.drone.analytics.service;

import cn.tedu.drone.analytics.pojo.AnalyticsOverviewVO;
import cn.tedu.drone.analytics.pojo.DispatchBlockStatsVO;
import cn.tedu.drone.analytics.pojo.EfficiencyVO;
import cn.tedu.drone.analytics.pojo.EvidenceTrendVO;

import java.time.LocalDateTime;

/**
 * 业务分析的只读查询（见 {@code docs/业务分析方案.md}）。
 *
 * <h2>为什么不复用大屏那套缓存</h2>
 * 大屏是「每 10 秒被看一眼」，所以对重复计算敏感；
 * 这一页是「有人点进来才看」，而且是按周/月看的聚合 —— 一次几百毫秒没人会在意。
 * 先不加缓存，等真的慢了再说（4.1 已经写明：它是按天/周看的，天然可以预聚合）。
 *
 * <h2>区间口径两个方法共用</h2>
 * 它们会出现在同一个页面上，默认区间与校验只有一份实现（见 Impl 的 resolveRange）——
 * 否则会出现「上半屏是本周、下半屏是本月」这种对不上的画面。
 */
public interface AnalyticsService {

    /**
     * 任务与告警概览：任务量趋势、完成率、按时完成率、平均执行时长、告警趋势、MTTR。
     *
     * <p>两个端点都可以不传：不传终点取「明天 0 点」、不传起点取「终点往前 7 天」，
     * 这样直接打开页面（或直接 curl 这个接口）就能看到一份合理的默认数据。
     *
     * @param from 区间起点（含），可为 null
     * @param to   区间终点（不含），可为 null
     */
    AnalyticsOverviewVO overview(LocalDateTime from, LocalDateTime to);

    /**
     * 待派发积压的归因：归因占比 + 积压时长分布 + 创建到派发的中位时长。
     *
     * @param from 区间起点（含），可为 null
     * @param to   区间终点（不含），可为 null
     */
    DispatchBlockStatsVO dispatchBlockStats(LocalDateTime from, LocalDateTime to);

    /**
     * 飞手与设备效率：工作量/按时率/拒绝率/无旁证占比，利用率/故障率/告警排行。
     *
     * <p>两个榜单都列**全部**在册飞手与设备（包括这段时间一条活都没有的）——
     * 「谁忙谁闲」「哪台一直停着」靠的正是那些 0 值行。
     *
     * @param from 区间起点（含），可为 null
     * @param to   区间终点（不含），可为 null
     */
    EfficiencyVO efficiency(LocalDateTime from, LocalDateTime to);

    /**
     * 证据量与活跃设备趋势：**历史读预聚合表，还没算的那几天（通常是今天）实时补**。
     *
     * <p>这是预聚合的第一个用户（见 {@code docs/业务分析方案.md} 4.3 ③ / 4.5）：
     * 明细表上这条查询要 0.58 s、读 197 万行，走预聚合表是 0.04 s、读 3650 行。
     *
     * @param from 区间起点（含），可为 null
     * @param to   区间终点（不含），可为 null
     */
    EvidenceTrendVO evidenceTrend(LocalDateTime from, LocalDateTime to);
}
