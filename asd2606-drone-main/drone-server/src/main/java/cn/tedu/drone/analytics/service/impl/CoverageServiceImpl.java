package cn.tedu.drone.analytics.service.impl;

import cn.tedu.drone.analytics.dao.AnalyticsDao;
import cn.tedu.drone.analytics.pojo.CoveragePointExcel;
import cn.tedu.drone.analytics.pojo.CoverageVO;
import cn.tedu.drone.analytics.service.CoverageService;
import cn.tedu.drone.common.CoordinateUtil;
import cn.tedu.drone.route.pojo.RoutePointPO;
import cn.tedu.drone.route.pojo.RouteSnapshot;
import cn.tedu.drone.route.service.RouteService;
import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.ExcelWriter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.OutputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 点位覆盖率的实现（见 {@code docs/业务分析方案.md} 2.5 与第四步）。
 *
 * <h2>算法就三步，难的是口径而不是代码</h2>
 * <ol>
 *   <li><b>计划点</b>：区间内已结束的任务，各取它的航线快照（快照 = 当时那份航线，
 *       之后航线怎么改都不影响历史口径）</li>
 *   <li><b>到过</b>：那条任务那个航点有打卡，且偏差 ≤ 半径</li>
 *   <li><b>汇总</b>：按点位（看客户关心哪些点没去）、按航线（看哪条线覆盖得差）各来一遍</li>
 * </ol>
 *
 * <h2>为什么不用轨迹判「到过」</h2>
 * 那要算「点位到轨迹的最短距离」——几何工具我们有，但代价是要扫证据表，
 * 那是**单任务下钻**时才划算的事。所以这一步只用打卡，并把这条边界写进文档与 VO：
 * 宁可说清「什么没做」，也不要做了个看起来更准、却慢到没人用的东西。
 */
@Slf4j
@Service
public class CoverageServiceImpl implements CoverageService {

    @Autowired
    private AnalyticsDao analyticsDao;

    @Autowired
    private RouteService routeService;

    /** 判定「到过」的半径（米）：打卡偏差小于它才算。手机定位误差通常十几米，100 米足够宽松 */
    @Value("${drone.analytics.coverage.radius-meters:100}")
    private int radiusMeters;

    @Override
    public CoverageVO coverage(LocalDateTime from, LocalDateTime to) {
        List<Map<String, Object>> tasks = analyticsDao.selectFinishedTasksWithSnapshot(from, to);
        Map<String, Integer> checkinDeviation = checkinDeviationByPoint(from, to);

        // 计划点按「点位 id」合并（同一航线被多条任务用过、同一个点被多条航线用过，都要并成一行）
        // 没有 pointId 的航点（极少数）退回用坐标当键，免得几行挤成一行
        Map<String, CoverageVO.PointCoverage> byPoint = new LinkedHashMap<>();
        Map<String, Set<String>> plannedByRoute = new LinkedHashMap<>();
        Map<String, Set<String>> visitedByRoute = new LinkedHashMap<>();
        Map<String, Integer> taskCountByRoute = new HashMap<>();

        int planned = 0;
        int visited = 0;
        int tasksWithoutRoute = 0;
        Set<Long> pointIds = new HashSet<>();

        for (Map<String, Object> task : tasks) {
            String snapshotJson = (String) task.get("routeSnapshot");
            if (snapshotJson == null || snapshotJson.isBlank()) {
                tasksWithoutRoute++;
                continue;
            }
            RouteSnapshot snapshot = routeService.parseSnapshot(snapshotJson);
            if (snapshot == null || snapshot.getPoints() == null || snapshot.getPoints().isEmpty()) {
                tasksWithoutRoute++;
                continue;
            }
            long taskId = ((Number) task.get("id")).longValue();
            String routeName = routeNameOf(snapshot);
            taskCountByRoute.merge(routeName, 1, Integer::sum);

            for (RoutePointPO point : snapshot.getPoints()) {
                String key = keyOf(point);
                int deviation = checkinDeviation.getOrDefault(checkinKey(taskId, point.getSeq()), Integer.MAX_VALUE);
                boolean hit = deviation <= radiusMeters;

                planned++;
                plannedByRoute.computeIfAbsent(routeName, k -> new HashSet<>()).add(key);
                if (hit) {
                    visited++;
                    visitedByRoute.computeIfAbsent(routeName, k -> new HashSet<>()).add(key);
                }

                CoverageVO.PointCoverage row = byPoint.get(key);
                if (row == null) {
                    row = new CoverageVO.PointCoverage()
                            .setPointId(point.getPointId())
                            .setName(null)
                            .setLng(point.getLng())
                            .setLat(point.getLat())
                            .setPlannedTimes(0)
                            .setVisitedTimes(0)
                            .setCovered(false);
                    byPoint.put(key, row);
                    if (point.getPointId() != null) {
                        pointIds.add(point.getPointId());
                    }
                }
                row.setPlannedTimes(row.getPlannedTimes() + 1);
                if (hit) {
                    row.setVisitedTimes(row.getVisitedTimes() + 1);
                    row.setCovered(true);
                }
            }
        }

        fillPointNames(byPoint, pointIds);

        List<CoverageVO.PointCoverage> points = new ArrayList<>(byPoint.values());
        // 没到过的排前面：这一屏是给「这个月还差哪些点」用的
        points.sort(Comparator.comparing(CoverageVO.PointCoverage::getCovered)
                .thenComparing(CoverageVO.PointCoverage::getPlannedTimes, Comparator.reverseOrder()));

        return new CoverageVO()
                .setFrom(from == null ? null : from.toString())
                .setTo(to == null ? null : to.toString())
                .setCoordinateSystem("gcj02")
                .setRadiusMeters(radiusMeters)
                .setTaskCount(tasks.size())
                .setTasksWithoutRoute(tasksWithoutRoute)
                .setPlannedPoints(planned)
                .setVisitedPoints(visited)
                .setCoverageRate(planned == 0 ? null : percent(visited, planned))
                .setRoutes(routeRows(plannedByRoute, visitedByRoute, taskCountByRoute))
                .setPoints(points);
    }

    /** 「任务 + 航点序号」→ 打卡偏差；同一航点打卡多次取最小的那次（离得最近才算到过） */
    private Map<String, Integer> checkinDeviationByPoint(LocalDateTime from, LocalDateTime to) {
        Map<String, Integer> byKey = new HashMap<>();
        for (Map<String, Object> row : analyticsDao.selectCheckinsOfFinishedTasks(from, to)) {
            long taskId = ((Number) row.get("taskId")).longValue();
            int seq = ((Number) row.get("seq")).intValue();
            int deviation = row.get("deviationM") == null ? Integer.MAX_VALUE
                    : ((Number) row.get("deviationM")).intValue();
            byKey.merge(checkinKey(taskId, seq), deviation, Math::min);
        }
        return byKey;
    }

    /** 点位在库里的名字/编号；查不到的（点位已被删）退回「航点」+ 坐标，而不是显示空白 */
    private void fillPointNames(Map<String, CoverageVO.PointCoverage> byPoint, Set<Long> pointIds) {
        Map<Long, Map<String, Object>> points = new HashMap<>();
        if (!pointIds.isEmpty()) {
            for (Map<String, Object> row : analyticsDao.selectPointsByIds(new ArrayList<>(pointIds))) {
                points.put(((Number) row.get("id")).longValue(), row);
            }
        }

        byPoint.values().forEach(row -> {
            Map<String, Object> point = row.getPointId() == null ? null : points.get(row.getPointId());
            if (point != null) {
                row.setCode((String) point.get("code")).setName(String.valueOf(point.get("name")));
            } else if (row.getPointId() == null) {
                // 航点没关联点位库（航线是自己在地图上点的）：如实说，别让人以为数据丢了
                row.setName(String.format("（航点 %.5f, %.5f）", row.getLng(), row.getLat()));
            } else {
                // 确实关联过点位、但点位已被删：这种情况要能看出来（否则会以为是别的问题）
                row.setName(String.format("（已删点位 %.5f, %.5f）", row.getLng(), row.getLat()));
            }
            // 出入都转 GCJ-02：与点位库、航线、监控看板同一条约定
            double[] gcj = CoordinateUtil.wgs84ToGcj02(row.getLng(), row.getLat());
            row.setLng(gcj[0]).setLat(gcj[1]);
        });
    }

    private List<CoverageVO.RouteCoverage> routeRows(Map<String, Set<String>> plannedByRoute,
                                                    Map<String, Set<String>> visitedByRoute,
                                                    Map<String, Integer> taskCountByRoute) {
        List<CoverageVO.RouteCoverage> rows = new ArrayList<>();
        plannedByRoute.forEach((name, planned) -> {
            int visited = visitedByRoute.getOrDefault(name, Set.of()).size();
            rows.add(new CoverageVO.RouteCoverage()
                    .setName(name)
                    .setPlannedPoints(planned.size())
                    .setVisitedPoints(visited)
                    .setCoverageRate(planned.isEmpty() ? null : percent(visited, planned.size()))
                    .setTaskCount(taskCountByRoute.getOrDefault(name, 0)));
        });
        // 覆盖得差的排前面
        rows.sort(Comparator.comparingDouble(row -> row.getCoverageRate() == null ? 0 : row.getCoverageRate()));
        return rows;
    }

    private String routeNameOf(RouteSnapshot snapshot) {
        String name = snapshot.getName();
        return (name == null || name.isBlank()) ? "（未命名航线）" : name;
    }

    // ------------------------------------------------------------------
    // 导出（给客户的那份表）
    // ------------------------------------------------------------------

    @Override
    public void exportCoverage(LocalDateTime from, LocalDateTime to, OutputStream out) {
        CoverageVO data = coverage(from, to);
        ExcelWriter writer = EasyExcel.write(out).build();
        try {
            // 第一张：汇总（两列，人一眼看懂这是什么）
            List<List<String>> head = List.of(List.of("项"), List.of("值"));
            List<List<String>> summary = List.of(
                    List.of("统计区间", data.getFrom() + " ~ " + data.getTo()),
                    List.of("已结束任务数", String.valueOf(data.getTaskCount())),
                    List.of("其中没配航线的任务", String.valueOf(data.getTasksWithoutRoute())),
                    List.of("计划航点总数", String.valueOf(data.getPlannedPoints())),
                    List.of("到过的航点", String.valueOf(data.getVisitedPoints())),
                    List.of("覆盖率", data.getCoverageRate() == null ? "—" : data.getCoverageRate() + "%"),
                    List.of("判定半径（米）", String.valueOf(data.getRadiusMeters())),
                    List.of("口径说明", "「到过」= 该任务该航点有打卡记录，且打卡偏差不超过半径"));
            writer.write(summary, EasyExcel.writerSheet(0, "汇总").head(head).build());

            // 第二张：点位明细（客户要的就是这张：哪些点去了、哪些没去）
            writer.write(data.getPoints().stream()
                            .map(row -> new CoveragePointExcel()
                                    .setCode(row.getCode())
                                    .setName(row.getName())
                                    .setLng(row.getLng())
                                    .setLat(row.getLat())
                                    .setPlannedTimes(row.getPlannedTimes())
                                    .setVisitedTimes(row.getVisitedTimes())
                                    .setCovered(Boolean.TRUE.equals(row.getCovered()) ? "是" : "否"))
                            .toList(),
                    EasyExcel.writerSheet(1, "点位明细").head(CoveragePointExcel.class).build());
        } finally {
            writer.finish();
        }
        log.info("覆盖率导出完成：{} → {}，点位 {} 行", from, to, data.getPoints().size());
    }

    /** 合并用的键：优先点位 id；没有就退回坐标（同一条航线里同一个坐标就是同一个点） */
    private String keyOf(RoutePointPO point) {
        return point.getPointId() != null
                ? "p" + point.getPointId()
                : String.format("c%.6f,%.6f", point.getLng(), point.getLat());
    }

    private String checkinKey(long taskId, Integer seq) {
        return taskId + "#" + seq;
    }

    private double percent(long count, long total) {
        return total <= 0 ? 0.0 : Math.round(count * 1000.0 / total) / 10.0;
    }
}
