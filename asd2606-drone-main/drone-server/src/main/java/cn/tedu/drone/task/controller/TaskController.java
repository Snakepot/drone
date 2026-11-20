package cn.tedu.drone.task.controller;

import cn.tedu.drone.common.BusinessException;
import cn.tedu.drone.common.JsonResult;
import cn.tedu.drone.common.PageResult;
import cn.tedu.drone.common.RequirePermission;
import cn.tedu.drone.common.UserContext;
import cn.tedu.drone.task.pojo.AlertQueryParam;
import cn.tedu.drone.task.pojo.AlertVO;
import cn.tedu.drone.task.pojo.TaskAlertReportParam;
import cn.tedu.drone.task.pojo.TaskAttachmentPO;
import cn.tedu.drone.task.pojo.TaskAttachmentVO;
import cn.tedu.drone.task.pojo.TaskCheckinParam;
import cn.tedu.drone.task.pojo.TaskCheckinVO;
import cn.tedu.drone.task.pojo.TaskCreateParam;
import cn.tedu.drone.task.pojo.TaskDetailVO;
import cn.tedu.drone.task.pojo.TaskDispatchOptionsVO;
import cn.tedu.drone.task.pojo.TaskDispatchParam;
import cn.tedu.drone.task.pojo.TaskFinishParam;
import cn.tedu.drone.task.pojo.TaskQueryParam;
import cn.tedu.drone.task.pojo.TaskRefuseParam;
import cn.tedu.drone.task.pojo.TaskReportReviseParam;
import cn.tedu.drone.task.pojo.TaskReportVO;
import cn.tedu.drone.task.pojo.TaskStartParam;
import cn.tedu.drone.task.pojo.TaskTrackVO;
import cn.tedu.drone.task.pojo.TaskMapVO;
import cn.tedu.drone.task.pojo.TaskMonitorVO;
import cn.tedu.drone.task.pojo.TaskVO;
import cn.tedu.drone.task.pojo.TaskWithdrawParam;
import cn.tedu.drone.task.pojo.TaskZoneCheckParam;
import cn.tedu.drone.task.service.AttachmentService;
import cn.tedu.drone.task.service.TaskService;
import cn.tedu.drone.task.service.TaskTrackService;
import cn.tedu.drone.zone.pojo.ZoneHitVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.Closeable;
import java.io.IOException;
import java.util.List;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * 任务模块接口（MVP 第二阶段：创建、派发、查询）。
 *
 * <h2>接口与权限的对应</h2>
 * <ul>
 *   <li>{@code POST /task/create} 需要 {@code task:add} —— 只有管理员能建任务</li>
 *   <li>{@code POST /task/dispatch}、{@code POST /task/withdraw}、
 *       {@code GET /task/dispatch-options} 需要 {@code task:dispatch}</li>
 *   <li>{@code GET /task/list}、{@code GET /task/{id}} 需要 {@code task:list}</li>
 *   <li>{@code GET /task/my} 需要 {@code task:execute} —— 飞手看「派给我的」用这个，
 *       他看不到 {@code /task/list} 那张总表</li>
 * </ul>
 *
 * <h2>为什么派发的下拉数据要单独开接口</h2>
 * 派发弹窗需要「能接活的飞手」和「能出机的设备」两份名单。前端当然可以去调
 * {@code /user/list?roleCode=pilot} 和 {@code /device/list?status=idle}，
 * 但那两个接口分别要求 {@code staff:list} 和 {@code device:list} ——
 * 等于逼着「能派发」的角色再去拿两个不相干的权限。
 * 所以这里开一个只认 {@code task:dispatch} 的接口，按派发的需要返回最少的字段。
 *
 * <h2>出入参一律是 Param 和 VO，不出现 PO</h2>
 * 入参用 {@code XxxParam}（前端能传什么，由它说了算），
 * 出参用 {@code XxxVO}（前端能看什么，也由它说了算）。
 * PO 只活在 Service 与 DAO 之间，绝不从这里漏出去 ——
 * 否则表结构一改，接口的形状就跟着被动改变。
 */
@Slf4j
@Tag(name = "任务模块")
@RestController
@RequestMapping("/task")
public class TaskController {

    @Autowired
    private TaskService taskService;

    /** 轨迹单独一个 Service（查证据坐标 + 抽稀 + 转坐标系），理由见它的接口注释 */
    @Autowired
    private TaskTrackService taskTrackService;

    /**
     * 创建任务。
     *
     * <p>创建只定义「要做什么」——指派飞手与设备是派发环节的事，
     * 所以这里既不接收飞手也不接收设备。
     */
    @Operation(summary = "创建任务")
    @RequirePermission("task:add")
    @PostMapping("/create")
    public JsonResult<TaskVO> create(@RequestBody TaskCreateParam param) {
        log.info("Controller [{}] 创建任务，名称={}", UserContext.threadLabel(),
                param == null ? null : param.getTaskName());
        return JsonResult.ok("创建成功", taskService.createTask(param));
    }

    /**
     * 创建任务前的禁飞区预检（只查不写）。
     *
     * <h2>为什么单独一个接口，而不是让创建接口把命中后也返回成功</h2>
     * 命中的处理是「软拦」：确认已知晓风险就能建。前端要先知道
     * <b>撞的是哪几块区域</b>，才能把那句话摆到人面前，而这个信息
     * 只有服务端算得出来（航线的点在库里，前端手上只有 routeId）。
     *
     * <p>它<b>不代替</b>创建时的校验 —— 那个写操作自己也会再查一遍。
     * 只靠预检等于「绕过前端就能免检」。
     */
    @Operation(summary = "创建任务前的禁飞区预检")
    @RequirePermission("task:add")
    @PostMapping("/zone-check")
    public JsonResult<List<ZoneHitVO>> zoneCheck(@RequestBody TaskZoneCheckParam param) {
        if (param == null) {
            return JsonResult.ok(List.of());
        }
        return JsonResult.ok(taskService.checkPlanZones(
                param.getRouteId(), param.getTakeoffLng(), param.getTakeoffLat()));
    }

    /**
     * 派发前的禁飞区预检。理由同上，判的是「这条任务已经定下来的计划」。
     */
    @Operation(summary = "派发前的禁飞区预检")
    @RequirePermission("task:dispatch")
    @GetMapping("/{taskId}/zone-check")
    public JsonResult<List<ZoneHitVO>> taskZoneCheck(@PathVariable("taskId") Long taskId) {
        return JsonResult.ok(taskService.checkTaskZones(taskId));
    }

    /**
     * 监控看板：当前所有执行中任务的位置与状态。
     *
     * <p>权限 {@code task:list}（管理侧）。飞手看的是「我的任务」，另一条路。
     */
    @Operation(summary = "监控看板数据")
    @RequirePermission("task:list")
    @GetMapping("/monitor")
    public JsonResult<List<TaskMonitorVO>> monitor() {
        return JsonResult.ok(taskService.monitorTasks());
    }

    /**
     * 飞手端：我这条任务的地图数据（计划航线 + 起降点 + 实际轨迹 + 打卡记录）。
     *
     * <p>为什么不复用 {@code GET /task/{id}}：那个是管理侧的详情（带流水、告警、
     * 报告），权限是 {@code task:list} —— 飞手根本没有。飞手要的是「我执行时需要的那些」，
     * 权限是 {@code task:execute} 加上「这条任务是不是派给我的」。
     */
    @Operation(summary = "飞手端任务地图")
    @RequirePermission("task:execute")
    @GetMapping("/my/{taskId}/map")
    public JsonResult<TaskMapVO> myTaskMap(@PathVariable("taskId") Long taskId) {
        return JsonResult.ok(taskService.getMyTaskMap(taskId));
    }

    /**
     * 到点打卡。
     *
     * <p>偏差由服务端算（目标点取自任务的航线快照），客户端传的坐标只用来算这个数。
     * 同一条（任务 + 航点 + 时刻）重复提交会被忽略并返回 {@code duplicated=true} ——
     * 离线补传重发就是这样，客户端据此把它从本地队列里去掉。
     */
    @Operation(summary = "到点打卡")
    @RequirePermission("task:execute")
    @PostMapping("/checkin")
    public JsonResult<TaskCheckinVO> checkIn(@RequestBody TaskCheckinParam param) {
        TaskCheckinVO result = taskService.checkIn(param);
        log.info("Controller [{}] 到点打卡，taskId={}，航点={}",
                UserContext.threadLabel(),
                param == null ? null : param.getTaskId(),
                param == null ? null : param.getSeq());
        return JsonResult.ok(result.getDuplicated() ? "这条打卡已经记过了" : "打卡成功", result);
    }

    /**
     * 任务列表（监控页）。
     *
     * <p>查询条件：关键字（编号 / 名称）、状态、优先级、飞手；都不传就是查全部。
     */
    @Operation(summary = "任务列表")
    @RequirePermission("task:list")
    @GetMapping("/list")
    public JsonResult<PageResult<TaskVO>> list(TaskQueryParam param) {
        log.info("Controller [{}] 查询任务列表，条件={}", UserContext.threadLabel(), param);
        return JsonResult.ok(taskService.pageTasks(param));
    }

    /**
     * 任务详情，含状态流转流水（详情页的时间轴用它渲染）。
     */
    @Operation(summary = "任务详情")
    @RequirePermission("task:list")
    @GetMapping("/{id}")
    public JsonResult<TaskDetailVO> detail(@PathVariable Long id) {
        return JsonResult.ok(taskService.getTaskDetail(id));
    }

    /**
     * 我的任务（飞手端）。
     *
     * <h2>为什么不让飞手直接用 /task/list</h2>
     * 飞手没有 {@code task:list}（那是给管理员看全部任务用的），
     * 而且 {@code /task/list} 支持传 {@code pilotId} —— 飞手要是能传，
     * 就能查别人的任务，属于<b>水平越权</b>。
     * 所以这里单独开一个接口，飞手 ID <b>只从登录态取，不接受前端传入</b>：
     * 前端就算在参数里塞了 pilotId，也会被服务端覆盖掉。
     *
     * <p>权限给 {@code task:execute} —— 飞手有，管理员也有（方便用管理员账号排查飞手看到的内容）。
     */
    @Operation(summary = "我的任务")
    @RequirePermission("task:execute")
    @GetMapping("/my")
    public JsonResult<PageResult<TaskVO>> myTasks(TaskQueryParam param) {
        log.info("Controller [{}] 查询我的任务，条件={}", UserContext.threadLabel(), param);
        return JsonResult.ok(taskService.pageMyTasks(param));
    }

    // ------------------------------------------------------------------
    // 飞手端：这条任务上的告警（只看不写）
    // ------------------------------------------------------------------

    /**
     * 我这条任务上的告警清单（飞手端任务卡片展开后）。
     *
     * <p><b>为什么放在任务模块而不是告警模块</b>：这个接口的判据是
     * 「这条任务是不是派给我的」，而那套规则（{@code requireMyTask}）
     * 住在任务模块。放到 {@code /alert/**} 下就得把那条规则再抄一份，
     * 或者把任务模块的内部方法公开出去 —— 两条路都比现在这条路差。
     *
     * <p>权限用 {@code task:execute}（飞手有），<b>不给 {@code alert:list}</b>：
     * 飞手只能看自己任务上的告警，看不到全库的告警。
     */
    @Operation(summary = "我这条任务上的告警")
    @RequirePermission("task:execute")
    @GetMapping("/alert/list")
    public JsonResult<PageResult<AlertVO>> myTaskAlerts(AlertQueryParam param) {
        log.info("Controller [{}] 查询任务告警，taskId={}，条件={}",
                UserContext.threadLabel(), param == null ? null : param.getTaskId(), param);
        Long taskId = param == null ? null : param.getTaskId();
        if (taskId == null) {
            // 不兜底成「查全部」：那会让飞手在漏传参数时看到别人的数据。
            // 宁可报错让他带上任务 id
            throw new BusinessException("请指定任务");
        }
        return JsonResult.ok(taskService.pageMyTaskAlerts(taskId, param));
    }

    /**
     * 现场异常上报（飞手端）。
     *
     * <p>只接受人工上报类那 4 条编码 —— 传设备类的（如 {@code bat.critical}）会被拒，
     * 否则飞手能伪造一条看起来像设备实测的告警。
     * 可以带照片：附件 id 要先通过 {@code /task/attachment/upload} 拿到。
     */
    @Operation(summary = "现场异常上报")
    @RequirePermission("task:execute")
    @PostMapping("/alert/report")
    public JsonResult<AlertVO> reportOnsiteIssue(@RequestBody TaskAlertReportParam param) {
        log.info("Controller [{}] 现场异常上报，taskId={}，编码={}，附件 {} 个",
                UserContext.threadLabel(),
                param == null ? null : param.getTaskId(),
                param == null ? null : param.getAlertCode(),
                param == null || param.getAttachmentIds() == null ? 0 : param.getAttachmentIds().size());
        return JsonResult.ok("已上报，管理员会尽快处理",
                taskService.reportOnsiteIssue(param));
    }

    // ------------------------------------------------------------------
    // 报告线：任务收尾（完成 / 失败）与报告
    // ------------------------------------------------------------------

    /**
     * 完成任务并提交报告。
     *
     * <p><b>一个接口收三条线</b>：状态 → 已完成、设备回待命、报告落库。
     * 不做成「先提交报告、再点完成」两步 —— 那会造出「任务还在执行中、
     * 报告却已提交」的中间态，而那个状态下没有任何人需要做任何事。
     * 见 {@code docs/任务收尾与执行报告.md} 2.2 节。
     *
     * <p>权限 {@code task:execute} + 归属校验（只允许收尾派给自己的任务）。
     * 附件先通过 {@code /task/attachment/upload} 传好，把 id 放进 {@code attachmentIds}。
     */
    @Operation(summary = "完成任务并提交报告")
    @RequirePermission("task:execute")
    @PostMapping("/finish")
    public JsonResult<TaskVO> finish(@RequestBody TaskFinishParam param) {
        log.info("Controller [{}] 完成任务，taskId={}，结论={}，附件 {} 个",
                UserContext.threadLabel(),
                param == null ? null : param.getTaskId(),
                param == null ? null : param.getResult(),
                param == null || param.getAttachmentIds() == null ? 0 : param.getAttachmentIds().size());
        return JsonResult.ok("任务已完成", taskService.finishTask(param));
    }

    /**
     * 标记执行失败。
     *
     * <p>没有这条出口，飞手遇到「飞机飞一半故障了」只能谎报完成，
     * 数据里就再也分不出「真完成」和「失败了但走完成」。
     * 与完成同一套逻辑，只多一条「必须写失败原因」。
     */
    @Operation(summary = "标记执行失败")
    @RequirePermission("task:execute")
    @PostMapping("/fail")
    public JsonResult<TaskVO> fail(@RequestBody TaskFinishParam param) {
        log.info("Controller [{}] 标记执行失败，taskId={}",
                UserContext.threadLabel(), param == null ? null : param.getTaskId());
        return JsonResult.ok("已标记为执行失败", taskService.failTask(param));
    }

    /**
     * 任务轨迹（地图上那条线）。
     *
     * <p>数据不是新采的：设备每秒报的经纬度早就存在证据表的**报文原文**里，
     * 这个接口把它取出来、抽稀、转成地图能直接用的坐标系。
     *
     * <p><b>返回的坐标是 GCJ-02</b>（高德/腾讯那一套），不是库里存的 WGS84 ——
     * 报文里带 {@code coordinateSystem} 说明这一点，别拿它和库里的值比。
     *
     * <p>权限用 {@code task:list}：轨迹里有位置信息，和任务详情一个敏感度。
     */
    @Operation(summary = "任务轨迹")
    @RequirePermission("task:list")
    @GetMapping("/{taskId}/track")
    public JsonResult<TaskTrackVO> track(@PathVariable("taskId") Long taskId) {
        log.info("Controller [{}] 查询任务轨迹，taskId={}", UserContext.threadLabel(), taskId);
        return JsonResult.ok(taskTrackService.getTrack(taskId));
    }

    /**
     * 任务报告详情（手填部分 + 系统自动汇总）。
     *
     * <h2>为什么没有 {@code @RequirePermission}</h2>
     * 规则是「管理侧（{@code task:list}）能看任意任务的报告，飞手只能看自己那条」——
     * 一个「或」。而 {@code @RequirePermission} 的多个值是「同时具备」（AND），
     * 表达不了「或」。所以放行到服务层判，判据仍然是权限码本身。
     * 附件下载用的是同一条规则、同一个理由。
     */
    @Operation(summary = "任务报告详情")
    @GetMapping("/report/{taskId}")
    public JsonResult<TaskReportVO> report(@PathVariable("taskId") Long taskId) {
        log.info("Controller [{}] 查询任务报告，taskId={}", UserContext.threadLabel(), taskId);
        return JsonResult.ok(taskService.getTaskReport(taskId));
    }

    /**
     * 修订报告 —— <b>只动报告，不动任务状态</b>。
     *
     * <p>报告是交付物，事后发现写错、被要求补充、客户提异议都要能改；
     * 而「改过必须看得出来」是多版本存在的唯一理由，所以每改一次多一个版本。
     *
     * <p>与「完成任务」是两个接口，因为它们的语义不同：
     * 那个是收尾（动状态、动资源），这个是改一份已经交上去的文档。
     * 混在一起会让「这次提交会不会把任务状态也改了」变成要读实现才知道的事。
     *
     * <p>权限与「看报告」同一条（管理侧或本人），所以同样放行到服务层判。
     */
    @Operation(summary = "修订报告")
    @PostMapping("/report/revise")
    public JsonResult<TaskReportVO> reviseReport(@RequestBody TaskReportReviseParam param) {
        log.info("Controller [{}] 修订报告，taskId={}，结论={}",
                UserContext.threadLabel(),
                param == null ? null : param.getTaskId(),
                param == null ? null : param.getResult());
        return JsonResult.ok("报告已修订，旧版本仍保留", taskService.reviseReport(param));
    }

    // ------------------------------------------------------------------
    // 飞手端：附件上传与下载
    // ------------------------------------------------------------------

    /**
     * 上传附件（现场照片、飞行日志）。
     *
     * <p>multipart 表单：{@code file} + {@code taskId} + 可选 {@code description}。
     * 返回值里带附件 id，前端拿着它去提交上报。
     *
     * <p><b>注意全局 multipart 上限</b>：{@code application.yml} 里配的是
     * 单文件 500MB（按最大的那类设），细分的照片 20MB / 视频 500MB / 日志 200MB
     * 在业务代码里判 —— Spring 的 multipart 配置是全局的，分不了类型。
     */
    @Operation(summary = "上传附件")
    @RequirePermission("task:execute")
    @PostMapping("/attachment/upload")
    public JsonResult<TaskAttachmentVO> uploadAttachment(
            @RequestParam("file") MultipartFile file,
            @RequestParam("taskId") Long taskId,
            @RequestParam(value = "description", required = false) String description) {
        log.info("Controller [{}] 上传附件，taskId={}，文件名={}，大小={} 字节",
                UserContext.threadLabel(), taskId,
                file == null ? null : file.getOriginalFilename(),
                file == null ? 0 : file.getSize());
        return JsonResult.ok("上传成功",
                taskService.uploadTaskAttachment(taskId, file, description));
    }

    /**
     * 下载附件。
     *
     * <h2>为什么这个方法上没有 {@code @RequirePermission}</h2>
     * 规则是「<b>有 {@code task:list} 就能下任意任务的，否则必须是这条任务的飞手</b>」——
     * 一个「或」。而 {@code @RequirePermission} 的多个值是「同时具备」（AND），
     * 表达不了「或」。所以这里只要求登录（拦器会校验 token），
     * 谁能下由 Service 判。判据仍然是权限码本身，不是角色名。
     *
     * <h2>为什么走接口而不是静态资源映射</h2>
     * 静态映射等于「拿到 URL 就能下」，设备照片与飞行日志是要过权限的。
     */
    @Operation(summary = "下载附件")
    @GetMapping("/attachment/{id}/download")
    public void downloadAttachment(@PathVariable("id") Long id,
                                   HttpServletResponse response) throws IOException {
        log.info("Controller [{}] 下载附件，attachmentId={}", UserContext.threadLabel(), id);

        AttachmentService.LoadedAttachment loaded = null;
        try {
            loaded = taskService.loadTaskAttachment(id);
            writeAttachment(loaded, response);
        } finally {
            // 流式写完之后把句柄关掉。放在 finally 里是因为：
            // 中途写响应失败（客户端断了）也必须释放，否则文件句柄会慢慢泄漏
            closeQuietly(loaded);
        }
    }

    // ------------------------------------------------------------------
    // 下载的内部实现
    // ------------------------------------------------------------------

    /**
     * 把附件流式写进响应。
     *
     * <p><b>为什么是流式而不是 {@code readAllBytes()}</b>：附件里最大的那类是
     * 飞行日志与视频（上限 200MB / 500MB）。全读进内存意味着一次并发下载就能
     * 把堆撑爆 —— 而且这种事故看起来像「莫名其妙的 OOM」，最难查。
     *
     * <p>响应头三件事：
     * <ul>
     *   <li>{@code Content-Type} 取自库里存的 {@code mime_type}（按扩展名查我们自己的表，
     *       不是上传者说了算的），认不出来退回二进制流</li>
     *   <li>{@code Content-Disposition: attachment} —— <b>强制下载而不是当场打开</b>。
     *       就算有人把 html 改名成 jpg 传上来，浏览器也不会当网页渲染</li>
     *   <li>文件名用 RFC 5987 的 {@code filename*=UTF-8''} 写法，
     *       与 {@code DeviceController.prepareExcelResponse} 一致，中文名不乱码</li>
     * </ul>
     */
    private void writeAttachment(AttachmentService.LoadedAttachment loaded,
                                 HttpServletResponse response) throws IOException {
        TaskAttachmentPO meta = loaded.meta();
        Resource resource = loaded.resource();

        String mimeType = StringUtils.hasText(meta.getMimeType())
                ? meta.getMimeType()
                : MediaType.APPLICATION_OCTET_STREAM_VALUE;
        response.setContentType(mimeType);
        response.setContentLengthLong(resource.contentLength());
        response.setHeader("Content-Disposition",
                "attachment; filename*=UTF-8''" + URLEncoder.encode(
                        meta.getFileName(), StandardCharsets.UTF_8));

        try (InputStream in = resource.getInputStream();
             OutputStream out = response.getOutputStream()) {
            in.transferTo(out);
            out.flush();
        }
    }

    /**
     * 关掉流式资源的句柄。
     *
     * <p>{@code FileSystemResource} 每次 {@code getInputStream()} 就新开一个，
     * 上面 try-with-resources 已经关过；这里是兜底：
     * 万一在拿到 InputStream 之前就抛了（比如文件不在），
     * 也不能让资源悬着 —— 关不掉只记日志，不让它盖住真正的错误。
     */
    private void closeQuietly(AttachmentService.LoadedAttachment loaded) {
        if (loaded == null || !(loaded.resource() instanceof Closeable closeable)) {
            return;
        }
        try {
            closeable.close();
        } catch (IOException e) {
            log.debug("关闭附件资源句柄失败（忽略）", e);
        }
    }

    /**
     * 开始执行（飞手端）。
     *
     * <p>权限用 {@code task:execute} —— 飞手有，管理员也有。
     * 但光有权限还不够：Service 里还会校验<b>这是不是派给你的任务</b>，
     * 所以管理员虽然权限齐全，也不能替飞手点「开始执行」
     * （要改状态应该走撤回 / 重新指派）。
     */
    @Operation(summary = "开始执行")
    @RequirePermission("task:execute")
    @PostMapping("/start")
    public JsonResult<TaskVO> start(@RequestBody TaskStartParam param) {
        Long taskId = param == null ? null : param.getTaskId();
        log.info("Controller [{}] 开始执行，taskId={}", UserContext.threadLabel(), taskId);
        return JsonResult.ok("已开始执行", taskService.startTask(taskId));
    }

    /**
     * 拒绝执行（飞手端）。
     *
     * <p>拒绝后任务退回「待派发」并释放飞手与设备，管理员可以直接重新指派 ——
     * 效果与「撤回派发」一样，但流水里记的是 {@code refuse}，
     * 时间轴上一眼能看出「是飞手不接」而不是「管理员反悔」。
     *
     * <p>操作成功后会给所有能重新指派的人推一条 {@code taskRefused} 通知。
     */
    @Operation(summary = "拒绝执行")
    @RequirePermission("task:execute")
    @PostMapping("/refuse")
    public JsonResult<TaskVO> refuse(@RequestBody TaskRefuseParam param) {
        log.info("Controller [{}] 拒绝执行，taskId={}", UserContext.threadLabel(),
                param == null ? null : param.getTaskId());
        return JsonResult.ok("已拒绝执行，任务已退回待派发", taskService.refuseTask(param));
    }

    /**
     * 派发任务：指定飞手与设备。
     *
     * <p>为什么返回整个任务而不是只回一句成功：派发会改状态、飞手、设备三项，
     * 前端拿到最新对象就能直接更新那一行，不用再发一次查询。
     */
    @Operation(summary = "派发任务")
    @RequirePermission("task:dispatch")
    @PostMapping("/dispatch")
    public JsonResult<TaskVO> dispatch(@RequestBody TaskDispatchParam param) {
        log.info("Controller [{}] 派发任务，taskId={}，pilotId={}，droneId={}",
                UserContext.threadLabel(),
                param == null ? null : param.getTaskId(),
                param == null ? null : param.getPilotId(),
                param == null ? null : param.getDroneId());
        return JsonResult.ok("派发成功", taskService.dispatchTask(param));
    }

    /**
     * 撤回派发：任务退回「待派发」，指派关系清空。
     */
    @Operation(summary = "撤回派发")
    @RequirePermission("task:dispatch")
    @PostMapping("/withdraw")
    public JsonResult<TaskVO> withdraw(@RequestBody TaskWithdrawParam param) {
        log.info("Controller [{}] 撤回派发，taskId={}", UserContext.threadLabel(),
                param == null ? null : param.getTaskId());
        return JsonResult.ok("已撤回派发", taskService.withdrawDispatch(param));
    }

    /**
     * 派发弹窗的下拉数据（可派的飞手 + 可用的设备）。
     */
    @Operation(summary = "派发下拉数据")
    @RequirePermission("task:dispatch")
    @GetMapping("/dispatch-options")
    public JsonResult<TaskDispatchOptionsVO> dispatchOptions() {
        return JsonResult.ok(taskService.getDispatchOptions());
    }
}
