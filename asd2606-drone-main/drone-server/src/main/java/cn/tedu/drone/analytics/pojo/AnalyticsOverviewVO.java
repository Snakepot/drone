package cn.tedu.drone.analytics.pojo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.util.List;

/**
 * 任务与告警概览（见 {@code docs/业务分析方案.md} 第一步、2.1 与 2.4）。
 *
 * <h2>两张图共用一条横轴</h2>
 * {@link #days} 是区间内**每一天**（缺口也留着），两个趋势序列都按它对齐：
 * 少了它，任务量和告警量各画各的轴，「同一天发生的事」就对不上了 ——
 * 而这一屏的价值恰恰在「任务量涨的那几天告警是不是也涨」。
 *
 * <h2>⚠️ 完成率的分母是「有结论的活」，不是「所有的活」</h2>
 * 只看在区间内**结束**（{@link Tasks#finished} + {@link Tasks#failed}）的任务。
 * 两个理由：还在飞的没有结论，算进去只是把数字往下拽；取消的任务根本没执行
 * （库里连结束时刻都没写），把它算成「没完成」等于把管理员撤回的活记在飞手头上。
 * 这样定之后，页面上每个数都能在**任务列表**里按「实际结束时间 + 状态」复算出来。
 *
 * <h2>没有样本时给 null，不给 0</h2>
 * 一段区间里一条任务都没结束时，「完成率 0%」是在说「全失败了」——
 * 而事实是**没有可判断的对象**。所以这些比率与时长在没有样本时是 null，
 * 由界面显示成「—」。
 */
@Data
@Accessors(chain = true)
@Schema(description = "任务与告警概览")
public class AnalyticsOverviewVO implements Serializable {

    @Schema(description = "区间起点（含）")
    private String from;

    @Schema(description = "区间终点（不含）")
    private String to;

    @Schema(description = "横轴：区间内每一天（yyyy-MM-dd，缺口也在）")
    private List<String> days;

    @Schema(description = "任务侧")
    private Tasks tasks;

    @Schema(description = "告警侧")
    private Alerts alerts;

    /** 任务侧：量、完成情况、时效 */
    @Data
    @Accessors(chain = true)
    @Schema(description = "任务侧概览")
    public static class Tasks implements Serializable {

        /** 与 {@link AnalyticsOverviewVO#days} 一一对应：每天新建了多少任务 */
        @Schema(description = "每天新建任务数（与 days 对齐）")
        private List<Long> created;

        @Schema(description = "区间内新建任务总数")
        private Long createdTotal;

        @Schema(description = "区间内结束的活里，完成的条数")
        private Long finished;

        @Schema(description = "区间内结束的活里，失败的条数")
        private Long failed;

        @Schema(description = "完成率 = 完成 ÷（完成 + 失败），%；没有结束的活时为 null")
        private Double finishRate;

        /** 按时率的样本：已完成的、且有计划结束时间（拿它当分母才谈得上「按不按时」） */
        @Schema(description = "按时完成率的样本数")
        private Long onTimeBase;

        @Schema(description = "按时完成率 = 实际结束不晚于计划结束的比例，%；无样本时为 null")
        private Double onTimeRate;

        @Schema(description = "平均执行时长的样本数（有实际起止时间的）")
        private Long durationBase;

        @Schema(description = "平均执行时长（分钟，四舍五入）；无样本时为 null")
        private Long avgMinutes;
    }

    /** 告警侧：趋势、总量、处置速度 */
    @Data
    @Accessors(chain = true)
    @Schema(description = "告警侧概览")
    public static class Alerts implements Serializable {

        @Schema(description = "按级别分条的趋势线，每条长度都等于 days")
        private List<AlertSeries> trend;

        @Schema(description = "区间内观察到的告警总数")
        private Long total;

        @Schema(description = "其中严重的")
        private Long critical;

        @Schema(description = "已处置（有处置时刻）的条数 —— MTTR 的样本数")
        private Long handled;

        @Schema(description = "平均处置时长（分钟，四舍五入）；无样本时为 null")
        private Long mttrMinutes;

        /**
         * 此刻还没处置的（pending + handling）。
         *
         * <p>它是**快照，不随区间变** —— MTTR 只说「处置起来快不快」，
         * 而积压说的是「还欠着多少」，两件事得分开看：
         * 处置很快、但积压一直在涨，说明报告的人多了，而不是值班变懒了。
         */
        @Schema(description = "此刻未处置的告警数（快照）")
        private Long openNow;
    }

    /** 一条趋势线（一个级别一条） */
    @Data
    @Accessors(chain = true)
    @Schema(description = "某个级别的告警趋势")
    public static class AlertSeries implements Serializable {

        @Schema(description = "critical / warning / info")
        private String severity;

        @Schema(description = "中文名")
        private String label;

        @Schema(description = "每天的数量，与 days 对齐")
        private List<Long> values;
    }
}
