package cn.tedu.drone.route.pojo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 点位（点位库），对应 {@code survey_point} 表。
 *
 * <h2>坐标是 WGS84</h2>
 * 和 {@code task_evidence} 里的遥测、{@code route_point} 一样，这一列存的是
 * <b>设备/GPS 的原始坐标系</b>。人在地图上点的位置是 GCJ-02，
 * 入库前由 Service 转过来（{@code CoordinateUtil.gcj02ToWgs84}）——
 * <b>DAO 这一层不管坐标系</b>，它拿到的永远是库里那一种。
 */
@Data
@Accessors(chain = true)
@Schema(description = "点位")
public class SurveyPointPO implements Serializable {

    @Schema(description = "主键")
    private Long id;

    /**
     * 点位编号，客户可能给（如「T-012」），<b>可空</b>。
     *
     * <p>唯一索引对多个 NULL 是「各不相同」的，所以不填编号的点位可以有很多个 ——
     * 这个性质在 {@code task_alert.device_code} 那里也用到了（见告警设计文档 4.4）。
     * 不能用空字符串代替 NULL：空串只会有一个。
     */
    @Schema(description = "点位编号（客户给的，可空）")
    private String code;

    @Schema(description = "点位名称")
    private String name;

    @Schema(description = "经度 WGS84")
    private Double lng;

    @Schema(description = "纬度 WGS84")
    private Double lat;

    @Schema(description = "建议作业高度（米，相对起飞点）")
    private Double alt;

    @Schema(description = "类型 survey 巡检 / takeoff 起降 / dock 机巢 / other 其他")
    private String category;

    @Schema(description = "备注")
    private String remark;

    @Schema(description = "创建人 ID")
    private Long createBy;

    @Schema(description = "创建人姓名")
    private String createByName;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    @Schema(description = "最后更新时间")
    private LocalDateTime updateTime;
}
