package cn.tedu.drone.task.pojo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.List;

/**
 * 修订报告的入参。
 *
 * <h2>为什么它和 {@link TaskFinishParam} 长得像、却不复用同一个类</h2>
 * 长得像：手填的那几个字段完全一样（结论、总结、问题、时长、架次、附件）。
 * 但**语义不同**，而且这个差别很要紧：
 * <ul>
 *   <li>{@code TaskFinishParam} 是「<b>收尾</b>」—— 它同时要动任务状态和设备/飞手资源</li>
 *   <li>本类是「<b>改报告</b>」—— 按设计文档 6.2 节那句话：<b>修订只动报告、不动任务状态</b>。
 *       任务可能早就完成了，飞手事后发现写错了一个数字，改的只是那一份交付物</li>
 * </ul>
 * 复用一个类会让「提交这个东西会不会把任务状态也改了」变成需要读实现才知道的事 ——
 * 那种模糊比多声明六个字段贵得多。
 *
 * <h2>结论这里收三个值</h2>
 * 与收尾不同：收尾只收 {@code success}/{@code partial}（失败走 fail 接口，
 * 免得「任务完成了」和「结论是失败」互相打架）。而修订是在**事后复核**，
 * 「当初标成功、后来发现漏检了 2 个点位」正是设计文档 6.3 列的场景 3 ——
 * 那时结论就该能改成 {@code partial} 甚至 {@code failed}。
 * 任务状态不动，所以不会打架。
 */
@Data
@Accessors(chain = true)
@Schema(description = "修订报告入参")
public class TaskReportReviseParam implements Serializable {

    @Schema(description = "任务 id（报告挂在任务上）")
    private Long taskId;

    @Schema(description = "结论：success 成功 / partial 部分完成 / failed 失败")
    private String result;

    @Schema(description = "执行总结")
    private String summary;

    @Schema(description = "遇到的问题与处置")
    private String problemLog;

    @Schema(description = "本次飞行时长（小时），0 ~ 24")
    private BigDecimal flightHours;

    @Schema(description = "本次飞行架次")
    private Integer flightCount;

    @Schema(description = "附件 id 列表；不传表示沿用上一次的顺序不变")
    private List<Long> attachmentIds;
}
