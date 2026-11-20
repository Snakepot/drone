package cn.tedu.drone.task.pojo;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 监控看板上的一个「位置点」：当前位置与最近航迹都用它。
 *
 * <h2>它同时是 DAO 的查询结果和接口出口</h2>
 * 与 {@code TaskTrackPoint} 同一个理由：字段全是展示用的（坐标 + 时刻 + 属于哪条任务），
 * 没有任何表内部结构可泄漏，拆成两个类只是把同样几个字段写两遍。
 *
 * <p>{@code taskId} 只在 DAO → Service 这一段有意义（用来把多个任务的结果分组），
 * 到 VO 里其实已经不需要了 —— 留着是因为「一组点属于哪条任务」这件事
 * 在日志与排查时有用，去掉反而要多写一层映射。
 *
 * <p>坐标：从库里查出来时是 <b>WGS84</b>，进 {@link TaskMonitorVO} 之前已经转成
 * <b>GCJ-02</b>。和轨迹那边一样，别拿它和库里的值直接比。
 */
@Data
@Accessors(chain = true)
@Schema(description = "看板上的位置点（GCJ-02）")
public class TaskLivePointVO implements Serializable {

    @Schema(description = "所属任务 id（分组用）")
    private Long taskId;

    @Schema(description = "经度")
    private Double lng;

    @Schema(description = "纬度")
    private Double lat;

    @Schema(description = "这个点的时刻（设备报的时刻）")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime observedAt;
}
