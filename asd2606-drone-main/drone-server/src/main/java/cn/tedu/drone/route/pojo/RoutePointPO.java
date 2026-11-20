package cn.tedu.drone.route.pojo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * 航线上的一个点，对应 {@code route_point} 表。
 *
 * <h2>为什么它自带 lng / lat，而不是只存一个 point_id</h2>
 * 航线上的点是<b>从点位库复制过来的</b>（{@code point_id} 只是记着「当初从哪来」）。
 * 这么设计有两个实在的好处：
 * <ol>
 *   <li><b>航线是独立的一份数据</b>。点位库清掉一个点，历史航线不会跟着塌 ——
 *       它能表达的是「点被我删了」，而不是「航线坏了」</li>
 *   <li><b>可以放点位库之外的临时点</b>。管理员在地图上直接点的位置不一定要入库，
 *       而航线必须存得下它</li>
 * </ol>
 * 代价是同一个位置存了两份（点位库一份、每条用到它的航线各一份），
 * 改了点位库不会自动更新航线 —— <b>这正是想要的</b>：
 * 航线改了要有人负责，不能因为别人整理点位库就悄悄变了形状。
 */
@Data
@Accessors(chain = true)
@Schema(description = "航点")
public class RoutePointPO implements Serializable {

    @Schema(description = "主键")
    private Long id;

    @Schema(description = "所属航线 id")
    private Long routeId;

    /**
     * 顺序，从 1 开始。
     *
     * <p><b>由服务端按数组顺序生成，不接受前端传</b>：前端传的话就得处理
     * 「两个点都是 3」「跳过了 2」这些情况，而数组本身的顺序已经是完整的答案了。
     */
    @Schema(description = "顺序，从 1 开始")
    private Integer seq;

    @Schema(description = "经度 WGS84")
    private Double lng;

    @Schema(description = "纬度 WGS84")
    private Double lat;

    @Schema(description = "作业高度（米，相对起飞点）")
    private Double alt;

    @Schema(description = "动作 none 飞过 / photo 拍照 / hover 悬停")
    private String action;

    @Schema(description = "悬停秒数（action=hover 时有值）")
    private Integer hoverSeconds;

    /** 来自点位库时记下它的 id；地图上临时加的点为 null */
    @Schema(description = "来源点位 id，可空")
    private Long pointId;
}
