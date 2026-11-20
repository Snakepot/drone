package cn.tedu.drone.task.track;

import cn.tedu.drone.common.GeoDistance;
import cn.tedu.drone.task.pojo.TaskTrackPoint;

import java.util.ArrayList;
import java.util.List;

/**
 * 轨迹抽稀 —— <b>纯逻辑，不碰数据库</b>。
 *
 * <h2>为什么必须要它，而不是「优化一下」</h2>
 * 遥测是每秒一条：一条 10 分钟的任务有 600 个点，一条 2 小时的有 7200 个。
 * 把这些点全传到前端画折线，浏览器会明显卡顿（小程序更糟），
 * 而**画出来的线一点没变细** —— 相邻两个点相隔不到一米，屏幕上本来就是同一个像素。
 * 所以这不是优化，是**能不能用**的问题。
 *
 * <h2>怎么抽：按距离，不按个数</h2>
 * 「每隔 5 个点取一个」看着简单，但会出问题：悬停时（原地不动）点数暴涨，
 * 按个数抽会把原地打转的几十个点均匀采样出来，反而更乱；
 * 而飞得快的时候，按个数抽可能把一段直线抽成折线。
 *
 * <p>按距离则天然合理：**只有移动超过阈值才记一个点**。
 * 停在原地时整段只留一个点，飞得快时保留更多细节。
 *
 * <h2>两道保险</h2>
 * <ol>
 *   <li>首尾两个点**永远保留** —— 起点和终点是这条轨迹最要紧的信息</li>
 *   <li>按距离抽完如果还是太多（比如一条笔直的长航线），
 *       再按固定间隔均匀取，直到不超过上限</li>
 * </ol>
 */
public final class TrackSampler {

    /**
     * 相邻点距离小于这个值就丢掉（米）。
     *
     * <p>取 5 米：无人机定位本身是米级精度，1 米以内的移动不代表任何东西；
     * 而 5 米在常见的地图缩放级别（街道级）上已经不影响线的形状。
     */
    public static final double MIN_DISTANCE_METERS = 5.0;

    /**
     * 返回点数的上限。
     *
     * <p>500 个点在屏幕上已经足够密（多数地图缩放级别下会明显重叠）。
     * 上限的作用是**兜底**：万一有条任务飞了几百公里，
     * 按距离抽稀也可能留下上千个点，那时必须硬砍。
     */
    public static final int MAX_POINTS = 500;

    private TrackSampler() {
    }

    /**
     * 抽稀一条轨迹。
     *
     * @param points 按时间升序的原始点，可为 null
     * @return 抽稀后的点；原始点不超过 1 个时原样返回
     */
    public static List<TaskTrackPoint> sample(List<TaskTrackPoint> points) {
        if (points == null || points.size() <= 2) {
            return points == null ? List.of() : points;
        }

        // ---- 第一道：按距离 ----
        List<TaskTrackPoint> kept = new ArrayList<>();
        kept.add(points.get(0));
        TaskTrackPoint last = points.get(0);
        for (int i = 1; i < points.size() - 1; i++) {
            TaskTrackPoint current = points.get(i);
            if (distanceMeters(last, current) >= MIN_DISTANCE_METERS) {
                kept.add(current);
                last = current;
            }
        }
        // 末尾那个点无条件保留（上面循环刻意跳过了最后一个）
        TaskTrackPoint end = points.get(points.size() - 1);
        if (kept.size() == 1 || distanceMeters(last, end) > 0) {
            kept.add(end);
        }

        // ---- 第二道：还是太多就均匀砍 ----
        return kept.size() <= MAX_POINTS ? kept : shrinkEvenly(kept);
    }

    /**
     * 均匀取点，把数量压到上限以内（首尾保留）。
     *
     * <p>为什么用「均匀」而不是「继续加大距离阈值」：再加大阈值会**不均匀**地
     * 影响不同路段（飞得快的路段被砍掉更多），而均匀取至少在每个路段上
     * 丢掉的密度是一样的。到这个量级时形状已经不重要了，能看出大方向就够。
     */
    private static List<TaskTrackPoint> shrinkEvenly(List<TaskTrackPoint> points) {
        List<TaskTrackPoint> result = new ArrayList<>(MAX_POINTS);
        // 步长含小数，靠乘法定位，避免整数除法把间距算歪
        double step = (double) (points.size() - 1) / (MAX_POINTS - 1);
        for (int i = 0; i < MAX_POINTS; i++) {
            result.add(points.get((int) Math.round(i * step)));
        }
        return result;
    }

    /**
     * 两点间的距离（米）。
     *
     * <p>公式本体搬到了 {@code common/GeoDistance} —— 航线的里程也用它。
     * 两处各留一份「看着简单」的近似实现，正是那种改了一处忘了另一处的地方。
     */
    static double distanceMeters(TaskTrackPoint a, TaskTrackPoint b) {
        return GeoDistance.meters(a.getLng(), a.getLat(), b.getLng(), b.getLat());
    }
}
