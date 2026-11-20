package cn.tedu.drone.route.pojo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.util.List;

/**
 * 航线快照 —— 存进 {@code task.route_snapshot}（json 列）的那份东西。
 *
 * <h2>它存在的唯一理由：模板会变，历史不能跟着变</h2>
 * 任务引用的是「东线巡检」这条模板。半年后有人把第 3 个点往东挪了 200 米，
 * 于是所有引用过它的历史任务，看上去「当时飞的就是挪过之后的那条线」——
 * 事后查不出真相，这是事故级的问题（见设计文档 6.5）。
 *
 * <p>所以创建任务时把当时那份航线<b>整段复制</b>下来。之后模板怎么改，
 * 这条任务的地图上画的永远是它自己那份。
 *
 * <h2>⚠️ 快照里的坐标是 WGS84</h2>
 * 与库里其它坐标一致 —— 它是一个数据库列，不是给地图的报文。
 * 要画图时由 {@code RouteService.fromSnapshot} 转成 GCJ-02。
 *
 * <h2>为什么单独一个类，而不是写到哪算哪的 Map</h2>
 * 因为「写快照」和「读快照」是两处代码。用 Map 的话，字段名是两份字符串字面量，
 * 改了一处忘了另一处，结果是<b>解析出一堆 null</b> 而不报错
 * （多余字段被忽略、缺失字段留空）。用一个类，Jackson 两边共用同一份字段定义，
 * 漏改会在编译期就暴露。
 */
@Data
@Accessors(chain = true)
@Schema(description = "航线快照（坐标 WGS84，存库用）")
public class RouteSnapshot implements Serializable {

    @Schema(description = "来源航线模板 id（模板可能已被删除，仅作追溯线索）")
    private Long routeId;

    @Schema(description = "快照时的航线名称")
    private String name;

    @Schema(description = "航点数")
    private Integer pointCount;

    @Schema(description = "总航程（米）")
    private Integer distanceM;

    /**
     * 航点，按 seq 升序。
     *
     * <p>直接复用 {@link RoutePointPO}：它就是「库里的航点」这个形状，
     * 快照里的点与库里的点字段完全一样，再定义一个同构的类只会多一处要同步的地方。
     * 里面的 {@code id} / {@code routeId} 在快照里其实没有意义
     * （模板可能早就没了），留着是因为它们无害，而且去掉反而要多一个类。
     */
    @Schema(description = "航点（WGS84）")
    private List<RoutePointPO> points;
}
