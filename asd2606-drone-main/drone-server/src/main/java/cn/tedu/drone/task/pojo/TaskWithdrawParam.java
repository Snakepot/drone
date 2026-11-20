package cn.tedu.drone.task.pojo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * 撤回派发参数。
 *
 * <p>只要一个任务 ID 和一句原因。撤回之后任务退回「待派发」，
 * 飞手与设备的指派会被清空 —— 这点必须在界面上跟用户说清楚，
 * 否则他会以为「撤回」只是取消等待，实际是指派关系没了。
 */
@Data
@Accessors(chain = true)
@Schema(description = "撤回派发参数")
public class TaskWithdrawParam implements Serializable {

    @Schema(description = "任务 ID")
    private Long taskId;

    @Schema(description = "撤回原因，选填，记进流转流水")
    private String note;
}
