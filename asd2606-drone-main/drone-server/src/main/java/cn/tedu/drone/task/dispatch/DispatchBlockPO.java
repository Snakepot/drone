package cn.tedu.drone.task.dispatch;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 待派发任务的一次归因采样，对应 {@code task_dispatch_block} 表。
 *
 * <p>它是一条**历史事实**：那一刻这条任务为什么派不出去。
 * 所以它只增不改 —— 事后没有任何理由去改一条「当时的观测」。
 */
@Data
@Accessors(chain = true)
@Schema(description = "待派发任务的归因点")
public class DispatchBlockPO implements Serializable {

    @Schema(description = "主键")
    private Long id;

    @Schema(description = "待派发任务 id")
    private Long taskId;

    @Schema(description = "采样时刻")
    private LocalDateTime checkedAt;

    @Schema(description = "归因：blocked_pilot / blocked_drone / blocked_both / idle")
    private String reason;

    /** 当时的候选数：只记「没有人」看不出是差 1 个还是差 5 个 */
    @Schema(description = "当时可派的飞手数")
    private Integer pilotCandidates;

    @Schema(description = "当时待命且不冲突的设备数")
    private Integer droneCandidates;
}
