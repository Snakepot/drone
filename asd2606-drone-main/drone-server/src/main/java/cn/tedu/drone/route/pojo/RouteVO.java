package cn.tedu.drone.route.pojo;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 航线视图对象。
 *
 * <h2>坐标又是 GCJ-02（和点位那边同一个理由）</h2>
 * 给地图用的东西一律转好再给，并在报文里写清（{@link #coordinateSystem}）。
 *
 * <h2>列表接口不带 points</h2>
 * {@link #points} 只在详情接口里有值。列表一次返回 10 条航线，
 * 若每条都带上它的点，就是 10 次额外查询（N+1）；
 * 而列表上要显示的「6 个点 / 3.2 公里」已经由 {@code pointCount} /
 * {@code distanceM} 两个冗余列回答了 —— 不需要点本身。
 */
@Data
@Accessors(chain = true)
@Schema(description = "航线（坐标已是 GCJ-02）")
public class RouteVO implements Serializable {

    /** 固定值 {@code gcj02}，告诉调用方这批坐标是哪种 */
    public static final String GCJ02 = "gcj02";

    @Schema(description = "坐标系，固定 gcj02（给高德/腾讯直接画）")
    private String coordinateSystem = GCJ02;

    @Schema(description = "主键")
    private Long id;

    @Schema(description = "航线名称")
    private String name;

    @Schema(description = "说明")
    private String description;

    @Schema(description = "航点数")
    private Integer pointCount;

    @Schema(description = "总航程（米）")
    private Integer distanceM;

    @Schema(description = "创建人姓名")
    private String createByName;

    @Schema(description = "创建时间")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;

    @Schema(description = "最后更新时间")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updateTime;

    /** 航点，已按 seq 升序；只有详情接口会填 */
    @Schema(description = "航点（仅详情接口返回）")
    private List<RoutePointVO> points;
}
