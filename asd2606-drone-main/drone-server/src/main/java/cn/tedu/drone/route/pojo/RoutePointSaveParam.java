package cn.tedu.drone.route.pojo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * 航点保存参数。
 *
 * <p>刻意没有 {@code seq}：顺序由数组下标决定（见 {@link RouteSaveParam}）。
 */
@Data
@Accessors(chain = true)
@Schema(description = "航点保存参数（坐标须为 GCJ-02）")
public class RoutePointSaveParam implements Serializable {

    @Schema(description = "经度（GCJ-02），必填")
    private Double lng;

    @Schema(description = "纬度（GCJ-02），必填")
    private Double lat;

    @Schema(description = "作业高度（米，相对起飞点），可空")
    private Double alt;

    @Schema(description = "动作 none / photo / hover，不传按 none")
    private String action;

    @Schema(description = "悬停秒数，action=hover 时必填且需大于 0")
    private Integer hoverSeconds;

    /**
     * 来源点位 id，可空。
     *
     * <p>服务端会校验这个 id 是否真的存在 —— 不校验的话，
     * 库里会出现「来源点位 999」这种点不开的引用，而界面上只会显示「没有来源」，
     * 谁都发现不了。
     */
    @Schema(description = "来源点位 id，可空（临时点不填）")
    private Long pointId;
}
