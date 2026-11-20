package cn.tedu.drone.task.pojo;

import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 任务附件 —— 对应 {@code task_attachment} 表。
 *
 * <h2>两种归属</h2>
 * <ul>
 *   <li>{@code task_id}：<b>必有</b>。上传总是发生在某条任务的上下文里。
 *       报告线的照片与飞行日志就只有它</li>
 *   <li>{@code alert_id}：<b>可空</b>。飞手在「现场异常上报」里带的照片会补上它 ——
 *       那张照片要能顺着告警被管理侧看到，只挂任务的话得靠时间窗去猜是哪条告警</li>
 * </ul>
 *
 * <h2>为什么要同时存 file_path 与 storage_key</h2>
 * 前者是「本地磁盘时代」的相对路径，后者是「对象存储时代」的 key，
 * {@code storage} 决定读哪一边。迁移期两边都在；等全迁完可以把 file_path 停用，
 * 但<b>不删列</b>（删列不可逆，留着占不了多少空间，却能在出问题时回退）。
 * 详细手册见 {@code docs/任务收尾与执行报告.md} 第八章。
 */
@Data
@Accessors(chain = true)
public class TaskAttachmentPO implements Serializable {

    private Long id;

    /** 所属任务（必有） */
    private Long taskId;

    /** 所属告警（可空，见类注释） */
    private Long alertId;

    /**
     * 原始文件名，<b>只用于展示</b>。
     *
     * <p>绝不拿它拼路径：真实文件叫 uuid，见 {@code LocalStorageService} 的类注释。
     */
    private String fileName;

    /** 本地存储的相对路径（迁移期与 storage_key 并存） */
    private String filePath;

    /** 存储位置：local / oss */
    private String storage;

    /** 存储内的 key —— 读取时用的就是它 */
    private String storageKey;

    /** 内容的 SHA-256（十六进制小写）：完整性、去重、迁对象存储时的对账依据 */
    private String sha256;

    /** MIME 类型，如 image/jpeg；下载时用来设 Content-Type */
    private String mimeType;

    /** 业务分类：image / video / log，见 {@link AttachmentType} */
    private String fileType;

    private Long fileSize;

    /** 展示顺序，不是按上传时间乱排 */
    private Integer sortNo;

    private String description;

    private Long uploadBy;

    private String uploadByName;

    private LocalDateTime createTime;
}
