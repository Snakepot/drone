package cn.tedu.drone.route.controller;

import cn.tedu.drone.common.JsonResult;
import cn.tedu.drone.common.PageResult;
import cn.tedu.drone.common.RequirePermission;
import cn.tedu.drone.route.pojo.PointCategory;
import cn.tedu.drone.route.pojo.SurveyPointQueryParam;
import cn.tedu.drone.route.pojo.SurveyPointSaveParam;
import cn.tedu.drone.route.pojo.SurveyPointVO;
import cn.tedu.drone.route.service.SurveyPointService;
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
 * 点位库接口。
 *
 * <h2>权限为什么是 route:list / route:edit，而不是复用 task:add</h2>
 * 点位维护是<b>机务</b>（maintainer）的日常活，而机务有 {@code task:list}
 * 却没有 {@code task:add}。复用任务那边的权限点会把他挡在门外，
 * 于是「能不能管点位」变成「能不能建任务」的副作用 —— 那是两件不同的事。
 *
 * <h2>它和航线共用同一个权限点</h2>
 * 点位与航线是同一件事的两半（在地图上规划「去哪飞」），
 * 界面上也在同一个菜单组里。分成两个权限点只会让我们多维护两个
 * 「权限点 → 角色」的对应关系，却没有任何人能据此区分出不同的职责。
 */
@Slf4j
@Tag(name = "点位库")
@RestController
@RequestMapping("/point")
public class SurveyPointController {

    @Autowired
    private SurveyPointService surveyPointService;

    @Operation(summary = "模块健康检查")
    @GetMapping("/health")
    public JsonResult<String> health() {
        return JsonResult.ok("point 模块就绪");
    }

    /**
     * 点位类型字典。
     *
     * <p>让前端下拉从后端取，是为了避免「后端加了类型、前端下拉没有它」——
     * 那会让新类型的点位压根建不出来。目前只有 4 条，但这类「两边各存一份」
     * 的字段迟早会漂。
     */
    @Operation(summary = "点位类型字典")
    @RequirePermission("route:list")
    @GetMapping("/categories")
    public JsonResult<Map<String, String>> categories() {
        return JsonResult.ok(PointCategory.labels());
    }

    @Operation(summary = "点位列表")
    @RequirePermission("route:list")
    @GetMapping("/list")
    public JsonResult<PageResult<SurveyPointVO>> list(SurveyPointQueryParam param) {
        return JsonResult.ok(surveyPointService.pagePoints(param));
    }

    @Operation(summary = "点位详情")
    @RequirePermission("route:list")
    @GetMapping("/{id}")
    public JsonResult<SurveyPointVO> detail(@PathVariable Long id) {
        return JsonResult.ok(surveyPointService.getPoint(id));
    }

    @Operation(summary = "新增点位")
    @RequirePermission("route:edit")
    @PostMapping("/add")
    public JsonResult<SurveyPointVO> add(@RequestBody SurveyPointSaveParam param) {
        SurveyPointVO saved = surveyPointService.addPoint(param);
        return JsonResult.ok("新增成功", saved);
    }

    @Operation(summary = "编辑点位")
    @RequirePermission("route:edit")
    @PostMapping("/update")
    public JsonResult<SurveyPointVO> update(@RequestBody SurveyPointSaveParam param) {
        surveyPointService.updatePoint(param);
        return JsonResult.ok("保存成功", null);
    }

    /**
     * 删除点位。
     *
     * <p>用 POST 而不是 DELETE：本项目的写操作都是 POST（见任务、设备模块），
     * 拦截器与前端请求封装也都按这个约定走，为一个接口破例不划算。
     */
    @Operation(summary = "删除点位")
    @RequirePermission("route:edit")
    @PostMapping("/delete")
    public JsonResult<Void> delete(@RequestParam("id") Long id) {
        surveyPointService.deletePoint(id);
        return JsonResult.ok("已删除", null);
    }
}
