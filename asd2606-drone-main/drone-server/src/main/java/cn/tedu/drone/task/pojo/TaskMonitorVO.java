package cn.tedu.drone.task.pojo;

import cn.tedu.drone.route.pojo.RouteVO;
import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 监控看板上的一条：一条执行中任务 + 它现在在哪、刚才怎么飞的、打算怎么飞。
 *
 * <h2>为什么这些字段凑在一个 VO 里，而不是让前端拼几个接口</h2>
 * 看板上每一行都要同时回答四个问题，而且**要一起刷新**：
 * <ol>
 *   <li>谁在飞（任务、飞手、设备）—— 来自 task 表</li>
 *   <li>现在在哪 —— 来自证据表里最新一条带坐标的遥测</li>
 *   <li>刚才怎么飞的（一小段航迹）—— 同样来自证据表</li>
 *   <li>打算怎么飞（计划航线 + 起降点）—— 来自任务上的快照</li>
 * </ol>
 * 拆成四个接口、让前端自己按 taskId 去对，就会出现「这一行的飞机位置是 3 秒前、
 * 航线是 10 秒前」这种各行其是的情况；而且 N 条任务就是 4N 次请求。
 * 一个接口一次给全，前端只管画。
 *
 * <h2>这里没有「完整轨迹」</h2>
 * 完整轨迹可能有几千个点，乘上任务数会把响应撑爆。看板只给<b>最近一小段</b>
 * （默认最近 300 条原始点抽稀后取尾部），点开某一条任务时再走
 * {@code GET /task/{id}/track} 拿完整的 —— 那是「聚焦一条」时才需要的。
 */
@Data
@Accessors(chain = true)
@Schema(description = "监控看板上的一条（坐标已是 GCJ-02）")
public class TaskMonitorVO implements Serializable {

    /** 固定值 {@code gcj02}，告诉调用方这批坐标是哪种 */
    public static final String GCJ02 = "gcj02";

    @Schema(description = "坐标系，固定 gcj02")
    private String coordinateSystem = GCJ02;

    // ---------- 一、谁在飞 ----------

    @Schema(description = "任务 id")
    private Long taskId;

    @Schema(description = "任务编号")
    private String taskNo;

    @Schema(description = "任务名称")
    private String taskName;

    @Schema(description = "优先级")
    private String priority;

    @Schema(description = "飞手姓名")
    private String pilotName;

    @Schema(description = "设备编号")
    private String droneCode;

    @Schema(description = "实际开始时间")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime actualStartTime;

    @Schema(description = "计划结束时间")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime planEndTime;

    /**
     * 是否已经超过计划结束时间还在执行中。
     *
     * <p>由服务端算：判断「超没超时」要和谁的表对时？——当然是我们自己的。
     * 让前端拿本机时间去比，客户端时钟偏几分钟就会一会儿红一会儿不红。
     */
    @Schema(description = "是否超期（执行中且已过计划结束时间）")
    private Boolean overdue;

    /**
     * 已超期多久（分钟）；没超期时为 null。
     *
     * <p>光说「超时」看不出严重程度 —— 超 5 分钟和超 2 小时是完全不同的两件事。
     */
    @Schema(description = "超期时长（分钟），未超期为 null")
    private Long overdueMinutes;

    // ---------- 二、告警 ----------

    /** 未处理的告警数（口径与飞手端角标一致：pending + handling） */
    @Schema(description = "未处理告警数")
    private Integer alertCount;

    /** 其中 critical 的条数 —— 看板靠它决定这一行要不要标红 */
    @Schema(description = "未处理告警中 critical 的条数")
    private Integer criticalAlertCount;

    // ---------- 三、现在在哪、刚才怎么飞的 ----------

    /**
     * 当前位置：**最新一条带坐标的遥测**。
     *
     * <p>为空表示这条任务还没有任何带坐标的数据（设备没上报、或报的报文里没有经纬度）——
     * 界面上要显示成「暂无位置」而不是画在 (0,0)。
     */
    @Schema(description = "当前位置，没有时为 null")
    private TaskLivePointVO current;

    /** 最近一小段航迹（已抽稀、按时间升序）；可能为空 */
    @Schema(description = "最近航迹（已抽稀）")
    private List<TaskLivePointVO> trail;

    // ---------- 四、打算怎么飞 ----------

    /** 当时的计划航线（任务上的快照，不是当前模板） */
    @Schema(description = "计划航线（快照）")
    private RouteVO plannedRoute;

    @Schema(description = "起降点经度（GCJ-02）")
    private Double takeoffLng;

    @Schema(description = "起降点纬度（GCJ-02）")
    private Double takeoffLat;

    @Schema(description = "起降点名称")
    private String takeoffName;
}
