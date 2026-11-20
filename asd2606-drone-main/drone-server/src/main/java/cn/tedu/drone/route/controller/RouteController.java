package cn.tedu.drone.route.controller;

import cn.tedu.drone.common.JsonResult;
import cn.tedu.drone.common.PageResult;
import cn.tedu.drone.common.RequirePermission;
import cn.tedu.drone.route.pojo.RouteAction;
import cn.tedu.drone.route.pojo.RouteQueryParam;
import cn.tedu.drone.route.pojo.RouteSaveParam;
import cn.tedu.drone.route.pojo.RouteVO;
import cn.tedu.drone.route.service.RouteService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 航线接口。
 *
 * <p>权限与点位共用 {@code route:list} / {@code route:edit}
 * —— 它们是同一件事的两半，理由见 {@code SurveyPointController} 的类注释。
 */
@Slf4j
@Tag(name = "航线")
@RestController
@RequestMapping("/route")
public class RouteController {

    @Autowired
    private RouteService routeService;

    @Operation(summary = "模块健康检查")
    @GetMapping("/health")
    public JsonResult<String> health() {
        return JsonResult.ok("route 模块就绪");
    }

    /** 航点动作字典，给前端下拉用（理由同点位的 /point/categories） */
    @Operation(summary = "航点动作字典")
    @RequirePermission("route:list")
    @GetMapping("/actions")
    public JsonResult<Map<String, String>> actions() {
        return JsonResult.ok(RouteAction.labels());
    }

    @Operation(summary = "航线列表（不含航点）")
    @RequirePermission("route:list")
    @GetMapping("/list")
    public JsonResult<PageResult<RouteVO>> list(RouteQueryParam param) {
        return JsonResult.ok(routeService.pageRoutes(param));
    }

    @Operation(summary = "航线详情（含航点，按飞行顺序）")
    @RequirePermission("route:list")
    @GetMapping("/{id}")
    public JsonResult<RouteVO> detail(@PathVariable Long id) {
        return JsonResult.ok(routeService.getRoute(id));
    }

    @Operation(summary = "新增航线")
    @RequirePermission("route:edit")
    @PostMapping("/add")
    public JsonResult<RouteVO> add(@RequestBody RouteSaveParam param) {
        RouteVO saved = routeService.addRoute(param);
        log.info("新增航线成功，id={}，名称={}", saved.getId(), saved.getName());
        return JsonResult.ok("新增成功", saved);
    }

    @Operation(summary = "编辑航线")
    @RequirePermission("route:edit")
    @PostMapping("/update")
    public JsonResult<Void> update(@RequestBody RouteSaveParam param) {
        routeService.updateRoute(param);
        return JsonResult.ok("保存成功", null);
    }

    /**
     * 删除航线模板。
     *
     * <p>已创建的任务不受影响 —— 它们各自存了航线快照（见 {@code RouteService#deleteRoute}）。
     */
    @Operation(summary = "删除航线")
    @RequirePermission("route:edit")
    @PostMapping("/delete")
    public JsonResult<Void> delete(@RequestParam("id") Long id) {
        routeService.deleteRoute(id);
        return JsonResult.ok("已删除", null);
    }
}
