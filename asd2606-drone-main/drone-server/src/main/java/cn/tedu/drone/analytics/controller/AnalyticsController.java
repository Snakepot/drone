package cn.tedu.drone.analytics.controller;

import cn.tedu.drone.analytics.pojo.AnalyticsOverviewVO;
import cn.tedu.drone.analytics.pojo.CoverageVO;
import cn.tedu.drone.analytics.pojo.DispatchBlockStatsVO;
import cn.tedu.drone.analytics.pojo.EfficiencyVO;
import cn.tedu.drone.analytics.pojo.EvidenceTrendVO;
import cn.tedu.drone.analytics.service.AnalyticsService;
import cn.tedu.drone.analytics.service.CoverageService;
import cn.tedu.drone.common.JsonResult;
import cn.tedu.drone.common.RequirePermission;
import cn.tedu.drone.common.UserContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

/**
 * 业务分析的数据接口（见 {@code docs/业务分析方案.md}）。
 *
 * <h2>权限为什么复用 {@code task:list}</h2>
 * 这一屏答的是「活攒着为什么没派出去」—— 那是**管理动作**的问题，
 * 看数据的人和「任务管理」那张总表的人是同一批（管理员 / 机务）。
 * 飞手不该看到「全公司的积压归因」，所以这里不开新权限点、也不放开给登录即可。
 *
 * <h2>为什么是整个区间一次算完，而不是三个接口</h2>
 * 三个指标要一起读才有意义（「这周缺设备多」+「现在积压的活等了一天以上」= 该买了）。
 * 分开三次请求会出现「归因是 10 秒前的、积压是刚算的」这种时间错位，
 * 而且每个指标都要重复一次区间参数校验。
 */
@Slf4j
@Tag(name = "业务分析")
@RestController
@RequestMapping("/analytics")
public class AnalyticsController {

    @Autowired
    private AnalyticsService analyticsService;

    @Autowired
    private CoverageService coverageService;

    /**
     * 任务与告警概览（2.1 与 2.4 里最常被问的那几个数）。
     *
     * <p>时间格式固定 {@code yyyy-MM-dd HH:mm:ss}（与任务接口上的 @JsonFormat 一致），
     * 两个参数都可以不传，不传就是「最近 7 天」。
     */
    @Operation(summary = "任务与告警概览（任务量趋势 / 完成率 / 按时率 / 平均时长 / 告警趋势 / MTTR）")
    @RequirePermission("task:list")
    @GetMapping("/overview")
    public JsonResult<AnalyticsOverviewVO> overview(
            @RequestParam(value = "from", required = false)
            @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime from,
            @RequestParam(value = "to", required = false)
            @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime to) {
        return JsonResult.ok(analyticsService.overview(from, to));
    }

    /**
     * 待派发积压的归因（2.7 的三个指标）。
     *
     * <p>时间格式固定 {@code yyyy-MM-dd HH:mm:ss}（与任务接口上的 @JsonFormat 一致），
     * 两个参数都可以不传，不传就是「最近 7 天」。
     */
    @Operation(summary = "待派发积压的归因（归因占比 / 积压时长分布 / 创建到派发中位时长）")
    @RequirePermission("task:list")
    @GetMapping("/dispatch-block")
    public JsonResult<DispatchBlockStatsVO> dispatchBlock(
            @RequestParam(value = "from", required = false)
            @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime from,
            @RequestParam(value = "to", required = false)
            @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime to) {
        return JsonResult.ok(analyticsService.dispatchBlockStats(from, to));
    }

    /**
     * 飞手与设备效率（2.2 与 2.3）：两个榜单。
     *
     * <p>一次给两个榜，因为它们在同一页、共用同一个区间；分成两个接口只会让
     * 「两半的时间范围不一致」有机会发生。
     */
    @Operation(summary = "飞手与设备效率（工作量 / 按时率 / 拒绝率 / 无旁证占比 / 利用率 / 故障率）")
    @RequirePermission("task:list")
    @GetMapping("/efficiency")
    public JsonResult<EfficiencyVO> efficiency(
            @RequestParam(value = "from", required = false)
            @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime from,
            @RequestParam(value = "to", required = false)
            @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime to) {
        return JsonResult.ok(analyticsService.efficiency(from, to));
    }

    /**
     * 证据量与活跃设备趋势。
     *
     * <p>历史走预聚合（`stat_daily`），还没算的那几天（通常是今天）现场补 ——
     * 报文里会带「有几天来自预聚合、有几天是实时补的」，界面据此说明数据新不新。
     */
    @Operation(summary = "证据量与活跃设备趋势（历史走预聚合，今天实时补）")
    @RequirePermission("task:list")
    @GetMapping("/evidence")
    public JsonResult<EvidenceTrendVO> evidence(
            @RequestParam(value = "from", required = false)
            @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime from,
            @RequestParam(value = "to", required = false)
            @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime to) {
        return JsonResult.ok(analyticsService.evidenceTrend(from, to));
    }

    /**
     * 点位覆盖率：该去的点去了没有，按点位逐条列出来（第四步）。
     *
     * <p>坐标是 GCJ-02（报文里声明了 `coordinateSystem`），可以直接画在地图上。
     */
    @Operation(summary = "点位覆盖率（计划航点 vs 打卡）")
    @RequirePermission("task:list")
    @GetMapping("/coverage")
    public JsonResult<CoverageVO> coverage(
            @RequestParam(value = "from", required = false)
            @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime from,
            @RequestParam(value = "to", required = false)
            @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime to) {
        return JsonResult.ok(coverageService.coverage(from, to));
    }

    /**
     * 覆盖率导出（给客户的那份表）：第一张「汇总」、第二张「点位明细」。
     *
     * <p>与页面上看到的是同一份数据（同一个 Service 方法算出来的）——
     * 否则客户手里的表和你在屏幕上指的表会对不上。
     */
    @Operation(summary = "导出点位覆盖率（xlsx）")
    @RequirePermission("task:list")
    @GetMapping("/coverage/export")
    public void exportCoverage(
            @RequestParam(value = "from", required = false)
            @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime from,
            @RequestParam(value = "to", required = false)
            @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime to,
            HttpServletResponse response) throws IOException {
        log.info("Controller [{}] 导出覆盖率，from={} to={}", UserContext.threadLabel(), from, to);
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader("Content-Disposition",
                "attachment; filename*=UTF-8''" + URLEncoder.encode("点位覆盖率.xlsx", StandardCharsets.UTF_8));
        coverageService.exportCoverage(from, to, response.getOutputStream());
    }
}
