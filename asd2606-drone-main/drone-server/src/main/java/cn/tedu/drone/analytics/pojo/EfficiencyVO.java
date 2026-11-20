package cn.tedu.drone.analytics.pojo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.util.List;

/**
 * 飞手与设备效率（见 {@code docs/业务分析方案.md} 第二步、2.2 与 2.3）。
 *
 * <h2>它回答两个「凭印象说不清」的问题</h2>
 * <ul>
 *   <li><b>谁忙谁闲</b>：每位飞手这段时间接了多少活、飞了多少架次、按时率如何</li>
 *   <li><b>哪台该大修</b>：每台设备的利用率与「每 100 飞行小时报几次警」</li>
 * </ul>
 *
 * <h2>⚠️ 一张榜单里混了三个时间基准，每一列都能单独复算</h2>
 * 这是没办法合并的：它们问的不是同一件事。
 * <ul>
 *   <li><b>工作量 / 按时率 / 架次时长</b>：按 {@code actual_end_time}（有结论的活）——
 *       与第一步的完成率同一个基准</li>
 *   <li><b>无旁证占比</b>：按 {@code actual_start_time}（开过的活，<b>含还在飞的</b>）——
 *       「他说在飞却没证据」这件事恰恰要在飞行中暴露，只算结束的会正好漏掉它</li>
 *   <li><b>派发数 / 拒绝数</b>：按 task_log 的<b>流水时间</b>（派发与拒绝都是事件，
 *       发生在那一刻就该记在那一刻）</li>
 * </ul>
 * 所以每列在表格里都能自己解释自己，不要拿两列的数去相互除 —— 分母不是一个。
 *
 * <h2>没有样本时给 null，不给 0</h2>
 * 「一次都没派过」的人的拒绝率不是 0%，是**无从谈起**。界面显示「—」。
 */
@Data
@Accessors(chain = true)
@Schema(description = "飞手与设备效率")
public class EfficiencyVO implements Serializable {

    @Schema(description = "区间起点（含）")
    private String from;

    @Schema(description = "区间终点（不含）")
    private String to;

    @Schema(description = "区间天数 —— 利用率的分母里有它，界面上要说清")
    private Integer rangeDays;

    @Schema(description = "每日可飞小时（配置项）—— 利用率的分母里也有它")
    private Integer flyableHoursPerDay;

    @Schema(description = "飞手榜：全部在册飞手（没活的那几位也在，为的是看出「谁闲」）")
    private List<PilotRow> pilots;

    @Schema(description = "设备榜：全部设备（一直没飞的那几台也在）")
    private List<DeviceRow> devices;

    /** 一位飞手在这段时间里的工作量与质量 */
    @Data
    @Accessors(chain = true)
    @Schema(description = "飞手效率")
    public static class PilotRow implements Serializable {

        private Long pilotId;

        /** 姓名冗余（花名册上的名字；飞手改了名也不会对不上历史） */
        private String name;

        @Schema(description = "区间内派给他的次数（含他拒掉的那些）—— 拒绝率的分母")
        private Integer offers;

        @Schema(description = "区间内他拒绝的次数（按拒绝流水）")
        private Integer refused;

        @Schema(description = "拒绝率 = 拒绝 ÷ 派给他的次数，%；一次没派过时为 null")
        private Double refuseRate;

        @Schema(description = "区间内结束的活（完成 + 失败）")
        private Integer taskCount;

        @Schema(description = "其中完成的")
        private Integer finished;

        @Schema(description = "按时完成率，%；无样本时为 null")
        private Double onTimeRate;

        @Schema(description = "区间内开过的活（含还在飞的）—— 无旁证占比的分母")
        private Integer started;

        @Schema(description = "其中「只有声明、没有设备旁证」的条数")
        private Integer noProof;

        @Schema(description = "无旁证占比，%；区间内一条都没开过时为 null")
        private Double noProofRate;

        @Schema(description = "报告里填的飞行时长合计（小时）")
        private Double hours;

        @Schema(description = "报告里填的飞行架次合计")
        private Integer sorties;
    }

    /** 一台设备在这段时间里的使用与故障 */
    @Data
    @Accessors(chain = true)
    @Schema(description = "设备效率")
    public static class DeviceRow implements Serializable {

        private Long deviceId;
        private String code;
        private String name;

        @Schema(description = "当前工作状态（快照）：idle / assigned / flying / maintenance …")
        private String status;

        @Schema(description = "区间内它在役的天数（与「采购日 → 区间末」取交集）")
        private Integer inServiceDays;

        @Schema(description = "可飞时长 = 在役天数 × 每日可飞小时")
        private Double flyableHours;

        @Schema(description = "报告里填的飞行时长合计（小时）")
        private Double hours;

        @Schema(description = "报告里填的架次合计")
        private Integer sorties;

        /**
         * 利用率 = 飞行时长 ÷ 可飞时长，%。
         *
         * <p>⚠️ 这是**近似**：维护/充电的日子也算进了分母，所以结果偏低（保守）。
         * 要做准得有设备状态变更历史，而你拍板不做（见文档 8.2 第 7 条）。
         */
        @Schema(description = "利用率，%；可飞时长为 0 时为 null")
        private Double utilization;

        @Schema(description = "区间内这台设备的告警数（按设备编号关联）")
        private Integer alerts;

        @Schema(description = "其中严重的")
        private Integer criticalAlerts;

        @Schema(description = "每 100 飞行小时的告警数；没有飞行时长时为 null")
        private Double faultRatePer100h;
    }
}
