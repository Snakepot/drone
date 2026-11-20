package cn.tedu.drone.task.pojo;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 到点打卡的入参。
 *
 * <h2>坐标默认是 GCJ-02，也可以显式声明成 WGS84</h2>
 * 手机定位默认给的就是 GCJ-02（`uni.getLocation({type:'gcj02'})`），
 * 原样传上来即可 —— 与读接口（{@link TaskCheckinVO}）声明的一致。
 * 「GCJ-02 → WGS84」的换算在 Service 里做，入库的永远是 WGS84。
 *
 * <p>{@link #coordinateSystem} 是为了**另一种来源**：浏览器/浏览器的定位
 * （`navigator.geolocation`）给的是 GPS 原始坐标 WGS84，没有 GCJ-02 可用
 * （H5 预览就是这种情况）。让调用方**声明**手里是哪一套，比让服务端去猜安全得多 ——
 * 猜错的表现是位置偏 500 米，而它看起来「差不多对」。
 *
 * <h2>为什么没有「偏差」这个字段</h2>
 * 偏差必须由服务端拿目标点与打卡点自己算。客户端算的那个数只能用来
 * <b>提示用户确认</b>（「离目标 230 米，确定到了吗」），绝不能落库 ——
 * 落库了就等于「谁来打卡都能自己填一个 0 米」。
 */
@Data
@Accessors(chain = true)
@Schema(description = "到点打卡入参（坐标须为 GCJ-02）")
public class TaskCheckinParam implements Serializable {

    @Schema(description = "任务 id")
    private Long taskId;

    /**
     * 目标航点序号，对应航线快照里第几个点（从 1 开始）。
     *
     * <p>为什么不传坐标：传坐标就等于让客户端决定「目标在哪」，
     * 那他可以对着自己脚下的位置传一个目标点，偏差永远是 0。
     * 目标点只能由服务端从**任务自己的航线快照**里取。
     */
    @Schema(description = "目标航点序号（从 1 开始，见航线快照）")
    private Integer seq;

    @Schema(description = "打卡位置经度（GCJ-02）")
    private Double lng;

    @Schema(description = "打卡位置纬度（GCJ-02）")
    private Double lat;

    /**
     * 打卡那一刻。
     *
     * <p>客户端给而不是服务端取「现在」：离线时打卡先存本地，
     * 联网补传时这一条代表的仍是<b>当时那一刻</b>。
     * 它也参与补传去重（任务 + 航点 + 时刻），所以缺了就没法去重 —— 必填。
     */
    @Schema(description = "打卡时刻（yyyy-MM-dd HH:mm:ss）")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime observedAt;

    @Schema(description = "手机定位精度（米），可空")
    private BigDecimal accuracyM;

    @Schema(description = "说明，可空（如「点位被树挡住，在边上拍的」）")
    private String remark;

    /**
     * 坐标是哪种：{@code gcj02}（默认，手机小程序定位）或 {@code wgs84}（浏览器 GPS）。
     *
     * <p>不传按 {@code gcj02} —— 这是飞手端最常见、也是唯一有地图配套的那一种。
     * 传了别的取值直接拒（白名单），不做「猜一个最像的」。
     */
    @Schema(description = "上传坐标的坐标系：gcj02（默认）/ wgs84")
    private String coordinateSystem;

    /**
     * 只算不落库（「试算」）。
     *
     * <p>给客户端「提交前问一句」用：算出偏差、告诉它远不远，但不写记录。
     * 为什么不让客户端自己算：它手里的坐标（浏览器 GPS 是 WGS84、手机小程序是 GCJ-02）
     * 和航线快照不一定是同一套，自己算会凭空多出 500 米 —— 这正是写这一版时踩到的坑。
     * 距离只有一处实现，就在服务端。
     */
    @Schema(description = "只试算不落库；用于提交前提示")
    private Boolean dryRun;

    /** 允许的取值。就这两个，不做通用坐标系词汇表 —— 多一个是将来真要用时再加 */
    public static final String GCJ02 = "gcj02";
    public static final String WGS84 = "wgs84";
    public static final List<String> COORDINATE_SYSTEMS = List.of(GCJ02, WGS84);

    /** 取本次声明的坐标系；没传或传空按 gcj02 */
    public String resolvedCoordinateSystem() {
        return (coordinateSystem == null || coordinateSystem.isBlank()) ? GCJ02 : coordinateSystem.trim();
    }
}
