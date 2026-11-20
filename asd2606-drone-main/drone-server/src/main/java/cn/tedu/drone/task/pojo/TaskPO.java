package cn.tedu.drone.task.pojo;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 任务，对应 task 表
 */
@Data
@Accessors(chain = true)
@Schema(description = "任务")
public class TaskPO implements Serializable {

    @Schema(description = "主键")
    private Long id;

    @Schema(description = "任务编号，如 TK-20260918-001")
    private String taskNo;

    @Schema(description = "任务名称")
    private String taskName;

    @Schema(description = "优先级 P0 紧急 / P1 高 / P2 中 / P3 低")
    private String priority;

    @Schema(description = "状态：pending/dispatched/executing/finished/failed/cancelled")
    private String status;

    @Schema(description = "计划开始")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime planStartTime;

    @Schema(description = "计划结束")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime planEndTime;

    @Schema(description = "实际开始")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime actualStartTime;

    @Schema(description = "实际结束")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime actualEndTime;

    @Schema(description = "指派的设备 ID")
    private Long droneId;

    @Schema(description = "设备编号")
    private String droneCode;

    @Schema(description = "指派的飞手 ID")
    private Long pilotId;

    @Schema(description = "飞手姓名")
    private String pilotName;

    @Schema(description = "任务内容 / 航线描述")
    private String routeInfo;

    /**
     * 引用的航线模板 id，可空。
     *
     * <p>⚠️ <b>这一列不能用来回答「当时飞的是哪条线」</b> —— 模板可以被改、
     * 也可以被删。当时的航线在 {@link #routeSnapshot} 里，
     * 这个 id 只是一条「它当初从哪来」的追溯线索。
     */
    @Schema(description = "引用的航线模板 id，可空")
    private Long routeId;

    /**
     * 航线快照（json 原文）。
     *
     * <p>创建任务时把当时那份航线整段复制下来，之后模板怎么改都不影响这条任务。
     * 格式定义在 {@code RouteSnapshot}，读写都在 {@code RouteService} 里 ——
     * <b>这个类只负责把它搬进搬出，不解释内容</b>（所以它没有对应的 VO 字段，
     * 也就不会顺着接口泄出去）。
     */
    @Schema(description = "航线快照（json，坐标 WGS84）")
    private String routeSnapshot;

    @Schema(description = "起降点经度 WGS84")
    private Double takeoffLng;

    @Schema(description = "起降点纬度 WGS84")
    private Double takeoffLat;

    @Schema(description = "起降点名称")
    private String takeoffName;

    @Schema(description = "补充说明")
    private String description;

    @Schema(description = "创建人 ID")
    private Long createBy;

    @Schema(description = "创建人姓名")
    private String createByName;

    /**
     * 执行依据状态：none / declared / confirmed / stale。
     *
     * <p><b>它和 {@link #status} 是两个正交的维度</b>：
     * status 是「业务走到哪一步」，这个字段是「这一步有没有客观依据」。
     * 所以「执行中 + 仅声明」是合法组合，加证据这件事没有改动状态机一个箭头。
     * 详见 docs/任务业务设计.md 第十章。
     */
    @Schema(description = "执行依据：none 无 / declared 仅飞手声明 / confirmed 已证实 / stale 声明已过期")
    private String evidenceState;

    @Schema(description = "最新一条支撑证据的类型：telemetry / flight_log / witness / manual")
    private String evidenceType;

    /** 证据时刻取的是「我们收到」的时间，不是设备报的时间 —— 理由见 TaskDao#markEvidenceConfirmed */
    @Schema(description = "最新一条证据的时刻")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime evidenceAt;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updateTime;
}
