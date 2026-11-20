package cn.tedu.drone.analytics.service;

import java.time.LocalDate;

/**
 * 预聚合的**写与补算**（见 {@code docs/业务分析方案.md} 4.3 ③）。
 *
 * <h2>它只干三件事：算一天、补缺的天、按区间重算</h2>
 * 读的那一头（把历史与「今天」拼成一条趋势）在 {@code AnalyticsService} 里 ——
 * 读要跟别的分析查询共用区间口径与横轴，写要能被定时任务独立调用，两边分开更清楚。
 *
 * <h2>为什么要能「按日期重算」</h2>
 * 预聚合表里存的是**算出来的结果**，所以三件事都会让它变脏：
 * 某天算错了、那天之后才补传了数据、指标口径改了（那时还要把
 * {@code caliber-version} +1 再整段重算，否则新旧数据混在一条趋势里会断层）。
 * 补算入口就是为这些情况准备的，而不是「出了问题去手工 UPDATE 那张表」。
 */
public interface StatDailyService {

    /**
     * 重算某一天：先删掉这天已有的行，再从明细算一遍写回去。
     *
     * <p>幂等：同一天重算多少次结果都一样（这也是补算任务敢每半小时跑一次的原因）。
     *
     * @param day 按应用本地时间的那一天
     * @return 写进去的行数（那天没数据时也会写「0」的标记行，所以正常 ≥ 1）
     */
    int rebuildDay(LocalDate day);

    /**
     * 重算一段（含两端）。给「口径改了要回填历史」用的 ——
     * 迁移之后按区间跑一次，别指望逐天手工点。
     *
     * @return 重算的天数
     */
    int rebuildRange(LocalDate from, LocalDate to);

    /**
     * 补齐「昨天及以前」还没算的天 —— 定时任务调的就是它。
     *
     * <p>为什么是「昨天及以前」：**今天的数据还在涨**，算出来的数当场就过期，
     * 今天那一段留给读的时候实时补（见 {@code AnalyticsService.evidenceTrend}）。
     *
     * <p>为什么往回看一段而不是「只算昨天」：停机、重启、漏跑都会留下空洞，
     * 只看昨天的话那个空洞永远补不上。回看窗口由配置给（默认 30 天）。
     *
     * @param today       应用的「今天」（由调用方给，便于用例摆时间）
     * @param lookbackDays 往回看多少天找空洞
     * @return 本次补了几天
     */
    int ensureUpToDate(LocalDate today, int lookbackDays);
}
