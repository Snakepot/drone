package cn.tedu.drone.task.pojo;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 任务创建参数。
 *
 * <p><b>注意这里刻意没有哪些字段</b>：
 * <ul>
 *   <li>{@code status} —— 创建出来的任务永远是 {@code pending}，状态只能通过流转接口改变。
 *       参数里不放它，前端就传不进来，服务端也不用写「忽略传入状态」的防御代码</li>
 *   <li>{@code createBy} —— 创建人从登录态取。前端能传就等于允许冒名创建</li>
 *   <li>{@code pilotId} / {@code droneId} —— 指派谁、用哪台设备是「派发」环节的事，
 *       创建只回答「要做什么」</li>
 * </ul>
 * 把不该有的字段从参数对象里拿掉，比在 Service 里一个个校验「这个字段不许传」更可靠。
 */
@Data
@Accessors(chain = true)
@Schema(description = "任务创建参数")
public class TaskCreateParam implements Serializable {

    @Schema(description = "任务名称")
    private String taskName;

    @Schema(description = "优先级 P0 紧急 / P1 高 / P2 中 / P3 低")
    private String priority;

    @Schema(description = "计划开始时间")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime planStartTime;

    @Schema(description = "计划结束时间")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime planEndTime;

    @Schema(description = "任务内容 / 航线描述")
    private String routeInfo;

    @Schema(description = "补充说明")
    private String description;

    /**
     * 引用的航线模板 id，可空。
     *
     * <p>选了它，创建时会做两件事：把航线<b>快照</b>一份存进任务
     * （之后模板怎么改都不影响这条任务，见 {@code RouteService#snapshotJsonOf}），
     * 以及在 {@link #routeInfo} 为空时自动填一句人看的摘要。
     *
     * <p>不选也行 —— 临时任务仍然可以直接在 {@link #routeInfo} 里写一句
     * 「绕操场三圈」之类的文字。这一步没有把老路堵死。
     */
    @Schema(description = "引用的航线模板 id，可空")
    private Long routeId;

    /**
     * 起降点，⚠️ <b>坐标是 GCJ-02</b>（人在地图上点的）。
     *
     * <p>三个字段要么都给、要么都不给 —— 只有名称没有坐标，地图上画不出东西；
     * 只有坐标没有名称，飞手不知道这是「哪个起降点」。所以服务端按
     * 「给了任一就要求给全」来校验。
     *
     * <p>为什么单独存起降点、而不是把它当成航线的第一个点：<b>它常常不在航线上</b>
     * （从基地起飞、飞到航线起点），而且它是安全信息（出事往哪落），
     * 和「按顺序飞哪几个点」是两件事。
     */
    @Schema(description = "起降点经度（GCJ-02），可空")
    private Double takeoffLng;

    @Schema(description = "起降点纬度（GCJ-02），可空")
    private Double takeoffLat;

    @Schema(description = "起降点名称，可空")
    private String takeoffName;

    /**
     * 「已知晓禁飞区风险，仍要创建」。
     *
     * <h2>为什么是「软拦」而不是硬拦</h2>
     * 系统拦不住已经起飞的飞机，它能做的是「<b>在事前把风险摆到人面前</b>」。
     * 而现实里确实存在必须飞进去的场景（机场大修期间的临时管制、
     * 客户自己划的范围但当天有批文）—— 硬拦只会逼着人去删掉那个禁飞区，
     * 那等于把安全控制整个关掉，还不如让他签个名。
     *
     * <p>所以规则是：<b>命中就必须明确确认，并且写下原因</b>，
     * 这段确认会进流转流水（`task_log.note`），事后对账时看得到。
     * 详见 {@code docs/地图接入方案.md} 6.3.2。
     */
    @Schema(description = "已知晓禁飞区风险、仍要创建；命中时必填 true")
    private Boolean acknowledgedZones;

    /** 已知晓风险的原因（留痕用）。命中并确认时必填、至少 4 个字 —— 见 {@code TaskServiceImpl} */
    @Schema(description = "已知晓风险的原因，命中并确认时必填")
    private String zoneReason;
}
