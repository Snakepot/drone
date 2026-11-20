package cn.tedu.drone.task.pojo;

import cn.tedu.drone.route.pojo.RouteVO;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.util.List;

/**
 * 飞手端「我这条任务的地图」：一次请求给全画图与打卡要用的东西。
 *
 * <h2>为什么合成一个接口</h2>
 * 飞手在野外点开一次，就要同时看到：计划航线、起降点、自己飞过的轨迹、
 * 哪些点已经打了卡。拆成四个请求，弱网下就是四次转圈；
 * 而且这些数据要一起刷新（打完卡进度就变了）——分接口必然出现
 * 「地图上是旧进度、列表里是新进度」。
 *
 * <h2>它和 {@code TaskDetailVO} 的区别</h2>
 * 详情那个是**管理侧**看的：带流转流水、期间告警、报告，权限是 {@code task:list}。
 * 这个是**飞手侧**看的：只有他执行时需要的东西，权限是 {@code task:execute}
 * 加上「这条任务是不是派给我的」那段校验。
 * 两者的数据来源有不少重叠，但受众不同 —— 给飞手看管理侧的详情，
 * 等于把「期间告警的处置记录、报告全文」这些不该他操心的东西一起塞给他。
 */
@Data
@Accessors(chain = true)
@Schema(description = "飞手端的任务地图数据（坐标已是 GCJ-02）")
public class TaskMapVO implements Serializable {

    /** 固定值 {@code gcj02}，告诉调用方这批坐标是哪种 */
    public static final String GCJ02 = "gcj02";

    @Schema(description = "坐标系，固定 gcj02")
    private String coordinateSystem = GCJ02;

    @Schema(description = "任务 id")
    private Long taskId;

    @Schema(description = "任务编号")
    private String taskNo;

    @Schema(description = "任务名称")
    private String taskName;

    @Schema(description = "状态")
    private String status;

    @Schema(description = "设备编号")
    private String droneCode;

    /** 当时的计划航线（任务上的快照，不是当前模板） */
    @Schema(description = "计划航线（快照）")
    private RouteVO plannedRoute;

    @Schema(description = "起降点经度（GCJ-02）")
    private Double takeoffLng;

    @Schema(description = "起降点纬度（GCJ-02）")
    private Double takeoffLat;

    @Schema(description = "起降点名称；一键导航用它当目的地的名字")
    private String takeoffName;

    /** 实际飞出来的轨迹（已抽稀）。飞手想「看看自己飞过哪」时用它 */
    @Schema(description = "实际轨迹（已抽稀）")
    private TaskTrackVO track;

    @Schema(description = "打卡记录，按航点序号升序")
    private List<TaskCheckinVO> checkins;

    /** 已经打过卡的**不同航点数**（同一个点打多次只算一个） */
    @Schema(description = "已打卡的航点数")
    private Integer checkedPointCount;

    /** 计划航点数（没有航线时为 0） */
    @Schema(description = "计划航点数")
    private Integer totalPointCount;
}
