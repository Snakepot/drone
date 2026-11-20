package cn.tedu.drone.zone.controller;

import cn.tedu.drone.common.JsonResult;
import cn.tedu.drone.common.PageResult;
import cn.tedu.drone.common.RequirePermission;
import cn.tedu.drone.zone.pojo.NoFlyZoneQueryParam;
import cn.tedu.drone.zone.pojo.NoFlyZoneSaveParam;
import cn.tedu.drone.zone.pojo.NoFlyZoneVO;
import cn.tedu.drone.zone.pojo.ZoneShape;
import cn.tedu.drone.zone.pojo.ZoneSource;
import cn.tedu.drone.zone.service.NoFlyZoneService;
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

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 禁飞区接口。
 *
 * <h2>这个模块只管「区域本身」，「要不要拦」在任务那边</h2>
 * 这里没有 {@code /zone/check}：校验要用到「这条任务打算飞哪些点」，
 * 那是任务领域的事（起降点 + 航线快照），放在这边就得反过来去读 task 表。
 * 所以预检接口挂在 {@code TaskController} 上（{@code POST /task/zone-check}），
 * 它内部调这个模块的 {@link NoFlyZoneService#check}。
 * <b>几何计算留在本模块，业务判断留在调用方</b> —— 两边各管各的那一半。
 *
 * <h2>权限点为什么和 route:* 分开</h2>
 * 「维护点位/航线」是日常作业，「划定禁飞区」是改动一条安全控制线。
 * 合成一个权限点，等于谁要能画航线就必须能改禁飞区。
 */
@Slf4j
@Tag(name = "禁飞区")
@RestController
@RequestMapping("/zone")
public class NoFlyZoneController {

    @Autowired
    private NoFlyZoneService noFlyZoneService;

    @Operation(summary = "模块健康检查")
    @GetMapping("/health")
    public JsonResult<String> health() {
        return JsonResult.ok("zone 模块就绪");
    }

    /** 形状与来源的字典，给前端下拉用（理由同点位类型：两处各存一份必然漂） */
    @Operation(summary = "形状与来源字典")
    @RequirePermission("zone:list")
    @GetMapping("/options")
    public JsonResult<Map<String, Map<String, String>>> options() {
        Map<String, Map<String, String>> options = new LinkedHashMap<>();
        options.put("shapes", ZoneShape.labels());
        options.put("sources", ZoneSource.labels());
        return JsonResult.ok(options);
    }

    @Operation(summary = "禁飞区列表")
    @RequirePermission("zone:list")
    @GetMapping("/list")
    public JsonResult<PageResult<NoFlyZoneVO>> list(NoFlyZoneQueryParam param) {
        return JsonResult.ok(noFlyZoneService.pageZones(param));
    }

    @Operation(summary = "禁飞区详情")
    @RequirePermission("zone:list")
    @GetMapping("/{id}")
    public JsonResult<NoFlyZoneVO> detail(@PathVariable Long id) {
        return JsonResult.ok(noFlyZoneService.getZone(id));
    }

    @Operation(summary = "新增禁飞区")
    @RequirePermission("zone:edit")
    @PostMapping("/add")
    public JsonResult<NoFlyZoneVO> add(@RequestBody NoFlyZoneSaveParam param) {
        NoFlyZoneVO saved = noFlyZoneService.addZone(param);
        log.info("新增禁飞区成功，id={}，名称={}", saved.getId(), saved.getName());
        return JsonResult.ok("新增成功", saved);
    }

    @Operation(summary = "编辑禁飞区")
    @RequirePermission("zone:edit")
    @PostMapping("/update")
    public JsonResult<Void> update(@RequestBody NoFlyZoneSaveParam param) {
        noFlyZoneService.updateZone(param);
        return JsonResult.ok("保存成功", null);
    }

    /**
     * 启用 / 停用。
     *
     * <p>单开一个接口而不是走 {@code /zone/update}：排查「为什么这条任务被拦了」时，
     * 人只想把某个区临时停掉，<b>不该顺手把它的形状也提交一遍</b>
     * （那等于把整块几何再写一次，中途被别的编辑覆盖了都看不出来）。
     */
    @Operation(summary = "启用 / 停用禁飞区")
    @RequirePermission("zone:edit")
    @PostMapping("/{id}/toggle")
    public JsonResult<Void> toggle(@PathVariable Long id, @RequestParam("enabled") boolean enabled) {
        noFlyZoneService.setEnabled(id, enabled);
        return JsonResult.ok(enabled ? "已启用" : "已停用", null);
    }

    @Operation(summary = "删除禁飞区")
    @RequirePermission("zone:edit")
    @PostMapping("/delete")
    public JsonResult<Void> delete(@RequestParam("id") Long id) {
        noFlyZoneService.deleteZone(id);
        return JsonResult.ok("已删除", null);
    }
}
