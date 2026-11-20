package cn.tedu.drone.analytics.service;

import cn.tedu.drone.analytics.pojo.CoverageVO;

import java.io.OutputStream;
import java.time.LocalDateTime;

/**
 * 点位覆盖率（见 {@code docs/业务分析方案.md} 2.5 与第四步）。
 *
 * <h2>口径只有一处</h2>
 * 「到过」= 那条任务那个航点有打卡记录，且偏差 ≤ 半径。判定写在实现里一处，
 * 页面与导出都拿同一份 {@link CoverageVO} —— 导出的表与屏幕上的表必然一致
 * （不然客户手里的表和你在屏幕上指的表会对不上）。
 *
 * <h2>这一步**不做**「点到轨迹的距离」</h2>
 * 那要扫证据表做几何计算，是**单任务下钻**时才划算的事（一条任务的轨迹几百个点，
 * 算得起；一个月所有任务一起算就是另一回事）。文档里写明了这个边界。
 */
public interface CoverageService {

    /**
     * 区间内的点位覆盖情况。
     *
     * @param from 区间起点（含），可为 null
     * @param to   区间终点（不含），可为 null
     */
    CoverageVO coverage(LocalDateTime from, LocalDateTime to);

    /**
     * 把覆盖率导成 xlsx：第一张「汇总」、第二张「点位明细」。
     *
     * @param from 区间起点（含），可为 null
     * @param to   区间终点（不含），可为 null
     * @param out  由 Controller 给的响应流（照设备导出的写法，服务不碰 HttpServletResponse）
     */
    void exportCoverage(LocalDateTime from, LocalDateTime to, OutputStream out);
}
