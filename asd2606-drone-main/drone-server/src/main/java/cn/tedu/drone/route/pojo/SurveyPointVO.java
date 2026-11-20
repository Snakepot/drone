package cn.tedu.drone.route.pojo;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 点位视图对象。
 *
 * <h2>这里的坐标是 GCJ-02，不是库里的 WGS84</h2>
 * 与 {@code TaskTrackVO} 同一个约定：<b>给地图用的 VO 一律转成 GCJ-02，
 * 并在报文里写清是哪一种</b>（{@link #coordinateSystem}）。
 * 理由是两件事只能有一处实现（见 {@code TaskTrackVO} 的说明），
 * 而这里比轨迹多一条更硬的理由：<b>这个 VO 会被送进编辑表单再存回来</b>。
 * 表单里回显什么坐标系，保存时就按什么坐标系转回去 ——
 * 中间若有一次「你以为我给了 WGS84、我以为你收到的是 GCJ-02」，
 * 结果就是点位每次编辑漂几百米，且看不出来。
 *
 * <p>所以：<b>读出来的和写回去的，都是 GCJ-02。</b>接口两端一致，就不会错。
 */
@Data
@Accessors(chain = true)
@Schema(description = "点位（坐标已是 GCJ-02）")
public class SurveyPointVO implements Serializable {

    /** 固定值 {@code gcj02}，告诉调用方这批坐标是哪种 */
    public static final String GCJ02 = "gcj02";

    @Schema(description = "坐标系，固定 gcj02（给高德/腾讯直接画）")
    private String coordinateSystem = GCJ02;

    @Schema(description = "主键")
    private Long id;

    @Schema(description = "点位编号（客户给的，可空）")
    private String code;

    @Schema(description = "点位名称")
    private String name;

    @Schema(description = "经度（GCJ-02）")
    private Double lng;

    @Schema(description = "纬度（GCJ-02）")
    private Double lat;

    @Schema(description = "建议作业高度（米，相对起飞点）")
    private Double alt;

    @Schema(description = "类型编码 survey / takeoff / dock / other")
    private String category;

    /**
     * 类型中文名，由后端给出。
     *
     * <p>前端不再维护一份「survey → 巡检点」的映射：那 4 条映射要在
     * 筛选下拉、地图图例、表格标签三处用到，抄三份比抄一份更容易漂。
     */
    @Schema(description = "类型中文名")
    private String categoryLabel;

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
