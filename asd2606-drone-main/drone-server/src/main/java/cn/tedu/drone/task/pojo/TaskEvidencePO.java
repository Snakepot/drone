package cn.tedu.drone.task.pojo;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 一条任务执行证据，对应 {@code task_evidence} 表。
 *
 * <h2>为什么证据要单独一张表，而不是在 task 上加几列</h2>
 * 因为「决定一条任务有没有在飞」靠的不是某一列，而是<b>一串客观记录</b>：
 * 一架飞机一次任务会产出成百上千条遥测。这些记录有三个特点：
 * <ul>
 *   <li><b>一条任务对应多条</b> —— 塞进 task 表就意味着要么只存一条（丢信息），
 *       要么存 JSON 数组（没法按时间去查、没法建索引）</li>
 *   <li><b>数量级完全不是一个量级</b> —— task 是几百行，证据是几百万行。
 *       放同一张表会让「查任务列表」这个高频操作被无关的大数据拖慢</li>
 *   <li><b>生命周期不同</b> —— 任务可以长期保留，原始遥测将来可能需要归档或按保留期清理</li>
 * </ul>
 *
 * <h2>task_id 允许为空，这不是偷懒</h2>
 * 设备是<b>按自己的节奏</b>发数据的：试飞、转场、维护时也会发。
 * 那些数据客观存在、值得留痕，但确实不属于任何一条任务。
 * 强行要求必须有 task_id，就等于要么丢弃这些数据，要么给它们编一个假任务，
 * 两种都比允许空更糟。
 */
@Data
@Accessors(chain = true)
@Schema(description = "任务执行证据")
public class TaskEvidencePO implements Serializable {

    @Schema(description = "主键")
    private Long id;

    /** 允许为空：数据可能不属于任何正在执行的任务 */
    @Schema(description = "所属任务 ID，为空表示未关联到任何任务")
    private Long taskId;

    @Schema(description = "设备编号，对应 drone.code")
    private String deviceCode;

    @Schema(description = "证据类型：telemetry / flight_log / witness / manual")
    private String type;

    @Schema(description = "来源：device / app / operator")
    private String source;

    @Schema(description = "证据是文件时指向 task_attachment")
    private Long attachId;

    @Schema(description = "证据内容，JSON 文本")
    private String payload;

    @Schema(description = "设备自增序号，参与唯一键去重")
    private Long seq;

    @Schema(description = "证据描述的那个时刻（设备说的，不等于我们收到的时刻）")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime observedAt;

    @Schema(description = "我们收到它的时刻（我们自己的时钟）")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime reportedAt;

    @Schema(description = "1 有效 / 0 已被推翻")
    private Integer valid;
}
