package cn.tedu.drone.dashboard.controller;

import cn.tedu.drone.common.JsonResult;
import cn.tedu.drone.common.RequirePermission;
import cn.tedu.drone.dashboard.config.ScreenProperties;
import cn.tedu.drone.dashboard.pojo.DashboardSummaryVO;
import cn.tedu.drone.dashboard.service.DashboardService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 数字大屏的数据接口。
 *
 * <h2>⚠️ 总览接口刻意**不加权限注解**</h2>
 * 加一个 {@code @RequirePermission("task:list")} 会让飞手连调都调不了 ——
 * 而他本该看到一个「只关于他自己」的总览。
 * 所以这里的边界是**「登录」**（拦截器默认要求登录），
 * 至于「哪些块给他」由 {@link DashboardService} 按权限逐个裁。
 *
 * <p>这也是为什么<b>不加一个 {@code dashboard:view} 权限点</b>：
 * 「能不能看大屏」和「能不能看数据」本就是一件事，拆成两个开关只会让人配错。
 *
 * <h2>配置接口为什么不限权限</h2>
 * 它只回「刷新间隔、轮播几屏」这类**运维参数**，没有任何业务数据；
 * 而且每一屏都要先拿到它才知道怎么刷新。要登录才给即可。
 */
@Slf4j
@Tag(name = "数字大屏")
@RestController
@RequestMapping("/dashboard")
public class DashboardController {

    @Autowired
    private DashboardService dashboardService;

    @Operation(summary = "大屏总览（按权限裁剪，没权限的块不返回）")
    @GetMapping("/summary")
    public JsonResult<DashboardSummaryVO> summary() {
        return JsonResult.ok(dashboardService.summary());
    }

    /**
     * 最近的告警（大屏上的滚动流）。
     *
     * <p>权限 {@code alert:list}：它返回的是**全量告警**（不是只看自己任务的），
     * 所以不能像总览那样放开给所有人。飞手看自己任务上的告警走 {@code /task/alert/list}。
     */
    @Operation(summary = "最近告警（大屏滚动流）")
    @RequirePermission("alert:list")
    @GetMapping("/recent")
    public JsonResult<List<DashboardSummaryVO.RecentAlert>> recent(
            @RequestParam(value = "limit", defaultValue = "8") int limit) {
        return JsonResult.ok(dashboardService.recentAlerts(limit));
    }

    @Operation(summary = "大屏配置（刷新间隔、轮播）")
    @GetMapping("/config")
    public JsonResult<ScreenProperties> config() {
        return JsonResult.ok(dashboardService.config());
    }
}
