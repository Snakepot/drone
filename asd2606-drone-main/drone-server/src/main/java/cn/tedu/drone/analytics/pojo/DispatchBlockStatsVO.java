package cn.tedu.drone.analytics.pojo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.util.List;

/**
 * 「待派发积压的归因」这一屏要的数字（见 {@code docs/业务分析方案.md} 2.7）。
 *
 * <h2>三个指标回答的是三个不同的问题</h2>
 * <ol>
 *   <li><b>归因占比</b>（{@link #reasonShare}）：活卡着，是缺人、缺设备，还是没人派？</li>
 *   <li><b>积压时长分布</b>（{@link #backlogAge}）：**此刻**有多少活已经等了一小时、一天？</li>
 *   <li><b>创建 → 派发的中位时长</b>（{@link #medianMinutes}）：管理动作的反应速度。
 *       用中位数而不是平均数 —— 一条被遗忘两周的任务能把平均数拉到毫无意义</li>
 * </ol>
 *
 * <h2>⚠️ 前两个的分母不是一个，界面上必须写清</h2>
 * 归因占比是**区间内的历史**（按归因点算），积压时长分布是**此刻的快照**。
 * 混着读会得出「这周 60% 是缺设备，可现在只积压了 3 条」这种前后不搭的结论 ——
 * 前者答的是「为什么攒下来的」，后者答的是「现在还剩多少」。
 *
 * <h2>为什么要给「有采样的小时数」</h2>
 * 归因点靠定时任务攒，**服务停了的那几个小时就是没有数据**。
 * 所以占比的分母只能是「有采样的那些小时」，不能是「区间一共多少小时」，
 * 否则停机一次，占比就整体失真。把采样覆盖一起给出来，
 * 界面上才敢说「这周的结论可信」还是「这周只覆盖了 40% 的时间」。
 */
@Data
@Accessors(chain = true)
@Schema(description = "待派发积压的归因（三个指标）")
public class DispatchBlockStatsVO implements Serializable {

    @Schema(description = "区间起点（含）")
    private String from;

    @Schema(description = "区间终点（不含）")
    private String to;

    // ------------------------------------------------------------------
    // ① 归因占比（区间内）
    // ------------------------------------------------------------------

    @Schema(description = "区间内的归因点总数（各归因占比的分母）")
    private Long blockPoints;

    @Schema(description = "各归因的占比，按条数从多到少")
    private List<ReasonShare> reasonShare;

    @Schema(description = "区间内有采样的整点数（按小时算）")
    private Long sampledHours;

    @Schema(description = "区间一共多少小时；与 sampledHours 的差就是停机时段")
    private Long expectedHours;

    // ------------------------------------------------------------------
    // ② 积压时长分布（此刻的快照，不受区间影响）
    // ------------------------------------------------------------------

    @Schema(description = "此刻待派发的任务总数")
    private Long pendingTotal;

    @Schema(description = "此刻待派发的任务，按「已经等了多久」分桶")
    private List<BacklogBucket> backlogAge;

    // ------------------------------------------------------------------
    // ③ 创建 → 派发
    // ------------------------------------------------------------------

    @Schema(description = "从创建到派发的中位时长（分钟）；区间内没有派发记录时为空")
    private Long medianMinutes;

    @Schema(description = "参与中位数的样本数 —— 太少就不要下结论")
    private Integer dispatchedSamples;

    /** 一个归因及其占比 */
    @Data
    @Accessors(chain = true)
    @Schema(description = "归因占比")
    public static class ReasonShare implements Serializable {

        @Schema(description = "blocked_pilot / blocked_drone / blocked_both / idle")
        private String reason;

        /** 中文名由后端给（与 DispatchBlockReason.label 同一处定义），前端不重复维护一套 */
        @Schema(description = "中文名")
        private String label;

        @Schema(description = "区间内该归因的条数")
        private Long count;

        @Schema(description = "占比（%，保留一位小数）")
        private Double percent;
    }

    /** 积压时长的一个桶 */
    @Data
    @Accessors(chain = true)
    @Schema(description = "积压时长分桶")
    public static class BacklogBucket implements Serializable {

        @Schema(description = "lt1h 一小时内 / lt1d 一天内 / ge1d 超过一天")
        private String key;

        @Schema(description = "中文名")
        private String label;

        @Schema(description = "条数")
        private Long count;
    }
}
