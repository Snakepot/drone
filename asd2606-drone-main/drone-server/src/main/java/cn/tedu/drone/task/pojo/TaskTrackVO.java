package cn.tedu.drone.task.pojo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.util.List;

/**
 * 任务轨迹（给地图直接用的那种）。
 *
 * <h2>这个 VO 有一个刻意的设计：把坐标系写在报文里</h2>
 * {@code points} 里的坐标**已经转成 GCJ-02**（高德/腾讯那一套），
 * 前端可以直接交给地图去画，不用再转一次。
 *
 * <p>但「同一个字段名，在不同接口里是不同坐标系」是个**很容易踩的坑** ——
 * 别人（或三个月后的自己）看到 {@code lng/lat}，会以为和别处一样是 WGS84，
 * 拿去和库里的值一比就错了。所以这里显式带一个 {@link #coordinateSystem}，
 * 让接口契约自己说清楚。
 *
 * <h2>为什么不返回 WGS84 让前端自己转</h2>
 * 两个原因：
 * <ol>
 *   <li><b>转换只能有一处实现</b>。管理端与飞手端都要画地图，
 *       放前端就是两份实现（两端是不同的工程，共享不了代码）</li>
 *   <li><b>可测性</b>。后端有测试框架，能拿公开实现的参考值钉住转换结果；
 *       前端那边的转换函数没有测试可跑，错了只能靠肉眼看图发现 ——
 *       而这个问题恰恰「看起来差不多对」</li>
 * </ol>
 * 这一条与设计文档 2.3 节「转换只在显示层做」不冲突：
 * 这个接口**就是**显示层的一部分（它只服务地图），
 * 而不是通用的数据接口。
 */
@Data
@Accessors(chain = true)
@Schema(description = "任务轨迹（坐标已是 GCJ-02）")
public class TaskTrackVO implements Serializable {

    /** 固定值 {@code gcj02}，告诉调用方这批坐标是哪种 */
    public static final String GCJ02 = "gcj02";

    @Schema(description = "坐标系，固定 gcj02（给高德/腾讯直接画）")
    private String coordinateSystem = GCJ02;

    @Schema(description = "原始点位数（抽稀前）；和 points 的长度对比能看出抽掉了多少")
    private Integer rawCount;

    /**
     * 抽稀之后的轨迹点，**已按时间升序**。
     *
     * <p>为什么要抽稀：一条 2 小时的任务有 7000 多个点，全画上去浏览器会卡。
     * 抽稀的规则见 {@code TrackSampler}。
     */
    @Schema(description = "轨迹点（已抽稀、按时间升序）")
    private List<TaskTrackPoint> points;
}
