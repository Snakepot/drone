package cn.tedu.drone.task.pojo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * 任务派发参数。
 *
 * <p>派发要做的事就一句话：<b>指定谁去做、用哪台设备</b>。
 * 所以参数只有这三样（加一句附言）。
 *
 * <p><b>注意这里没有 status</b>：状态由服务端按状态机推导（派发后一定是
 * {@code dispatched}），不接受前端指定 —— 和创建任务时把 status 从参数里拿掉是同一个道理。
 * 派发人也不在这里，从登录态取。
 */
@Data
@Accessors(chain = true)
@Schema(description = "任务派发参数")
public class TaskDispatchParam implements Serializable {

    @Schema(description = "任务 ID")
    private Long taskId;

    @Schema(description = "飞手 ID")
    private Long pilotId;

    @Schema(description = "设备 ID")
    private Long droneId;

    @Schema(description = "附言，选填，记进流转流水")
    private String note;

    /**
     * 「已知晓禁飞区风险，仍然派发」+ 原因。
     *
     * <p>为什么<b>派发时还要再校验一次</b>（创建时已经查过了）：
     * 任务可能是上周建的，而禁飞区是昨天才画的 —— 那正是最需要拦住的时刻。
     * 区域还会有生效期（临时管制今天 8 点开始），所以这两次校验的结果**可以不一样**。
     *
     * <p>含义与 {@code TaskCreateParam} 上的同名参数完全一致。
     */
    @Schema(description = "已知晓禁飞区风险、仍要派发；命中时必填 true")
    private Boolean acknowledgedZones;

    @Schema(description = "已知晓风险的原因，命中并确认时必填")
    private String zoneReason;
}
