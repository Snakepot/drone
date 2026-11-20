package cn.tedu.drone.task.pojo;

import cn.tedu.drone.route.pojo.RouteVO;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.util.List;

/**
 * 任务详情 = 任务本体 + 状态流转流水 + 期间的告警。
 *
 * <p>把流水和告警一起返回，是因为「这条任务是怎么走到今天的」「当时出了什么事」
 * 和任务本身同样重要 —— 详情页的时间轴用流水渲染，告警区块用告警渲染。
 * 一次请求给全，页面不用来回跳（也就不用为了看告警先切到告警中心去筛）。
 */
@Data
@Accessors(chain = true)
@Schema(description = "任务详情")
public class TaskDetailVO implements Serializable {

    @Schema(description = "任务本体")
    private TaskVO task;

    @Schema(description = "状态流转流水，按时间正序")
    private List<TaskLogVO> logs;

    /**
     * 本次任务期间的告警（按 {@code task_id} 或「设备 + 任务时间窗」聚合）。
     *
     * <p>为什么要两边查：设备上报那一刻如果没有执行中的任务，
     * 落库时 {@code task_id} 是空的；事后看这条任务时，那些「当时没主」的告警
     * 其实和它有关。只按 {@code task_id} 查会漏掉它们 ——
     * 详见 {@code AlertDao.selectInTaskWindow} 的注释。
     *
     * <p>它和下面的流水一起构成详情页的时间线：
     * 流水记「任务走到了哪一步」，告警记「当时出了什么事」。
     */
    private List<AlertVO> alerts;

    /**
     * 这条任务<b>当时</b>的计划航线（任务上的快照，坐标已转成 GCJ-02）。
     *
     * <p>它和 {@code TaskTrackVO}（实际飞出来的轨迹）是一对：
     * 详情页把两条线叠在同一张地图上 —— <b>计划画虚线、实际画实线</b>，
     * 一眼能看出「哪没飞到、哪飞偏了」。这是这一整块地图功能最先交付的价值。
     *
     * <p>为空有两种情况，界面都按「这条任务没有计划航线」处理：
     * 创建时没选航线（临时任务直接填了一句文字），或者快照读不出来
     * （见 {@code RouteService#fromSnapshot}）。
     */
    private RouteVO plannedRoute;

    /**
     * 这条任务上的**现场打卡**记录（飞手在各航点打的卡），按航点序号升序。
     *
     * <p>放在详情里，是因为它是「飞手说自己去过哪」的唯一记录 ——
     * 事后复核时，它要和设备轨迹放在一起看：
     * 打卡点在轨迹附近 → 对得上；打卡点与轨迹差几十公里 → 要问一句。
     * <b>但它是自述，不能当设备证据用</b>（见设计文档 7.2）。
     */
    private List<TaskCheckinVO> checkins;
}
