package cn.tedu.drone.task.pojo;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 任务状态流转日志，对应 task_log 表。
 *
 * <p>只追加、不修改：状态一变就记一条，任务详情页的时间轴就是把它按时间排出来。
 */
@Data
@Accessors(chain = true)
@Schema(description = "任务流转日志")
public class TaskLogPO implements Serializable {

    private Long id;

    @Schema(description = "所属任务")
    private Long taskId;

    @Schema(description = "动作：create/dispatch/withdraw/start/finish/fail/cancel")
    private String action;

    @Schema(description = "变更前状态，创建时为 null")
    private String fromStatus;

    @Schema(description = "变更后状态")
    private String toStatus;

    @Schema(description = "操作人 ID")
    private Long operatorId;

    @Schema(description = "操作人姓名")
    private String operatorName;

    @Schema(description = "操作时的角色，便于复盘权限问题")
    private String operatorRole;

    @Schema(description = "附言，如撤回原因")
    private String note;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;
}
