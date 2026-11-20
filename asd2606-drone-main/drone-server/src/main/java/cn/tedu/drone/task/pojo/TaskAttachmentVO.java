package cn.tedu.drone.task.pojo;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 附件的视图对象。
 *
 * <h2>刻意<b>不</b>包含的三样东西</h2>
 * <ul>
 *   <li>{@code filePath} / {@code storageKey} —— <b>存储布局是服务端的内部实现</b>。
 *       吐出去等于把「文件放在哪」变成接口契约的一部分，将来换对象存储就是破坏性变更。
 *       客户端要文件就走 {@code GET /task/attachment/{id}/download}，那次调用要过权限校验</li>
 *   <li>{@code sha256} —— 客户端用不上。它是给服务端做完整性对账与去重用的</li>
 * </ul>
 * 一句话：<b>这个 VO 只回答「有哪些附件、叫什么、多大、什么时候传的」</b>。
 */
@Data
@Accessors(chain = true)
@Schema(description = "附件视图对象")
public class TaskAttachmentVO implements Serializable {

    @Schema(description = "主键，下载时用它拼地址")
    private Long id;

    @Schema(description = "原始文件名（展示用）")
    private String fileName;

    @Schema(description = "类型：image 照片 / video 视频 / log 飞行日志")
    private String fileType;

    @Schema(description = "MIME 类型")
    private String mimeType;

    @Schema(description = "字节数")
    private Long fileSize;

    @Schema(description = "说明")
    private String description;

    @Schema(description = "上传人")
    private String uploadByName;

    @Schema(description = "上传时间")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;
}
