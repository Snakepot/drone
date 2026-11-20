package cn.tedu.drone.zone.pojo;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 禁飞区视图对象。
 *
 * <h2>坐标又是 GCJ-02</h2>
 * 与点位库、航线同一个约定：<b>给地图用的 VO 一律转成 GCJ-02 并声明</b>
 * （{@link #coordinateSystem}），编辑表单读出来、存回去都是这一种，中间不会漂。
 *
 * <h2>两种形状各有各的字段</h2>
 * 多边形用 {@link #points}，圆用 {@link #center} + {@link #radius}，
 * 由 {@link #shape} 决定该看哪一组。刻意不塞进一个「万能 geometry 对象」：
 * 那样前端每次用它都要先判断一次形状，而判断写错了（比如圆的顶点是空数组）
 * 只会表现为「地图上什么都没画」，不报错。
 */
@Data
@Accessors(chain = true)
@Schema(description = "禁飞区（坐标已是 GCJ-02）")
public class NoFlyZoneVO implements Serializable {

    /** 固定值 {@code gcj02}，告诉调用方这批坐标是哪种 */
    public static final String GCJ02 = "gcj02";

    @Schema(description = "坐标系，固定 gcj02（给高德/腾讯直接画）")
    private String coordinateSystem = GCJ02;

    @Schema(description = "主键")
    private Long id;

    @Schema(description = "区域名称")
    private String name;

    @Schema(description = "来源编码 official / custom")
    private String source;

    @Schema(description = "来源中文名")
    private String sourceLabel;

    @Schema(description = "形状编码 polygon / circle")
    private String shape;

    @Schema(description = "形状中文名")
    private String shapeLabel;

    /** 多边形顶点（shape=polygon 时有值），按绘制顺序 */
    @Schema(description = "多边形顶点（GCJ-02）")
    private List<ZonePointVO> points;

    /** 圆心（shape=circle 时有值） */
    @Schema(description = "圆心（GCJ-02）")
    private ZonePointVO center;

    /** 半径（米，shape=circle 时有值） */
    @Schema(description = "半径（米）")
    private Double radius;

    /** 区域内限高（米）；为 null 表示完全禁飞 */
    @Schema(description = "区域内限高（米），null 表示完全禁飞")
    private Integer maxAlt;

    /**
     * 是不是「完全禁飞」（没有限高）。
     *
     * <p>它由 {@link #maxAlt} 推导，看着冗余，但界面靠它决定用红色还是琥珀色、
     * 文案说「禁飞」还是「限高」—— 让每个前端各自写一次
     * {@code maxAlt == null}，迟早会有一处写成 {@code maxAlt <= 0}。
     */
    @Schema(description = "是否完全禁飞（maxAlt 为 null）")
    private Boolean forbidden;

    /** 高度规则的一句话说明，如「完全禁飞」/「限高 120 米」 */
    @Schema(description = "高度规则说明")
    private String altRuleLabel;

    @Schema(description = "生效开始，null 表示长期")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime effectiveFrom;

    @Schema(description = "生效结束，null 表示长期")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime effectiveTo;

    @Schema(description = "是否启用")
    private Boolean enabled;

    @Schema(description = "备注")
    private String remark;

    @Schema(description = "创建人姓名")
    private String createByName;

    @Schema(description = "创建时间")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;

    @Schema(description = "最后更新时间")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updateTime;
}
