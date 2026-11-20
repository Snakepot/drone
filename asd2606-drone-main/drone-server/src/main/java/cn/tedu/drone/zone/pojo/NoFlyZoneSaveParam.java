package cn.tedu.drone.zone.pojo;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 禁飞区新增 / 编辑参数。
 *
 * <h2>⚠️ 坐标传 GCJ-02</h2>
 * 管理员是在高德的地图上画的，拿去的就是 GCJ-02；与读接口
 * （{@link NoFlyZoneVO}）声明的一致，两边对齐才不会错。
 * 「GCJ-02 → WGS84」的换算在 Service 里做，<b>入库的永远是 WGS84</b>。
 *
 * <h2>两种形状用不同的字段</h2>
 * 多边形填 {@link #points}，圆填 {@link #centerLng} / {@link #centerLat} / {@link #radius}，
 * 由 {@link #shape} 决定该填哪一组；填错那组会被明确拒掉
 * （而不是「静默忽略了多余字段」，那样会让人以为存进去了）。
 */
@Data
@Accessors(chain = true)
@Schema(description = "禁飞区保存参数（坐标须为 GCJ-02）")
public class NoFlyZoneSaveParam implements Serializable {

    /** 编辑时必填；为空表示新增 */
    @Schema(description = "主键，编辑时必填")
    private Long id;

    @Schema(description = "区域名称，必填")
    private String name;

    @Schema(description = "来源 official 官方空域 / custom 自建，不传按 custom")
    private String source;

    @Schema(description = "形状 polygon 多边形 / circle 圆，不传按 polygon")
    private String shape;

    /** shape=polygon 时必填：至少 3 个顶点（GCJ-02） */
    @Schema(description = "多边形顶点（GCJ-02），至少 3 个")
    private List<ZonePointVO> points;

    @Schema(description = "圆心经度（GCJ-02），shape=circle 时必填")
    private Double centerLng;

    @Schema(description = "圆心纬度（GCJ-02），shape=circle 时必填")
    private Double centerLat;

    @Schema(description = "半径（米），shape=circle 时必填且需大于 0")
    private Double radius;

    /**
     * 区域内限高（米）。不填 = 完全禁飞。
     *
     * <p>⚠️ <b>不能传 0 表示「不限」</b> —— 0 会被存成「限高 0 米」，
     * 那是个比完全禁飞还严的规则。要表达「不限」就别传这个字段。
     */
    @Schema(description = "区域内限高（米），不填表示完全禁飞")
    private Integer maxAlt;

    @Schema(description = "生效开始，不填表示长期有效")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime effectiveFrom;

    @Schema(description = "生效结束，不填表示长期有效")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime effectiveTo;

    @Schema(description = "是否启用，不传按启用")
    private Boolean enabled;

    @Schema(description = "备注")
    private String remark;
}
