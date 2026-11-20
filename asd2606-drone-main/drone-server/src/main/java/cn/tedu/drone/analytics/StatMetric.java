package cn.tedu.drone.analytics;

import java.util.List;

/**
 * 预聚合指标的**词汇表**（见 {@code docs/业务分析方案.md} 第三步 / 4.3 ③）。
 *
 * <h2>指标名里藏着粒度，这不是偷懒</h2>
 * {@code stat_daily} 是一张窄表（一天一指标一行），粒度靠 {@code dimension} 表达，
 * 而**粒度定错就再也补不回来**：`COUNT(DISTINCT)` 不可加 ——
 * 只存「这天 5 台、明天 5 台」加不出「这两天一共几台」（4.5 实测后定下的）。
 * 所以每个指标在这里都要写清「写到哪一层」，而不是只写个名字。
 *
 * <h2>每个指标**每天都要留一行，哪怕当天是 0**</h2>
 * 否则读的时候分不清两种情况：「这一天任务没跑」和「跑了、那天确实没有数据」。
 * 前者该实时补算，后者该直接显示 0 —— 分不清就会把「没算」画成「那天是空的」。
 * 所以 {@link #ACTIVE_DEVICES} 兼了两个角色：它既是那个指标，也是「这天算过没有」的标记。
 */
public final class StatMetric {

    private StatMetric() {
    }

    /**
     * 证据条数：**每天每台设备**多少条（粒度：天 × 设备）。
     *
     * <p>为什么写到设备这一层，而不是只写「每天多少条」：
     * 「每天多少条」= 这一天所有行的 {@code value} 之和（SUM 可加），
     * 「每天多少台设备活跃」= 这一天有多少行（有行就说明那台设备那天有数据）。
     * 反过来，只存「每天多少条」的话，第二个问题永远算不出来。
     */
    public static final String EVIDENCE_COUNT = "evidence_count";

    /**
     * 活跃设备数：**每天**多少台设备有数据（粒度：天）。
     *
     * <p>它其实能从 {@link #EVIDENCE_COUNT} 的行数加出来，为什么还要单独存？
     * 两个理由，第二个才是关键：
     * <ol>
     *   <li>它是被问得最多的那一条线，存成一行读起来最省事</li>
     *   <li><b>它兼当「这一天算过没有」的标记</b>：每天都写（没数据的那天写 0），
     *       所以「有这一行」就等于「这天算过了」。读的时候靠它决定哪几天要实时补算 ——
     *       只看 {@code EVIDENCE_COUNT} 的话，空数据的那天永远「像是没算」，会每次都被实时重算</li>
     * </ol>
     * 两个指标在同一件事务里从同一条查询写出来，所以不会互相矛盾。
     */
    public static final String ACTIVE_DEVICES = "active_devices";

    /** 需要按「天」写的指标（写与删都按这个清单走，免得漏一个） */
    public static final List<String> DAILY_METRICS = List.of(EVIDENCE_COUNT, ACTIVE_DEVICES);

    /**
     * 「这天算过没有」看哪个指标 —— 看那个**每天都写**的。
     *
     * <p>把它写成常量而不是散在代码里：将来加指标时，只有一处需要想清楚
     * 「它会不会每天都写」。
     */
    public static final String PRESENCE = ACTIVE_DEVICES;

    /** 维度取值：不分维度时用它（与 DDL 上的默认值一致；别用 null —— NULL 在唯一键里不相等，重算会插重） */
    public static final String NO_DIMENSION = "";
}
