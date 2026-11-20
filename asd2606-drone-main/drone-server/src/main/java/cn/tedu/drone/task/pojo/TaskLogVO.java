package cn.tedu.drone.task.pojo;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 任务流转日志视图对象，返回给前端（详情页时间轴用它渲染）。
 *
 * <p>它只是一条「谁在什么时候把状态从 A 改成了 B」的记录，
 * 对前端来说就是一个只读列表项，所以这里把表结构一比一映射出去，
 * 不做裁剪 —— 时间轴要展示的就是这些信息本身。
 */
@Data
@Accessors(chain = true)
@Schema(description = "任务流转日志视图对象")
public class TaskLogVO implements Serializable {

    @Schema(description = "主键")
    private Long id;

    @Schema(description = "动作：create/dispatch/withdraw/start/finish/fail/cancel")
    private String action;

    @Schema(description = "变更前状态，创建时为 null")
    private String fromStatus;

    @Schema(description = "变更后状态")
    private String toStatus;

    @Schema(description = "操作人姓名")
    private String operatorName;

    @Schema(description = "操作时的角色，便于复盘权限问题")
    private String operatorRole;

    @Schema(description = "附言，如撤回原因")
    private String note;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;
}
