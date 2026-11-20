package cn.tedu.drone.zone.pojo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 禁飞区，对应 {@code no_fly_zone} 表。
 *
 * <p>{@code geometry} 在这里是**原始 JSON 字符串**，PO 不解析它 ——
 * 解析/序列化都收在 Service 里（形状决定格式，见 {@link ZoneShape}）。
 * 这样「库里这一列长什么样」只在两个地方出现：写它的地方、读它的地方，
 * 中间任何一层都不会顺手把它当结构用。
 *
 * <p>坐标是 <b>WGS84</b>（几何里的经纬度也是）。
 */
@Data
@Accessors(chain = true)
@Schema(description = "禁飞区")
public class NoFlyZonePO implements Serializable {

    @Schema(description = "主键")
    private Long id;

    @Schema(description = "区域名称")
    private String name;

    @Schema(description = "来源 official 官方空域 / custom 自建")
    private String source;

    @Schema(description = "形状 polygon 多边形 / circle 圆")
    private String shape;

    /** WGS84 的几何原文；polygon 是顶点数组，circle 是圆心半径 */
    @Schema(description = "几何（json 原文，WGS84）")
    private String geometry;

    /**
     * 区域内限高（米）。
     *
     * <p><b>NULL 和 0 是两件完全不同的事</b>：
     * NULL 表示「完全禁飞」，任何点进来都算命中；
     * 填了数字才是「限高区」，只拦高度已知且超过它的点。
     * 所以这一列不能图省事用 0 表示「不限」—— 那会变成「限高 0 米」。
     */
    @Schema(description = "区域内限高（米）；NULL 表示完全禁飞")
    private Integer maxAlt;

    @Schema(description = "生效开始，NULL 表示长期")
    private LocalDateTime effectiveFrom;

    @Schema(description = "生效结束，NULL 表示长期")
    private LocalDateTime effectiveTo;

    @Schema(description = "1 启用 / 0 停用")
    private Integer enabled;

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
