package cn.tedu.drone.task.pojo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 一行告警，对应 {@code task_alert} 表。
 *
 * <h2>它和 {@link TaskEvidencePO} 的关系：两张表、两个问题</h2>
 * <ul>
 *   <li>证据回答「这件事<b>发生过</b>吗」—— 只追加，不可改</li>
 *   <li>告警回答「哪里<b>不对劲</b>」—— 有状态，要有人处理、要能关掉</li>
 * </ul>
 * 一条「电量低」的遥测会<b>同时</b>产生两边的记录：证据里是这次飞行的客观记录，
 * 告警里是一条待处理的问题。两者都留，因为它们回答不同问题。
 *
 * <p><b>task_id 与 device_code 都允许为空</b>：
 * 设备上报时可能并没有正在执行的任务（试飞、维护、机库自检），
 * 但那条告警仍然必须记下来、必须有人管 —— 这跟证据表是同一个思路。
 * 人工上报的告警甚至可能连设备都没有（比如「今天所有设备电量都异常偏低」）。
 */
@Data
@Accessors(chain = true)
@Schema(description = "任务/设备告警")
public class AlertPO implements Serializable {

    @Schema(description = "主键")
    private Long id;

    @Schema(description = "所属任务；为空表示与任何任务无关")
    private Long taskId;

    @Schema(description = "设备编号；人工上报可能没有")
    private String deviceCode;

    @Schema(description = "告警编码，取值见 AlertCode（与 docs/告警体系设计.md 第二章一致）")
    private String alertCode;

    @Schema(description = "级别：info / warning / critical")
    private String severity;

    @Schema(description = "人可读的一句话，列表与推送直接用它")
    private String message;

    @Schema(description = "原始内容，JSON 文本（设备报文原文或人填的表单）")
    private String payload;

    @Schema(description = "来源：device 设备 / app 飞手端 / operator 人工")
    private String source;

    @Schema(description = "告警描述的那个时刻（设备说的或人填的）")
    private LocalDateTime observedAt;

    @Schema(description = "我们收到的时刻")
    private LocalDateTime reportedAt;

    @Schema(description = "pending 待处理 / handling 处理中 / resolved 已处理 / ignored 误报")
    private String status;

    @Schema(description = "处理人 ID")
    private Long handledBy;

    @Schema(description = "处理人姓名冗余")
    private String handledByName;

    @Schema(description = "处理完成时刻")
    private LocalDateTime handledAt;

    @Schema(description = "处置说明")
    private String handleNote;

    /** 1 表示需要飞手在现场动手（见设计文档第十二章）；由 AlertProperties.pilotAction 折算而来 */
    @Schema(description = "是否需要飞手在现场动手")
    private Integer pilotActionRequired;

    @Schema(description = "给飞手看的动作提示")
    private String actionHint;
}
