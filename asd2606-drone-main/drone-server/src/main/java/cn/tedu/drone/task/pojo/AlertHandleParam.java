package cn.tedu.drone.task.pojo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * 处理告警的入参。
 *
 * <p>三个动作共用这一个入参，而不是开三个接口（{@code /alert/claim}、
 * {@code /alert/resolve}、{@code /alert/ignore}）：它们改的是同一个东西，
 * 校验走的是同一张状态机表，分成三个接口只会让权限点、前端调用、测试
 * 都各写三份。用 {@code action} 区分，状态机一眼就能看全。
 */
@Data
@Accessors(chain = true)
@Schema(description = "告警处理入参")
public class AlertHandleParam implements Serializable {

    @Schema(description = "告警 ID")
    private Long id;

    /**
     * 处理动作，见 {@link AlertStatusFlow}：
     * <ul>
     *   <li>{@code claim} 认领（待处理 → 处理中）</li>
     *   <li>{@code resolve} 标记为已处理（处理中 → 已处理）</li>
     *   <li>{@code ignore} 标记为误报（待处理/处理中 → 误报）</li>
     * </ul>
     */
    @Schema(description = "处理动作：claim 认领 / resolve 标记已处理 / ignore 标记误报")
    private String action;

    /**
     * 处置说明。
     *
     * <p><b>认领时可以不填，收尾（已处理 / 误报）时必填</b> ——
     * 一个没有说明的「已处理」和不处理没有区别，事后复盘时仍然是黑盒；
     * 「误报」更要写清为什么是误报，那是调阈值最直接的依据。
     */
    @Schema(description = "处置说明。收尾（resolve / ignore）时必填")
    private String note;
}
