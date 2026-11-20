package cn.tedu.drone.task.pojo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.util.List;

/**
 * 飞手现场异常上报的入参。
 *
 * <h2>没有 pilotId 字段，这是故意的</h2>
 * 上报人从登录态取。前端就算想传也没得传 —— 不给他留这个口子，
 * 就少一类「传了会不会被采纳」的疑问。
 *
 * <h2>为什么附件要分两步：先上传拿 id，再随上报提交</h2>
 * 因为照片可能在飞手写说明之前就选好了，而且上传失败时他应该能重试上传
 * 而不必把说明重写一遍。所以上传是独立接口，本参数里只带附件 id。
 * 这个思路与设计文档 2.1 节「完成任务」那个接口把附件做成 id 列表是一致的。
 */
@Data
@Accessors(chain = true)
@Schema(description = "现场异常上报入参")
public class TaskAlertReportParam implements Serializable {

    @Schema(description = "任务 id")
    private Long taskId;

    /**
     * 告警编码，只能取人工上报类那 4 条
     * （`man.onsite.issue` / `man.device.damage` / `man.weather` / `man.customer.issue`）。
     *
     * <p>服务端会校验类别 —— 传设备类的编码（如 `bat.critical`）会被拒，
     * 否则飞手能伪造一条看起来像设备实测的告警。理由见 {@link AlertCode#MANUAL_CODES}。
     */
    @Schema(description = "告警编码，只能取人工上报类 4 条")
    private String alertCode;

    @Schema(description = "说明：现场到底发生了什么。必填 —— 管理员要据此判断怎么处理")
    private String message;

    @Schema(description = "附件 id 列表（现场照片）。可空；每张都要先调上传接口拿到 id")
    private List<Long> attachmentIds;
}
