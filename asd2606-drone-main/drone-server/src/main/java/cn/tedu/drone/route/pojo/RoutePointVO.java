package cn.tedu.drone.route.pojo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * 航点视图对象。
 *
 * <p>比 {@link RoutePointPO} 多了两个<b>给人看</b>的字段：
 * {@code actionLabel}（动作中文名）与 {@code pointName}（来源点位的名称）。
 * 「这个航点是从哪个点位挑来的」在界面上很有用 ——
 * 排航线的人认得「T-012 东塔」，不认得 {@code 116.4123, 39.9165}。
 */
@Data
@Accessors(chain = true)
@Schema(description = "航点（坐标已是 GCJ-02）")
public class RoutePointVO implements Serializable {

    @Schema(description = "顺序，从 1 开始")
    private Integer seq;

    @Schema(description = "经度（GCJ-02）")
    private Double lng;

    @Schema(description = "纬度（GCJ-02）")
    private Double lat;

    @Schema(description = "作业高度（米，相对起飞点）")
    private Double alt;

    @Schema(description = "动作编码 none / photo / hover")
    private String action;

    @Schema(description = "动作中文名")
    private String actionLabel;

    @Schema(description = "悬停秒数（action=hover 时有值）")
    private Integer hoverSeconds;

    /** 来自点位库时记下它的 id；地图上临时加的点为 null */
    @Schema(description = "来源点位 id，可空")
    private Long pointId;

    /** 来源点位名称；没有来源点时为空 */
    @Schema(description = "来源点位名称，可空")
    private String pointName;
}
