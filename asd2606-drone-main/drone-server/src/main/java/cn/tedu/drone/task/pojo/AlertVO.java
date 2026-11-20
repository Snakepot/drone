package cn.tedu.drone.task.pojo;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 告警视图对象（管理端告警页的一行）。
 *
 * <h2>为什么这里带上中文名，而 {@link TaskVO} 不带</h2>
 * 任务的 status / priority 只有几个取值，前端自己写一份字典很轻；
 * 而告警编码有 <b>27 个</b>，前端的字典必然和后端的 {@link AlertCode} 漂移 ——
 * 后端加了新告警、前端没跟着加，页面上就是一片英文编码。
 *
 * <p>所以这几个「中文名」由后端给：**编码与中文名的对应只维护一处**
 * （{@code AlertCode} / {@code AlertSeverity} / {@code AlertStatusFlow}），
 * 前端只负责展示。代价是响应体大一点点，可以忽略。
 *
 * <p>注意仍然只给「原始编码 + 中文名」，不给「用什么颜色」——
 * 颜色是展示层的事，后端不该管。
 */
@Data
@Accessors(chain = true)
@Schema(description = "告警视图对象")
public class AlertVO implements Serializable {

    @Schema(description = "主键")
    private Long id;

    @Schema(description = "所属任务 ID，可能为空（告警可以不属于任何任务）")
    private Long taskId;

    @Schema(description = "设备编号，可能为空（人工上报的告警可能不涉及设备）")
    private String deviceCode;

    @Schema(description = "告警编码，如 bat.critical")
    private String alertCode;

    @Schema(description = "告警编码的中文名，如「电量严重不足」")
    private String alertCodeLabel;

    @Schema(description = "严重级别：info / warning / critical")
    private String severity;

    @Schema(description = "级别中文名：提示 / 警告 / 严重")
    private String severityLabel;

    @Schema(description = "给用户看的一句话，如「电量仅 12%（阈值 ≤15%）」")
    private String message;

    @Schema(description = "来源：device 设备 / app 飞手端 / operator 人工")
    private String source;

    @Schema(description = "告警描述的那个时刻（设备说的或人填的）")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime observedAt;

    @Schema(description = "我们收到的时刻")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime reportedAt;

    @Schema(description = "处理状态：pending / handling / resolved / ignored")
    private String status;

    @Schema(description = "状态中文名：待处理 / 处理中 / 已处理 / 误报")
    private String statusLabel;

    @Schema(description = "处理人姓名")
    private String handledByName;

    @Schema(description = "处理完成时刻")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime handledAt;

    @Schema(description = "处置说明：做了什么、结论是什么")
    private String handleNote;

    @Schema(description = "是否需要飞手在现场动手")
    private Boolean pilotActionRequired;

    @Schema(description = "给飞手看的动作提示")
    private String actionHint;

    /**
     * 这条告警带的附件（现场照片）。
     *
     * <p>为什么把附件<b>嵌进告警</b>、而不是让前端再调一个「按告警查附件」的接口：
     * 那样列表页就是 1 + N 次请求，而且那个新接口要同时满足两种调用方
     * （管理端有 {@code alert:list}、飞手端只有 {@code task:execute} + 本人任务），
     * 权限点又得再造一套。嵌进来之后，能拿到告警的人自然就能拿到它的附件，
     * 权限只在「能不能看这条告警」这一处判。
     *
     * <p>返回给前端的是 {@link TaskAttachmentVO}：**只有文件名、大小、时间**，
     * 没有存储路径。要文件得走 {@code GET /task/attachment/{id}/download}，那次要过权限。
     */
    @Schema(description = "现场照片等附件（没有附件时为空列表）")
    private List<TaskAttachmentVO> attachments = List.of();
}
