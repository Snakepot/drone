package cn.tedu.drone.task.pojo;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 报告视图对象 —— 报告详情页要的全部东西。
 *
 * <h2>它由两半组成</h2>
 * <ul>
 *   <li><b>飞手手填的</b>：结论、总结、问题日志、飞行时长/架次（{@code report}）</li>
 *   <li><b>系统自动汇总的</b>：飞了多久、收到多少条数据、最低电量、期间几条告警、
 *       附件清单（其余字段）</li>
 * </ul>
 *
 * <p>为什么两半放在一个 VO 里返回：详情页要一起显示，分两个接口就是两次往返；
 * 而它们回答的正是同一个问题 ——「这次任务到底做了什么」。
 *
 * <p><b>自动汇总那部分不落库</b>（每次现算），理由见 {@link TaskEvidenceSummary}。
 */
@Data
@Accessors(chain = true)
@Schema(description = "任务报告（手填 + 自动汇总）")
public class TaskReportVO implements Serializable {

    // ---------- 飞手手填的部分 ----------

    @Schema(description = "报告 id")
    private Long id;

    @Schema(description = "版本号，从 1 开始；每修订一次 +1")
    private Integer version;

    @Schema(description = "结论：success 成功 / partial 部分完成 / failed 失败")
    private String result;

    @Schema(description = "结论中文名")
    private String resultLabel;

    @Schema(description = "执行总结")
    private String summary;

    @Schema(description = "遇到的问题与处置")
    private String problemLog;

    @Schema(description = "本次飞行时长（小时）")
    private BigDecimal flightHours;

    @Schema(description = "本次飞行架次")
    private Integer flightCount;

    @Schema(description = "上报人姓名")
    private String reporterName;

    @Schema(description = "提交时间")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;

    // ---------- 系统自动汇总的部分（每次现算，不落库）----------

    @Schema(description = "任务编号，如 TK-20260920-001")
    private String taskNo;

    @Schema(description = "任务名称")
    private String taskName;

    @Schema(description = "任务状态")
    private String taskStatus;

    @Schema(description = "飞手姓名")
    private String pilotName;

    @Schema(description = "执行设备编号")
    private String droneCode;

    @Schema(description = "实际开始时刻")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime actualStartTime;

    @Schema(description = "实际结束时刻")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime actualEndTime;

    @Schema(description = "按实际起止算出的时长（小时，保留两位）；起止缺一个就是空")
    private BigDecimal computedHours;

    @Schema(description = "收到多少条设备数据（证据行数）")
    private Integer telemetryCount;

    @Schema(description = "数据时间范围起")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime dataFrom;

    @Schema(description = "数据时间范围止")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime dataTo;

    @Schema(description = "期间最低电量（百分比）；设备没报过就是空")
    private BigDecimal minBattery;

    @Schema(description = "期间最大高度（米）；设备没报过就是空")
    private BigDecimal maxAlt;

    @Schema(description = "执行依据：none / declared / confirmed / stale")
    private String evidenceState;

    @Schema(description = "执行依据的中文说明")
    private String evidenceLabel;

    /**
     * 本次执行是否<b>只有飞手的声明</b>、没有任何设备数据佐证。
     *
     * <p>界面上把这一条标红 —— 完成**不强制**要求有证据（强制会把
     * 「设备数据链路不通」变成「飞手无法收工」，用大问题换小问题），
     * 但必须让人看见。见 {@code docs/任务收尾与执行报告.md} 2.3 节。
     */
    @Schema(description = "true 表示本次执行只有飞手声明、无设备数据佐证")
    private Boolean onlyDeclared;

    @Schema(description = "期间告警条数")
    private Integer alertCount;

    @Schema(description = "附件清单（现场照片、飞行日志）")
    private List<TaskAttachmentVO> attachments;
}
