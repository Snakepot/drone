package cn.tedu.drone.task.pojo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 到点打卡，对应 {@code task_checkin} 表。
 *
 * <p>坐标全是 <b>WGS84</b>（库里那一套）。飞手手机上拿到的是 GCJ-02，
 * 接口层转过来/转回去，DAO 这一层不认坐标系。
 */
@Data
@Accessors(chain = true)
@Schema(description = "到点打卡")
public class TaskCheckinPO implements Serializable {

    @Schema(description = "主键")
    private Long id;

    @Schema(description = "所属任务")
    private Long taskId;

    @Schema(description = "目标航点序号（从 1 开始）")
    private Integer seq;

    @Schema(description = "目标点位经度 WGS84")
    private Double targetLng;

    @Schema(description = "目标点位纬度 WGS84")
    private Double targetLat;

    @Schema(description = "打卡位置经度 WGS84")
    private Double lng;

    @Schema(description = "打卡位置纬度 WGS84")
    private Double lat;

    /** 手机定位精度（米）：越大越不可信，展示时要带上 —— 5 米和 500 米是两回事 */
    @Schema(description = "手机定位精度（米）")
    private BigDecimal accuracyM;

    /** 离目标点位的距离（米，**服务端算的**：客户端传的数只用于「提示他确认一下」） */
    @Schema(description = "离目标点位的距离（米，服务端算）")
    private Integer deviationM;

    @Schema(description = "打卡那一刻（客户端给的，补传时就是当时那一刻）")
    private LocalDateTime observedAt;

    @Schema(description = "我们收到它的时刻")
    private LocalDateTime reportedAt;

    @Schema(description = "打卡人 ID")
    private Long reporterId;

    @Schema(description = "打卡人姓名")
    private String reporterName;

    @Schema(description = "飞手填的说明")
    private String remark;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;
}
