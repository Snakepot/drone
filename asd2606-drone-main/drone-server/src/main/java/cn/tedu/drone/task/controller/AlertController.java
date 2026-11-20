package cn.tedu.drone.task.controller;

import cn.tedu.drone.common.JsonResult;
import cn.tedu.drone.common.PageResult;
import cn.tedu.drone.common.RequirePermission;
import cn.tedu.drone.common.UserContext;
import cn.tedu.drone.task.pojo.AlertHandleParam;
import cn.tedu.drone.task.pojo.AlertQueryParam;
import cn.tedu.drone.task.pojo.AlertVO;
import cn.tedu.drone.task.service.AlertService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 告警模块接口（管理端）。
 *
 * <h2>接口与权限的对应</h2>
 * <ul>
 *   <li>{@code GET /alert/list}、{@code GET /alert/{id}} 需要 {@code alert:list}</li>
 *   <li>{@code POST /alert/handle} 需要 {@code alert:handle}</li>
 * </ul>
 * 「查看」与「处理」拆成两个权限点，是因为它们是两种职责：
 * 机务可以只看（知道设备出了什么事），但“谁来关掉这条告警”是要单独授予的。
 * 现在两个都给了管理员与机务（见 {@code migration/alert_permission.sql}），
 * 但把它们分开，将来要调整时不用改代码。
 *
 * <h2>飞手看不到这些接口</h2>
 * 飞手只有 {@code task:execute}，没有 {@code alert:list}——
 * 他能看到的是「派给我的任务上挂着的告警」，那个入口在飞手端，
 * 而且**只能看不能处理**（设计文档第十二章：自己报的问题自己关掉等于没有监督）。
 *
 * <h2>为什么处理动作只有一个接口</h2>
 * 认领 / 已处理 / 误报三个动作改的是同一个东西、走同一张状态机表。
 * 拆成三个接口的话，权限点、前端调用、测试都要各写三份，
 * 而它们之间的差别只是一个字符串。
 */
@Slf4j
@Tag(name = "告警模块")
@RestController
@RequestMapping("/alert")
public class AlertController {

    @Autowired
    private AlertService alertService;

    /**
     * 告警列表。
     *
     * <p>排序不是简单的时间倒序：未处理的在前、级别高的在前、时间新的在前 ——
     * 第一屏要回答「现在有什么要管的」（见 Mapper 里的说明）。
     */
    @Operation(summary = "告警列表")
    @RequirePermission("alert:list")
    @GetMapping("/list")
    public JsonResult<PageResult<AlertVO>> list(AlertQueryParam param) {
        log.info("Controller [{}] 查询告警列表，条件={}", UserContext.threadLabel(), param);
        return JsonResult.ok(alertService.pageAlerts(param));
    }

    /** 告警详情（比列表多一个 payload：原始报文或人填的内容） */
    @Operation(summary = "告警详情")
    @RequirePermission("alert:list")
    @GetMapping("/{id}")
    public JsonResult<AlertVO> detail(@PathVariable Long id) {
        return JsonResult.ok(alertService.getAlert(id));
    }

    /**
     * 处理告警：认领 / 标记已处理 / 标记误报。
     *
     * <p>处理人从登录态取，不接受前端传。
     */
    @Operation(summary = "处理告警")
    @RequirePermission("alert:handle")
    @PostMapping("/handle")
    public JsonResult<AlertVO> handle(@RequestBody AlertHandleParam param) {
        log.info("Controller [{}] 处理告警，id={}，动作={}", UserContext.threadLabel(),
                param == null ? null : param.getId(), param == null ? null : param.getAction());
        return JsonResult.ok("已处理", alertService.handleAlert(param));
    }
}
