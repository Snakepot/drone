package cn.tedu.drone.analytics.pojo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.util.List;

/**
 * 点位覆盖率（见 {@code docs/业务分析方案.md} 2.5 与第四步）。
 *
 * <h2>它回答客户最常问的那句话</h2>
 * 「这个月，该巡的点到底去了没有？」—— 所以输出不是一句「覆盖率 87%」，
 * 而是**按点位的清单**：哪些点被计划过、去了几次、哪些点一次都没去。
 *
 * <h2>「到过」凭什么算</h2>
 * 用<b>打卡</b>（{@code task_checkin}）：打卡本来就是「到点取证」这个动作，
 * 而且它带目标坐标与偏差（{@link #radiusMeters} 以内才算到过）。
 * 拿轨迹反推「点到轨迹的距离」要几何计算 + 扫证据表，那是**单任务下钻**时才做的事
 * （一条任务的轨迹是几百个点，算得起；一个月所有任务一起算就是另一回事了）——
 * 这一点写在文档里，不让它变成一个没人知道为什么没做的缺口。
 *
 * <h2>为什么还有「没有航线的任务」这个数</h2>
 * 没配航线的任务没有计划点，压根谈不上覆盖 —— 把它算进分母会把覆盖率压低，
 * 而它的问题不在「没覆盖」而在「没排路线」。所以单独给一个数，让它可被看见。
 */
@Data
@Accessors(chain = true)
@Schema(description = "点位覆盖率")
public class CoverageVO implements Serializable {

    @Schema(description = "区间起点（含）")
    private String from;

    @Schema(description = "区间终点（不含）")
    private String to;

    /** 坐标一律 GCJ-02（与点位库、航线、监控看板同一条约定，可直接画在地图上） */
    @Schema(description = "固定 gcj02")
    private String coordinateSystem;

    @Schema(description = "判定「到过」的半径（米）—— 打卡偏差小于它才算")
    private Integer radiusMeters;

    @Schema(description = "区间内已结束的任务数")
    private Integer taskCount;

    @Schema(description = "其中没配航线、因此没有计划点的任务数")
    private Integer tasksWithoutRoute;

    @Schema(description = "计划航点总数（各任务去重后累加）")
    private Integer plannedPoints;

    @Schema(description = "其中到过的")
    private Integer visitedPoints;

    @Schema(description = "覆盖率 = 到过 ÷ 计划，%；没有计划点时为 null")
    private Double coverageRate;

    @Schema(description = "按航线汇总（同一航线被多条任务用过时合并）")
    private List<RouteCoverage> routes;

    /**
     * 按点位的明细 —— 这一屏真正的产出。
     *
     * <p>只列**被计划过**的点：点位库里有、但这个月没排到它，不属于「本月的覆盖率」问题。
     */
    @Schema(description = "按点位的覆盖明细")
    private List<PointCoverage> points;

    /** 一条航线的覆盖情况 */
    @Data
    @Accessors(chain = true)
    @Schema(description = "航线的覆盖情况")
    public static class RouteCoverage implements Serializable {

        @Schema(description = "航线名称（取自任务快照，航线改名不影响历史口径）")
        private String name;

        @Schema(description = "计划点（去重）")
        private Integer plannedPoints;

        @Schema(description = "到过的点（去重）")
        private Integer visitedPoints;

        @Schema(description = "覆盖率，%")
        private Double coverageRate;

        @Schema(description = "这段时间里用过这条航线的任务数")
        private Integer taskCount;
    }

    /** 一个点位的覆盖情况 */
    @Data
    @Accessors(chain = true)
    @Schema(description = "点位的覆盖情况")
    public static class PointCoverage implements Serializable {

        @Schema(description = "点位 id；快照里的旧点位已被删除时为 null")
        private Long pointId;

        @Schema(description = "点位编号（客户给的，可能为空）")
        private String code;

        @Schema(description = "点位名称；查不到就退回「航点 #序号」")
        private String name;

        @Schema(description = "经度（GCJ-02）")
        private Double lng;

        @Schema(description = "纬度（GCJ-02）")
        private Double lat;

        @Schema(description = "被计划了几次（同一航线被多条任务用过会累加）")
        private Integer plannedTimes;

        @Schema(description = "到过几次")
        private Integer visitedTimes;

        @Schema(description = "这段时间里到过没有 —— 表格里按它排序与标色")
        private Boolean covered;
    }
}
