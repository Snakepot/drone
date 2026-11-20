package cn.tedu.drone.task.pojo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.List;

/**
 * 「完成任务」/「标记失败」的入参 —— 一个接口带齐状态、资源与交付物。
 *
 * <h2>为什么不是「先提交报告、再点完成」两步</h2>
 * 那会造出一个<b>没有业务含义的中间态</b>：任务还是执行中，但报告已经交了。
 * 判断一个中间态值不值得存在，看<b>有没有人会在这个状态下做决策</b> ——
 * 「报告已交但任务没完成」时，没有任何人需要做任何事。
 * 详见 {@code docs/任务收尾与执行报告.md} 2.2 节。
 *
 * <h2>附件为什么是 id 列表</h2>
 * 照片要先通过 {@code POST /task/attachment/upload} 传上去拿到 id，再随这里提交。
 * 大文件传输和业务提交分开，任何一边失败都好重试。
 */
@Data
@Accessors(chain = true)
@Schema(description = "完成任务入参")
public class TaskFinishParam implements Serializable {

    @Schema(description = "任务 id")
    private Long taskId;

    /**
     * 结论：{@code success} 成功 / {@code partial} 部分完成。
     *
     * <p><b>注意这里收不到 {@code failed}</b> —— 「失败」走 {@code POST /task/fail}，
     * 它的 {@code result} 由服务端写死。这样 {@code result} 就只描述
     * 「作业完成度」，不描述任务状态，两者不会打架（见设计文档 12.1）。
     */
    @Schema(description = "结论：success 成功 / partial 部分完成（失败走 /task/fail）")
    private String result;

    @Schema(description = "执行总结：做了什么、结果如何")
    private String summary;

    @Schema(description = "遇到的问题与处置（选填；标记失败时必填）")
    private String problemLog;

    /** 飞行时长（小时）。界面应当预填自动算出来的值，让飞手确认或修正 */
    @Schema(description = "本次飞行时长（小时），0 ~ 24")
    private BigDecimal flightHours;

    @Schema(description = "本次飞行架次")
    private Integer flightCount;

    @Schema(description = "附件 id 列表（先调上传接口拿到的）")
    private List<Long> attachmentIds;
}
