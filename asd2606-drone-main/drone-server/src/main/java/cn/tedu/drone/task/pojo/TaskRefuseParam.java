package cn.tedu.drone.task.pojo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * 拒绝执行参数。
 *
 * <p><b>原因建议填，但不强制</b>：拒绝本身已经是一个明确的态度，
 * 强行要求填原因只会让飞手随手敲两个字应付。真正有价值的场景是
 * 「我说明白为什么不去」—— 管理员据此决定是改时间、换设备还是换人。
 * 所以界面上给输入框并提示用途，但不拦着提交。
 */
@Data
@Accessors(chain = true)
@Schema(description = "拒绝执行参数")
public class TaskRefuseParam implements Serializable {

    @Schema(description = "任务 ID")
    private Long taskId;

    @Schema(description = "拒绝原因，选填；会记进流转流水并一起推给管理员")
    private String reason;
}
