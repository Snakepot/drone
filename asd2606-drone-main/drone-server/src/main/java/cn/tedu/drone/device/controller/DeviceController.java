package cn.tedu.drone.device.controller;

import cn.tedu.drone.common.ImportResultVO;
import cn.tedu.drone.common.JsonResult;
import cn.tedu.drone.common.PageResult;
import cn.tedu.drone.common.RequirePermission;
import cn.tedu.drone.common.UserContext;
import cn.tedu.drone.device.pojo.DeviceQueryParam;
import cn.tedu.drone.device.pojo.DeviceSaveParam;
import cn.tedu.drone.device.pojo.DeviceVO;
import cn.tedu.drone.device.service.DeviceExcelService;
import cn.tedu.drone.device.service.DeviceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * 设备模块接口。
 *
 * <p>整体结构对照员工模块：列表检索、详情、增改、导入导出，
 * 每个接口挂一个 {@link RequirePermission} 权限码。
 *
 * <h2>设备与员工在权限设计上的差别</h2>
 * 员工那边有「启用/禁用账号」的概念，设备没有 ——
 * 设备的 status 是「工作状态」（待命/飞行中/维护中），由业务流转决定，
 * 不是管理员手工开关的。所以这里没有 toggle 接口。
 */
@Slf4j
@Tag(name = "设备模块")
@RestController
@RequestMapping("/device")
public class DeviceController {

    @Autowired
    private DeviceService deviceService;

    @Autowired
    private DeviceExcelService deviceExcelService;

    @Operation(summary = "模块健康检查")
    @GetMapping("/health")
    public JsonResult<String> health() {
        return JsonResult.ok("device 模块就绪");
    }

    /**
     * 设备列表（支持检索与分页）。
     *
     * <p>查询条件：关键字（模糊匹配 编号/名称/型号）、类型、工作状态；
     * 都不传就是查全部。导出接口复用同一套条件。
     */
    @Operation(summary = "设备列表")
    @RequirePermission("device:list")
    @GetMapping("/list")
    public JsonResult<PageResult<DeviceVO>> list(DeviceQueryParam param) {
        log.info("Controller [{}] 查询设备列表，条件={}", UserContext.threadLabel(), param);
        return JsonResult.ok(deviceService.pageDevices(param));
    }

    /**
     * 设备详情，供编辑弹窗回显。
     */
    @Operation(summary = "设备详情")
    @RequirePermission("device:list")
    @GetMapping("/{id}")
    public JsonResult<DeviceVO> detail(@PathVariable Long id) {
        return JsonResult.ok(deviceService.getDeviceDetail(id));
    }

    /**
     * 新增设备。
     */
    @Operation(summary = "新增设备")
    @RequirePermission("device:add")
    @PostMapping("/add")
    public JsonResult<Void> add(@RequestBody DeviceSaveParam param) {
        log.info("Controller [{}] 新增设备，编号={}", UserContext.threadLabel(), param.getCode());
        deviceService.addDevice(param);
        return JsonResult.ok("新增成功", null);
    }

    /**
     * 编辑设备。
     *
     * <p>设备编号不可修改 —— 它是档案的业务主键，历史任务与飞行记录可能引用它。
     */
    @Operation(summary = "编辑设备")
    @RequirePermission("device:edit")
    @PostMapping("/update")
    public JsonResult<Void> update(@RequestBody DeviceSaveParam param) {
        log.info("Controller [{}] 编辑设备，id={}", UserContext.threadLabel(), param.getId());
        deviceService.updateDevice(param);
        return JsonResult.ok("保存成功", null);
    }

    /**
     * 下载设备导入模版。
     */
    @Operation(summary = "下载设备导入模版")
    @RequirePermission("device:import")
    @GetMapping("/import/template")
    public void downloadTemplate(HttpServletResponse response) throws IOException {
        prepareExcelResponse(response, "设备导入模版.xlsx");
        deviceExcelService.writeImportTemplate(response.getOutputStream());
    }

    /**
     * 导入设备。
     *
     * <p>不会因为某几行有问题就整体失败：成功的照常入库，
     * 失败的在返回结果里逐条列出（行号 + 原因）。
     */
    @Operation(summary = "导入设备")
    @RequirePermission("device:import")
    @PostMapping("/import")
    public JsonResult<ImportResultVO> importDevices(@RequestParam("file") MultipartFile file) {
        log.info("Controller [{}] 导入设备，文件名={}", UserContext.threadLabel(), file.getOriginalFilename());
        return JsonResult.ok("导入完成", deviceExcelService.importDevices(file));
    }

    /**
     * 按条件导出设备。
     *
     * <p>查询条件与列表接口完全一致（但忽略分页参数）。
     */
    @Operation(summary = "按条件导出设备")
    @RequirePermission("device:export")
    @GetMapping("/export")
    public void exportDevices(DeviceQueryParam param, HttpServletResponse response) throws IOException {
        log.info("Controller [{}] 导出设备，条件={}", UserContext.threadLabel(), param);
        prepareExcelResponse(response, "设备列表.xlsx");
        deviceExcelService.exportDevices(param, response.getOutputStream());
    }

    /**
     * 统一设置 Excel 下载的响应头。
     * <p>文件名用 RFC 5987 的 {@code filename*=UTF-8''} 写法，中文文件名在各浏览器都不会乱码。
     */
    private void prepareExcelResponse(HttpServletResponse response, String filename) throws IOException {
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader("Content-Disposition",
                "attachment; filename*=UTF-8''" + URLEncoder.encode(filename, StandardCharsets.UTF_8));
    }
}
