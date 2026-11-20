package cn.tedu.drone.task.service.impl;

import cn.tedu.drone.common.BusinessException;
import cn.tedu.drone.common.CoordinateUtil;
import cn.tedu.drone.common.GeoDistance;
import cn.tedu.drone.common.GeoInput;
import cn.tedu.drone.common.LoginUser;
import cn.tedu.drone.common.PageResult;
import cn.tedu.drone.common.UserContext;
import cn.tedu.drone.device.pojo.DeviceVO;
import cn.tedu.drone.device.service.DeviceService;
import cn.tedu.drone.route.pojo.RoutePointPO;
import cn.tedu.drone.route.pojo.RouteSnapshot;
import cn.tedu.drone.route.pojo.RouteVO;
import cn.tedu.drone.route.service.RouteService;
import cn.tedu.drone.task.dao.TaskCheckinDao;
import cn.tedu.drone.task.dao.TaskDao;
import cn.tedu.drone.task.dao.TaskEvidenceDao;
import cn.tedu.drone.task.dao.TaskLogDao;
import cn.tedu.drone.task.dao.TaskReportDao;
import cn.tedu.drone.task.pojo.AlertPO;
import cn.tedu.drone.task.pojo.AlertQueryParam;
import cn.tedu.drone.task.pojo.AlertVO;
import cn.tedu.drone.task.pojo.EvidenceState;
import cn.tedu.drone.task.pojo.TaskAlertCount;
import cn.tedu.drone.task.pojo.TaskAlertReportParam;
import cn.tedu.drone.task.pojo.TaskAttachmentPO;
import cn.tedu.drone.task.pojo.TaskAttachmentVO;
import cn.tedu.drone.task.pojo.TaskCheckinPO;
import cn.tedu.drone.task.pojo.TaskCheckinParam;
import cn.tedu.drone.task.pojo.TaskCheckinVO;
import cn.tedu.drone.task.pojo.TaskCreateParam;
import cn.tedu.drone.task.pojo.TaskDetailVO;
import cn.tedu.drone.task.pojo.TaskDispatchOptionsVO;
import cn.tedu.drone.task.pojo.TaskDispatchParam;
import cn.tedu.drone.task.pojo.TaskEvidenceSummary;
import cn.tedu.drone.task.pojo.TaskFinishParam;
import cn.tedu.drone.task.pojo.TaskLogPO;
import cn.tedu.drone.task.pojo.TaskLivePointVO;
import cn.tedu.drone.task.pojo.TaskLogVO;
import cn.tedu.drone.task.pojo.TaskMapVO;
import cn.tedu.drone.task.pojo.TaskMonitorVO;
import cn.tedu.drone.task.pojo.TaskPO;
import cn.tedu.drone.task.pojo.TaskQueryParam;
import cn.tedu.drone.task.pojo.TaskRefuseParam;
import cn.tedu.drone.task.pojo.TaskReportPO;
import cn.tedu.drone.task.pojo.TaskReportReviseParam;
import cn.tedu.drone.task.pojo.TaskReportVO;
import cn.tedu.drone.task.pojo.TaskStatusFlow;
import cn.tedu.drone.task.pojo.TaskVO;
import cn.tedu.drone.task.pojo.TaskWithdrawParam;
import cn.tedu.drone.task.service.AlertService;
import cn.tedu.drone.task.service.AttachmentService;
import cn.tedu.drone.task.service.TaskService;
import cn.tedu.drone.task.service.TaskTrackService;
import cn.tedu.drone.task.ws.TaskNotifier;
import cn.tedu.drone.user.dao.UserDao;
import cn.tedu.drone.user.pojo.UserPO;
import cn.tedu.drone.user.service.UserService;
import cn.tedu.drone.zone.pojo.ZoneCheckInput;
import cn.tedu.drone.zone.pojo.ZoneCheckPoint;
import cn.tedu.drone.zone.pojo.ZoneHitVO;
import cn.tedu.drone.zone.service.NoFlyZoneService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 任务业务实现。
 *
 * <h2>创建任务做了六件事</h2>
 * <ol>
 *   <li>校验必填与格式（含优先级白名单、时间区间）</li>
 *   <li>生成任务编号 {@code TK-yyyyMMdd-序号}</li>
 *   <li>落库，状态固定 {@code pending}</li>
 *   <li>创建人从登录态取（不接受前端传）</li>
 *   <li>写一条 {@code create} 流水</li>
 *   <li>主表与流水在同一个事务里</li>
 * </ol>
 *
 * <h2>派发任务做了五件事</h2>
 * <ol>
 *   <li>状态机校验：只有「待派发」能派发（见 {@link TaskStatusFlow}）</li>
 *   <li>资源校验：飞手要能飞（存在、启用、有飞手角色），设备要能用（存在、待命）</li>
 *   <li>冲突校验：飞手在该任务时间段内不能已有别的任务</li>
 *   <li>占用资源：设备「待命 → 已派出」，飞手「空闲 → 作业中」。撤回时反向恢复</li>
 *   <li>更新主表并写一条 {@code dispatch} 流水，两者同一事务</li>
 * </ol>
 *
 * <h2>状态联动为什么用「比较并交换」而不是直接改</h2>
 * 「先查状态再改」中间有缝隙：查完是 idle，改之前别人把它设成了 maintenance，
 * 你的写就把人工设置覆盖了。所以两处联动都走
 * {@code changeStatusIfMatch(id, 预期值, 新值)}，判断和写入在同一条 SQL 里完成。
 * 设备那步失败要中止派发（设备是可用性的判据），飞手那步失败只放过
 * （在线状态是展示位，且「已停飞」这类人工标记不该被业务流程冲掉）。
 */
@Slf4j
@Service
public class TaskServiceImpl implements TaskService {

    /** 允许的优先级 */
    private static final Set<String> VALID_PRIORITIES = Set.of("P0", "P1", "P2", "P3");

    /** 编号前缀与日期格式：TK-20260918-001 */
    private static final String TASK_NO_PREFIX = "TK-";
    private static final DateTimeFormatter DATE_PART = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** 任务名称长度上限，与表结构保持一致 */
    private static final int MAX_NAME_LENGTH = 100;

    /**
     * 「能看所有任务」的权限码 —— 下载附件时用它区分两类人：
     * 有它的是管理侧（可以下任意任务的附件），没有的就只能是这条任务的飞手本人。
     *
     * <p>写成常量而不是散在代码里的字符串：同一个权限码在多个地方出现时，
     * 拼错一个字符不会有任何编译错误，只会在运行时悄悄变成「永远不满足」或「永远满足」。
     */
    private static final String PERMISSION_TASK_LIST = "task:list";

    /** 单次超时释放扫描的上限，一次别捞太多 */
    private static final int RELEASE_BATCH_SIZE = 200;

    /** 单次证据过期扫描的上限。和释放一样分页处理，不追求一轮扫完 */
    private static final int EVIDENCE_SCAN_BATCH_SIZE = 200;

    /** 系统操作人的展示名与角色，用于定时任务写的流水 */
    private static final String SYSTEM_OPERATOR_NAME = "系统";
    private static final String SYSTEM_OPERATOR_ROLE = "system";

    /** user 表里 status 为 "1" 表示账号可用 */
    private static final String USER_ENABLED = "1";

    /** user 表的在线状态：空闲 / 作业中。派发与撤回时联动它 */
    private static final String USER_IDLE = "idle";
    private static final String USER_BUSY = "busy";

    /** 能接任务的飞手角色编码 */
    private static final String PILOT_ROLE_CODE = "pilot";

    /**
     * 「已知晓禁飞区风险」时那段原因的长度要求。
     *
     * <p>下限 4 个字是刻意的：留痕里出现「知道了」「ok」这种等于没写，
     * 而事后对账时没人在场，唯一能读的就是这句话。
     * 上限 150 是给流转流水的 note 列（varchar(500)）留余量 ——
     * 那句里还会自动带上命中的区域名。
     */
    private static final int ZONE_REASON_MIN_LENGTH = 4;
    private static final int ZONE_REASON_MAX_LENGTH = 150;

    /** 看板上每条任务的航迹：先从库里取最近这么多原始点，再截到下面那个数 */
    private static final int TRAIL_RAW_LIMIT = 200;
    private static final int TRAIL_MAX_POINTS = 80;

    /**
     * 打卡偏差超过这个值就标出来（米）。
     *
     * <p>200 米是设计文档 7.2 给的值：手机定位本身有几米到几十米的误差，
     * 而一个巡检点周围 200 米通常已经是「另一个地方」了。
     * ⚠️ 阈值**只在这里定义一次**：前端要提示用户确认时用的是服务端返回的这个标记，
     * 自己再写一个阈值就会出现「页面提示了、服务端说不远」这种对不上。
     */
    private static final int CHECKIN_FAR_AWAY_METERS = 200;

    /** 收到时刻比打卡时刻晚这么多（分钟）就算「离线补传」——界面要把它和实时打卡分开显示 */
    private static final long CHECKIN_LATE_MINUTES = 10;

    private static final int CHECKIN_REMARK_MAX_LENGTH = 200;

    /** 报告结论：成功 / 部分完成 / 失败。前两个能由飞手填，第三个由服务端写死 */
    private static final Set<String> FINISH_RESULTS = Set.of("success", "partial");
    private static final String RESULT_FAILED = "failed";

    /** 结论的中文名。表里只存编码，展示文案在这一处 —— 和别的字典同一个思路 */
    private static final Map<String, String> RESULT_LABELS = Map.of(
            "success", "成功",
            "partial", "部分完成",
            "failed", "失败");

    /** 单次飞行的时长上限（小时）。比这更长的填法，基本是手滑（比如把 1.25 填成 12.5 或者 125） */
    private static final BigDecimal MAX_FLIGHT_HOURS = BigDecimal.valueOf(24);

    /** drone 表里能出机的状态 */
    private static final String DRONE_IDLE = "idle";

    /** 设备被派出后的状态：已派出（派了任务但还没起飞） */
    private static final String DRONE_ASSIGNED = "assigned";

    /** 设备真正起飞后的状态：飞行中。派发只是「已派出」，开始执行才置这个 */
    private static final String DRONE_FLYING = "flying";

    /** 报错文案里展示任务时间段用，精确到分钟就够了 */
    private static final DateTimeFormatter WINDOW_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /** 只显示时间部分的格式，用于「同一天」的时间段简写 */
    private static final DateTimeFormatter TIME_ONLY_FORMATTER =
            DateTimeFormatter.ofPattern("HH:mm");

    /**
     * 超时释放的宽限期（分钟）。
     *
     * <p>不能一到计划结束时间就释放 —— 任务延后半小时开工是常事。
     * 走配置而不是写死，是因为这个值多大合适完全看业务节奏。
     */
    @Value("${drone.task.auto-release.grace-minutes:120}")
    private int graceMinutes;

    @Autowired
    private TaskDao taskDao;

    @Autowired
    private TaskLogDao taskLogDao;

    /** 只为把创建人的真实姓名查出来存进冗余字段，省得列表页每次 join */
    @Autowired
    private UserDao userDao;

    /** 校验飞手有没有飞手角色时用它查角色；任务模块不重复实现一份 */
    @Autowired
    private UserService userService;

    /** 查设备状态用。走 Service 而不是直接拿 DeviceDao，免得跨模块读写别人的表 */
    @Autowired
    private DeviceService deviceService;

    /** 派发成功后给飞手推一条实时通知（走 WebSocket，见 task/ws 包） */
    @Autowired
    private TaskNotifier taskNotifier;

    /**
     * 飞手端要看到「这条任务上挂着哪些告警」，现场上报也走它。
     *
     * <p>任务模块只调它的接口，不直接碰 {@code task_alert} ——
     * 「告警表长什么样」这件事只该被告警模块知道。
     */
    @Autowired
    private AlertService alertService;

    /** 现场照片的上传与读取（存哪、怎么校验都在附件模块里） */
    @Autowired
    private AttachmentService attachmentService;

    /**
     * 到点打卡。
     *
     * <p>它是「飞手说自己去过哪」的唯一记录（弱依据），
     * 和证据表是两套东西：证据是设备/系统的客观数据，打卡是人的自述。
     * 混在一起存会让「这条数据可不可信」变得没法回答。
     */
    @Autowired
    private TaskCheckinDao taskCheckinDao;

    /** 飞手端的任务地图要带上「自己飞过哪」，轨迹的取法复用它（抽稀 + 转坐标系都在里面） */
    @Autowired
    private TaskTrackService taskTrackService;

    /** 报告：多版本，所以按任务查必须带 is_latest（方法名里就带 Latest） */
    @Autowired
    private TaskReportDao taskReportDao;

    /** 报告里「自动汇总」那一半的数据来源（证据条数、时间范围、最低电量和最大高度） */
    @Autowired
    private TaskEvidenceDao taskEvidenceDao;

    /**
     * 航线：创建任务时取快照、看详情时把快照读回来。
     *
     * <p>任务模块只调它的接口，不直接碰 {@code route} / {@code route_point} ——
     * 「快照长什么样」这件事只该被航线模块知道（见 {@code RouteService} 的说明）。
     */
    @Autowired
    private RouteService routeService;

    /**
     * 禁飞区：创建与派发时做静态校验（软拦 + 留痕）。
     *
     * <p>任务模块负责回答「这条任务打算飞哪些点」（起降点 + 航线航点），
     * 禁飞区模块负责回答「这些点撞上哪块区域了」—— 各管各的那一半。
     * 校验的输入必须是 <b>WGS84</b>（库里那一套），见 {@code ZoneCheckInput}。
     */
    @Autowired
    private NoFlyZoneService noFlyZoneService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TaskVO createTask(TaskCreateParam param) {
        validate(param);

        LoginUser current = UserContext.get();
        Long operatorId = current == null ? null : current.getId();

        // 选了航线就取一次：既拿到「存在的确认」（不存在会直接抛异常，
        // 任务建不出来 —— 否则会留下一条引用了不存在航线的任务，地图上什么都画不出），
        // 也拿到名称等信息用来拼一句人看的摘要
        RouteVO route = (param.getRouteId() == null) ? null : routeService.getRoute(param.getRouteId());

        // 关键的一步：把**当时**那份航线整段复制下来存进任务。
        // 之后模板怎么改，这条任务画出来的永远是它自己那份（快照的意义见 RouteSnapshot）
        String routeSnapshot = (route == null) ? null : routeService.snapshotJsonOf(route.getId());

        // 起降点：图上拾取的是 GCJ-02，入库前转回 WGS84（与点位、航线同一条规则）
        double[] takeoffWgs = takeoffToWgs84(param.getTakeoffLng(), param.getTakeoffLat());
        Double takeoffLng = (takeoffWgs == null) ? null : takeoffWgs[0];
        Double takeoffLat = (takeoffWgs == null) ? null : takeoffWgs[1];

        TaskPO task = new TaskPO()
                .setTaskName(param.getTaskName().trim())
                .setPriority(param.getPriority())
                // 状态写死：任何试图在创建时指定状态的行为都不存在入口
                .setStatus(TaskStatusFlow.PENDING)
                .setPlanStartTime(param.getPlanStartTime())
                .setPlanEndTime(param.getPlanEndTime())
                .setRouteInfo(resolveRouteInfo(param, route))
                .setDescription(param.getDescription())
                .setRouteId(param.getRouteId())
                .setRouteSnapshot(routeSnapshot)
                .setTakeoffLng(takeoffLng)
                .setTakeoffLat(takeoffLat)
                .setTakeoffName(param.getTakeoffName())
                .setCreateBy(operatorId)
                .setCreateByName(resolveUserName(operatorId, current));

        // 禁飞区静态校验：拿「这条航线 + 这个起降点」去比生效中的区域。
        // 命中就拦下 —— 除非调用方明确表示已知晓风险（软拦，见 TaskCreateParam）
        List<ZoneHitVO> zoneHits = noFlyZoneService.check(
                zoneCheckInput(routeService.parseSnapshot(routeSnapshot), takeoffWgs));
        assertZoneAcceptable(zoneHits, param.getAcknowledgedZones(), param.getZoneReason());

        insertWithGeneratedNo(task);

        // 顺手写流水：任务详情页的时间轴从创建那一刻起就是完整的。
        // 命中过禁飞区的话，把「当时提示了哪几块区域 + 人为什么还是建了」也写进去 ——
        // 这段留痕是事后唯一能证明「系统提示过」的东西
        String createNote = appendNote("创建任务", zoneNote(zoneHits, param.getZoneReason()));
        writeLog(task.getId(), TaskStatusFlow.ACTION_CREATE, null, TaskStatusFlow.PENDING, createNote);

        log.info("创建任务成功，id={}，编号={}，名称={}", task.getId(), task.getTaskNo(), task.getTaskName());

        // 回查一次再转 VO：create_time / update_time 是 MySQL 用 DEFAULT CURRENT_TIMESTAMP
        // 生成的，插入时内存里的 PO 上这两个字段还是 null。
        // 不回查的话，前端刚创建完看到的「创建时间」是空的，得刷新一下才有值。
        return toTaskVO(taskDao.selectById(task.getId()));
    }

    @Override
    public PageResult<TaskVO> pageTasks(TaskQueryParam param) {
        TaskQueryParam query = (param == null) ? new TaskQueryParam() : param;

        long total = taskDao.countByCondition(query);
        // 总数是 0 就没必要再查一次列表；PO 到 VO 的转换顺手在流里做掉
        List<TaskVO> list = (total == 0)
                ? List.of()
                : taskDao.selectByCondition(query).stream().map(this::toTaskVO).toList();

        return PageResult.of(list, total,
                query.getPage() == null ? 1 : query.getPage(),
                query.getLimit());
    }

    @Override
    public TaskDetailVO getTaskDetail(Long id) {
        TaskPO task = requireTask(id);

        return new TaskDetailVO()
                .setTask(toTaskVO(task))
                .setLogs(taskLogDao.selectByTaskId(id).stream().map(this::toLogVO).toList())
                // 告警按「task_id 或 设备+时间窗」聚合，见 AlertService.listAlertsInTaskWindow
                .setAlerts(alertService.listAlertsInTaskWindow(task))
                // 计划航线取自**这条任务自己的快照**，不是当前的航线模板 ——
                // 模板后来被改或被删，这条任务的「当时航线」都不该跟着变
                .setPlannedRoute(routeService.fromSnapshot(task.getRouteSnapshot()))
                // 现场打卡：飞手说自己去过哪。和管理侧的告警一样，详情页要一次给全
                .setCheckins(listCheckins(id));
    }

    @Override
    public PageResult<TaskVO> pageMyTasks(TaskQueryParam param) {
        LoginUser current = UserContext.get();
        if (current == null) {
            // 正常走不到这里：能调这个接口就说明拦截器已经放行并往口袋里放过登录人。
            // 但显式报错比后面 NPE 强，也让「这个接口必须登录」这件事在代码里看得见
            throw new BusinessException(401, "未登录，请先登录");
        }

        TaskQueryParam query = (param == null) ? new TaskQueryParam() : param;
        // 关键的一行：以登录态里的 id 为准，前端传的 pilotId 直接覆盖掉。
        // 「不接受前端传入」的落实方式就是覆盖 —— 若改成「传了就报错」，
        // 反而给了探测别人 id 是否存在 的反馈通道
        query.setPilotId(current.getId());

        PageResult<TaskVO> page = pageTasks(query);

        // 补角标。注意 toTaskVO 只是 BeanUtils.copyProperties、没有字段映射，
        // 所以这两个字段必须在这里显式填 —— 数据库里没有对应的列
        fillAlertCounts(page.getList());
        return page;
    }

    /**
     * 给当页任务补上「还有几条未处理的告警」。
     *
     * <p>两条讲究：
     * <ul>
     *   <li><b>一次查完整页</b>：一页 10 条任务，逐条去数就是 10 次往返（N+1）</li>
     *   <li><b>查不到的按 0 处理</b>：统计 SQL 只返回「有未处理告警」的任务，
     *       这种「不返回就是零」的约定比让 SQL 去 {@code LEFT JOIN} 造全量行更省事，
     *       也避免把 {@code SUM(CASE ...)} 在无匹配行时返回 NULL 的坑带进来</li>
     * </ul>
     */
    private void fillAlertCounts(List<TaskVO> tasks) {
        if (tasks.isEmpty()) {
            return;
        }
        Map<Long, TaskAlertCount> counts = alertService.countOpenAlertsByTaskIds(
                tasks.stream().map(TaskVO::getId).toList());

        for (TaskVO task : tasks) {
            TaskAlertCount count = counts.get(task.getId());
            task.setAlertCount(count == null ? 0 : count.getTotal());
            task.setCriticalAlertCount(count == null ? 0 : count.getCriticalCount());
        }
    }

    // ------------------------------------------------------------------
    // 监控看板与现场打卡（地图接入方案 第五步）
    // ------------------------------------------------------------------

    @Override
    public List<TaskMonitorVO> monitorTasks() {
        List<TaskPO> executing = taskDao.selectExecuting();
        if (executing.isEmpty()) {
            // 一条在飞的都没有：连位置与航迹都不用查
            return List.of();
        }
        List<Long> taskIds = executing.stream().map(TaskPO::getId).toList();

        // 两次查询拿全所有任务的「当前位置」与「最近航迹」——
        // 逐条查就是 2N 次往返，而这个接口每几秒就会被调一次（看板在轮询）
        Map<Long, TaskLivePointVO> latest = taskEvidenceDao.selectLatestPositions(taskIds).stream()
                .collect(Collectors.toMap(TaskLivePointVO::getTaskId, point -> point, (a, b) -> a));
        Map<Long, List<TaskLivePointVO>> trails = new HashMap<>();
        for (TaskLivePointVO point : taskEvidenceDao.selectRecentTrails(taskIds, TRAIL_RAW_LIMIT)) {
            trails.computeIfAbsent(point.getTaskId(), key -> new ArrayList<>()).add(point);
        }
        Map<Long, TaskAlertCount> alertCounts = alertService.countOpenAlertsByTaskIds(taskIds);

        LocalDateTime now = LocalDateTime.now();
        List<TaskMonitorVO> board = new ArrayList<>(executing.size());
        for (TaskPO task : executing) {
            TaskAlertCount count = alertCounts.get(task.getId());
            // 超期由服务端判：拿本机时间去比，客户端时钟偏几分钟就会一会儿红一会儿不红
            boolean overdue = task.getPlanEndTime() != null && now.isAfter(task.getPlanEndTime());

            TaskMonitorVO row = new TaskMonitorVO()
                    .setTaskId(task.getId())
                    .setTaskNo(task.getTaskNo())
                    .setTaskName(task.getTaskName())
                    .setPriority(task.getPriority())
                    .setPilotName(task.getPilotName())
                    .setDroneCode(task.getDroneCode())
                    .setActualStartTime(task.getActualStartTime())
                    .setPlanEndTime(task.getPlanEndTime())
                    .setOverdue(overdue)
                    .setOverdueMinutes(overdue
                            ? Duration.between(task.getPlanEndTime(), now).toMinutes() : null)
                    .setAlertCount(count == null ? 0 : count.getTotal())
                    .setCriticalAlertCount(count == null ? 0 : count.getCriticalCount())
                    .setCurrent(toGcjLivePoint(latest.get(task.getId())))
                    .setTrail(toTrail(trails.get(task.getId())))
                    // 计划航线取**任务自己的快照**：模板后来改了，看板上也该显示当时那条
                    .setPlannedRoute(routeService.fromSnapshot(task.getRouteSnapshot()))
                    .setTakeoffName(task.getTakeoffName());

            if (task.getTakeoffLng() != null && task.getTakeoffLat() != null) {
                double[] gcj = CoordinateUtil.wgs84ToGcj02(task.getTakeoffLng(), task.getTakeoffLat());
                row.setTakeoffLng(gcj[0]).setTakeoffLat(gcj[1]);
            }
            board.add(row);
        }
        log.debug("监控看板：执行中 {} 条，其中 {} 条有位置信息",
                board.size(), board.stream().filter(row -> row.getCurrent() != null).count());
        return board;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TaskCheckinVO checkIn(TaskCheckinParam param) {
        if (param == null || param.getTaskId() == null) {
            throw new BusinessException("请选择要打卡的任务");
        }
        // 归属校验：只能给自己的任务打卡（与现场上报同一条规则）。
        // 不校验任务状态 —— 到点打卡这件事就算任务已经收尾了也该允许补记
        TaskPO task = requireMyTask(param.getTaskId());

        if (param.getSeq() == null) {
            throw new BusinessException("请选择要打卡的航点");
        }
        if (param.getObservedAt() == null) {
            // 它同时是「补传去重」的键之一，缺了就没法去重。
            // 不能悄悄用「现在」顶上：补传时「现在」每次都不同，同一条会被反复插进来
            throw new BusinessException("缺少打卡时刻（离线打卡补传时要带上当时那一刻）");
        }
        validateCheckinRemark(param.getRemark());
        if (!TaskCheckinParam.COORDINATE_SYSTEMS.contains(param.resolvedCoordinateSystem())) {
            // 不做「猜一个最像的」：猜错会让位置偏 500 米，而它看起来「差不多对」
            throw new BusinessException("坐标只能是："
                    + String.join(" / ", TaskCheckinParam.COORDINATE_SYSTEMS));
        }
        double[] input = GeoInput.require(param.getLng(), param.getLat(), "打卡位置");

        // 目标点只能从**任务自己的航线快照**里取。
        // 让客户端传目标坐标就等于让他决定「目标在哪」—— 对着自己脚下传一个，偏差永远是 0
        RoutePointPO target = findSnapshotPoint(
                routeService.parseSnapshot(task.getRouteSnapshot()), param.getSeq());
        if (target == null) {
            throw new BusinessException("这条任务上没有第 " + param.getSeq() + " 个航点，请刷新后重试");
        }

        double[] wgs = toWgs84(input, param.resolvedCoordinateSystem());
        // 偏差在**库里那套坐标（WGS84）**上算：目标点来自快照（WGS84），
        // 换算过来的打卡点也是 WGS84，两边同系。混进 GCJ-02 会凭空多出 500 米
        int deviation = (int) Math.round(GeoDistance.meters(
                target.getLng(), target.getLat(), wgs[0], wgs[1]));

        LoginUser current = UserContext.get();
        Long operatorId = current == null ? null : current.getId();
        TaskCheckinPO checkin = new TaskCheckinPO()
                .setTaskId(task.getId())
                .setSeq(param.getSeq())
                .setTargetLng(target.getLng())
                .setTargetLat(target.getLat())
                .setLng(wgs[0])
                .setLat(wgs[1])
                .setAccuracyM(param.getAccuracyM())
                .setDeviationM(deviation)
                .setObservedAt(param.getObservedAt())
                // reported_at 显式写：表上的默认值只在 SQL 里生效，插入后内存里的 PO 还是 null，
                // 而「是不是补传」正是拿它和 observed_at 比出来的
                .setReportedAt(LocalDateTime.now())
                .setReporterId(operatorId)
                .setReporterName(resolveUserName(operatorId, current))
                .setRemark(param.getRemark() == null ? null : param.getRemark().trim());

        if (Boolean.TRUE.equals(param.getDryRun())) {
            // 试算：算完就返回，不写库。客户端拿 farAway 决定要不要弹「确定到了吗」
            log.debug("打卡试算，taskId={}，航点={}，偏差={}米", task.getId(), param.getSeq(), deviation);
            return toCheckinVO(checkin).setDuplicated(false);
        }

        if (taskCheckinDao.insertIgnore(checkin) > 0) {
            log.info("现场打卡，taskId={}，航点={}，偏差={}米，打卡时刻={}，上报人={}",
                    task.getId(), param.getSeq(), deviation, param.getObservedAt(), checkin.getReporterName());
            return toCheckinVO(checkin).setDuplicated(false);
        }

        // 撞上唯一键（任务+航点+时刻）：同一次打卡之前已经落库了。
        // 离线补传重发就是这样 —— 不报错，但要告诉客户端「这条早就在了」，
        // 否则它的本地队列会一直以为没传成功、反复重试
        log.info("现场打卡重复提交，已忽略：taskId={}，航点={}，打卡时刻={}",
                task.getId(), param.getSeq(), param.getObservedAt());
        TaskCheckinPO existing = taskCheckinDao.selectByTaskId(task.getId()).stream()
                .filter(row -> param.getSeq().equals(row.getSeq())
                        && param.getObservedAt().equals(row.getObservedAt()))
                .findFirst().orElse(checkin);
        return toCheckinVO(existing).setDuplicated(true);
    }

    @Override
    public List<TaskCheckinVO> listCheckins(Long taskId) {
        if (taskId == null) {
            return List.of();
        }
        return taskCheckinDao.selectByTaskId(taskId).stream().map(this::toCheckinVO).toList();
    }

    @Override
    public TaskMapVO getMyTaskMap(Long taskId) {
        // requireMyTask 会抛异常：不是派给我的任务，连「它有没有航线」都不该知道
        TaskPO task = requireMyTask(taskId);
        RouteSnapshot snapshot = routeService.parseSnapshot(task.getRouteSnapshot());

        TaskMapVO map = new TaskMapVO()
                .setTaskId(task.getId())
                .setTaskNo(task.getTaskNo())
                .setTaskName(task.getTaskName())
                .setStatus(task.getStatus())
                .setDroneCode(task.getDroneCode())
                .setPlannedRoute(routeService.fromSnapshot(task.getRouteSnapshot()))
                .setTakeoffName(task.getTakeoffName())
                .setTrack(taskTrackService.getTrack(task.getId()))
                .setCheckins(listCheckins(task.getId()))
                .setCheckedPointCount(taskCheckinDao.countDistinctPoints(task.getId()))
                .setTotalPointCount(snapshot == null || snapshot.getPoints() == null
                        ? 0 : snapshot.getPoints().size());

        if (task.getTakeoffLng() != null && task.getTakeoffLat() != null) {
            double[] gcj = CoordinateUtil.wgs84ToGcj02(task.getTakeoffLng(), task.getTakeoffLat());
            map.setTakeoffLng(gcj[0]).setTakeoffLat(gcj[1]);
        }
        return map;
    }

    /**
     * 从航线快照里找第 {@code seq} 个航点；找不到返回 null。
     *
     * <p>按 seq 找而不是按下标：seq 是航线的一部分（顺序就是飞行顺序），
     * 而下标依赖「列表有没有被过滤过」，两处一旦不一致，打卡就对到了别的点上。
     */
    private RoutePointPO findSnapshotPoint(RouteSnapshot snapshot, int seq) {
        if (snapshot == null || snapshot.getPoints() == null) {
            return null;
        }
        return snapshot.getPoints().stream()
                .filter(point -> point.getSeq() != null && point.getSeq() == seq)
                .findFirst().orElse(null);
    }

    private void validateCheckinRemark(String remark) {
        if (remark != null && remark.trim().length() > CHECKIN_REMARK_MAX_LENGTH) {
            throw new BusinessException("说明不能超过 " + CHECKIN_REMARK_MAX_LENGTH + " 个字");
        }
    }

    /** 打卡 PO → VO：**库里的 WGS84 在这里转成 GCJ-02**，并补上几个判断好的标记 */
    private TaskCheckinVO toCheckinVO(TaskCheckinPO checkin) {
        if (checkin == null) {
            return null;
        }
        double[] targetGcj = CoordinateUtil.wgs84ToGcj02(checkin.getTargetLng(), checkin.getTargetLat());
        double[] selfGcj = CoordinateUtil.wgs84ToGcj02(checkin.getLng(), checkin.getLat());

        boolean late = checkin.getReportedAt() != null && checkin.getObservedAt() != null
                && Duration.between(checkin.getObservedAt(), checkin.getReportedAt()).toMinutes()
                >= CHECKIN_LATE_MINUTES;

        return new TaskCheckinVO()
                .setId(checkin.getId())
                .setSeq(checkin.getSeq())
                .setTargetLng(targetGcj[0])
                .setTargetLat(targetGcj[1])
                .setLng(selfGcj[0])
                .setLat(selfGcj[1])
                .setAccuracyM(checkin.getAccuracyM())
                .setDeviationM(checkin.getDeviationM())
                .setFarAway(checkin.getDeviationM() != null
                        && checkin.getDeviationM() > CHECKIN_FAR_AWAY_METERS)
                .setObservedAt(checkin.getObservedAt())
                .setReportedAt(checkin.getReportedAt())
                .setSubmittedLate(late)
                .setReporterName(checkin.getReporterName())
                .setRemark(checkin.getRemark());
    }

    /** 证据点 PO……其实是 WGS84 的位置点转成 GCJ-02；为空就原样返回（看板上显示「暂无位置」） */
    private TaskLivePointVO toGcjLivePoint(TaskLivePointVO point) {
        if (point == null) {
            return null;
        }
        double[] gcj = CoordinateUtil.wgs84ToGcj02(point.getLng(), point.getLat());
        return new TaskLivePointVO()
                .setLng(gcj[0])
                .setLat(gcj[1])
                .setObservedAt(point.getObservedAt());
    }

    /**
     * 把最近的原始点收成「够画一小段航迹」的点列。
     *
     * <p>原始点数已经由 SQL 限制在 {@code TRAIL_RAW_LIMIT} 以内（看板上每条任务
     * 都画完整轨迹会把响应撑爆），所以这里只做等间隔截取，不再按距离抽稀 ——
     * 按距离抽稀是给「几千个点」用的，而这里的量级已经被压住了。
     * <b>末点必留</b>：它要和「当前位置」那个标记对得上，否则线头会飘在别处。
     */
    private List<TaskLivePointVO> toTrail(List<TaskLivePointVO> points) {
        if (points == null || points.isEmpty()) {
            return List.of();
        }
        List<TaskLivePointVO> converted = points.stream().map(this::toGcjLivePoint).toList();
        if (converted.size() <= TRAIL_MAX_POINTS) {
            return converted;
        }
        List<TaskLivePointVO> result = new ArrayList<>(TRAIL_MAX_POINTS);
        double step = (double) (converted.size() - 1) / (TRAIL_MAX_POINTS - 1);
        for (int i = 0; i < TRAIL_MAX_POINTS; i++) {
            result.add(converted.get((int) Math.round(i * step)));
        }
        return result;
    }

    // ------------------------------------------------------------------
    // 禁飞区静态校验（地图接入方案 第三步）
    //
    // 它发生在两个写操作里：创建任务、派发任务。两处都是「软拦」——
    // 命中后必须明确确认 + 写原因才放行，那段确认写进流转流水（留痕）。
    // 这一层只做静态校验，**拦不住已经起飞的飞机**；
    // 事中的检测与告警是第四步（见设计文档 6.3.3）。
    // ------------------------------------------------------------------

    @Override
    public List<ZoneHitVO> checkPlanZones(Long routeId, Double takeoffLng, Double takeoffLat) {
        // 前端手上只有 routeId，航线的点得服务端去取 —— 所以这个预检必须在服务端做
        RouteSnapshot snapshot = (routeId == null)
                ? null
                : routeService.parseSnapshot(routeService.snapshotJsonOf(routeId));
        return noFlyZoneService.check(zoneCheckInput(snapshot, takeoffToWgs84(takeoffLng, takeoffLat)));
    }

    @Override
    public List<ZoneHitVO> checkTaskZones(Long taskId) {
        return zoneHitsOfTask(requireTask(taskId));
    }

    /**
     * 把一条任务「打算怎么飞」拿去和禁飞区比。
     *
     * <p>用的是任务上的<b>快照</b>（不是当前的航线模板）：校验要判的是
     * 「这条任务会飞到哪里」，而它引用模板只是当初的引用关系 ——
     * 模板改了之后，这条任务飞的位置并没有跟着变（见 {@code RouteSnapshot}）。
     */
    private List<ZoneHitVO> zoneHitsOfTask(TaskPO task) {
        // 任务上存的起降点已经是 WGS84（入库时转过的），这里不需要再转
        double[] takeoffWgs = (task.getTakeoffLng() == null || task.getTakeoffLat() == null)
                ? null
                : new double[]{task.getTakeoffLng(), task.getTakeoffLat()};
        return noFlyZoneService.check(zoneCheckInput(
                routeService.parseSnapshot(task.getRouteSnapshot()), takeoffWgs));
    }

    /**
     * 组装待校验的内容。
     *
     * <p><b>⚠️ {@code takeoffWgs} 和快照里的坐标都必须是 WGS84</b> ——
     * 禁飞区几何、设备轨迹都在那一套坐标上，混一个 GCJ-02 进来
     * 会算出几百米的假偏差（看起来「差不多对」，只是偶尔拦错）。
     *
     * <p>为什么点与折线都带上：点回答「这些位置在不在区内」，
     * 折线回答「这条线有没有从区内穿过去」—— 起点终点都在区外、中间横穿的情况，
     * 只看点会漏掉（设计文档 6.3.2）。
     */
    private ZoneCheckInput zoneCheckInput(RouteSnapshot snapshot, double[] takeoffWgs) {
        List<ZoneCheckPoint> points = new ArrayList<>();
        if (takeoffWgs != null) {
            points.add(ZoneCheckPoint.of("起降点", takeoffWgs[0], takeoffWgs[1]));
        }

        List<double[]> polyline = new ArrayList<>();
        if (snapshot != null && snapshot.getPoints() != null) {
            for (RoutePointPO point : snapshot.getPoints()) {
                if (point.getLng() == null || point.getLat() == null) {
                    continue;
                }
                String label = "第 " + (point.getSeq() == null ? points.size() : point.getSeq()) + " 个航点";
                points.add(new ZoneCheckPoint(label, point.getLng(), point.getLat(), point.getAlt()));
                polyline.add(new double[]{point.getLng(), point.getLat()});
            }
        }
        return new ZoneCheckInput(points, polyline);
    }

    /**
     * 命中就拦下来 —— 除非调用方「明确确认 + 写清原因」。
     *
     * <h2>为什么是软拦</h2>
     * 系统拦不住已经起飞的飞机，它能做的是把风险摆到人面前。而现实里确实有
     * 必须飞进去的场景（临时管制期间有批文、客户自己划的范围当天要巡）。
     * 硬拦只会逼着人去把那个禁飞区删掉 —— 那等于把安全控制整个关掉，
     * 还不如让他签名留痕（见设计文档 6.3.2）。
     *
     * <p>「必须写原因」不是形式主义：流水里那句「为什么还是建了」
     * 是事后唯一能把「人知情」和「人不知情」分开的东西。
     */
    private void assertZoneAcceptable(List<ZoneHitVO> hits, Boolean acknowledged, String reason) {
        if (hits == null || hits.isEmpty()) {
            return;
        }
        if (!Boolean.TRUE.equals(acknowledged)) {
            String detail = hits.stream().map(ZoneHitVO::getMessage).limit(3)
                    .collect(Collectors.joining("；"));
            throw new BusinessException("禁飞区校验未通过：" + detail
                    + "。确认已知晓风险并填写原因后可以继续（这段确认会记进流转流水）");
        }
        String trimmed = reason == null ? null : reason.trim();
        if (trimmed == null || trimmed.length() < ZONE_REASON_MIN_LENGTH) {
            throw new BusinessException("已知晓禁飞区风险，请填写原因（至少 "
                    + ZONE_REASON_MIN_LENGTH + " 个字）—— 这句会记进流转流水");
        }
        if (trimmed.length() > ZONE_REASON_MAX_LENGTH) {
            throw new BusinessException("原因请控制在 " + ZONE_REASON_MAX_LENGTH + " 个字以内");
        }
    }

    /** 流转流水里那段留痕；没命中时返回 null（调用方据此不加这一段） */
    private String zoneNote(List<ZoneHitVO> hits, String reason) {
        String described = noFlyZoneService.describeHits(hits);
        if (described == null) {
            return null;
        }
        String trimmed = reason == null ? null : reason.trim();
        return "已知晓禁飞区风险：" + described + (trimmed == null ? "" : "；原因：" + trimmed);
    }

    /**
     * 打卡坐标 → 库里那套（WGS84）。
     *
     * <p>调用方**声明**手里的坐标是哪一种，不靠服务端猜：
     * 手机小程序给 GCJ-02（地图那一套），浏览器给 WGS84（GPS 原始值）。
     * 猜错的表现是位置偏 500 米，而它看起来「差不多对」——
     * 这正是本项目里最容易蒙混过关的一类错。
     */
    private double[] toWgs84(double[] lngLat, String coordinateSystem) {
        if (TaskCheckinParam.WGS84.equals(coordinateSystem)) {
            return lngLat;
        }
        return CoordinateUtil.gcj02ToWgs84(lngLat[0], lngLat[1]);
    }

    /** 前端传的起降点是 GCJ-02（地图上点的），校验要 WGS84；没传全时返回 null（不是 0,0） */
    private double[] takeoffToWgs84(Double gcjLng, Double gcjLat) {
        if (gcjLng == null || gcjLat == null) {
            return null;
        }
        return CoordinateUtil.gcj02ToWgs84(gcjLng, gcjLat);
    }

    // ------------------------------------------------------------------
    // 飞手端：告警与附件（实施步骤第五步）
    // ------------------------------------------------------------------

    @Override
    public PageResult<AlertVO> pageMyTaskAlerts(Long taskId, AlertQueryParam param) {
        // 归属校验先做：不是派给我的任务，连「这条任务上有几条告警」都不该知道
        requireMyTask(taskId);
        return alertService.pageAlertsOfTask(taskId, param);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AlertVO reportOnsiteIssue(TaskAlertReportParam param) {
        if (param == null || param.getTaskId() == null) {
            throw new BusinessException("请选择要上报的任务");
        }
        // 不限任务状态：只要这条任务是派给我的就能报。
        // 理由：飞机落地后才发现机身损伤，那时候任务可能已经完成了
        TaskPO task = requireMyTask(param.getTaskId());

        AlertPO alert = alertService.raiseManualReport(
                task, param.getAlertCode(), param.getMessage(), param.getAttachmentIds());

        // 重新查一次拿带附件与中文名的完整 VO（附件刚在 raiseManualReport 里绑上去）
        return alertService.getAlert(alert.getId());
    }

    @Override
    public TaskAttachmentVO uploadTaskAttachment(Long taskId, MultipartFile file, String description) {
        TaskPO task = requireMyTask(taskId);
        return attachmentService.upload(task, file, description);
    }

    @Override
    public AttachmentService.LoadedAttachment loadTaskAttachment(Long attachmentId) {
        TaskAttachmentPO attachment = attachmentService.getById(attachmentId);
        if (attachment == null) {
            throw new BusinessException("附件不存在");
        }

        LoginUser current = UserContext.get();
        if (current == null) {
            throw new BusinessException(401, "未登录，请先登录");
        }

        // 判据是「有没有 task:list」：有就能下任意任务的附件（管理侧要看飞手报的证据），
        // 没有就只能是这条任务的飞手本人。
        // 这里用权限码而不是角色名 —— 和项目里其它鉴权一致：
        // 角色是可以重新配的，权限点才是接口真正要求的那个东西
        if (!current.hasPermission(PERMISSION_TASK_LIST) && !isMyTask(attachment.getTaskId(), current)) {
            log.warn("越权下载附件被拒：userId={}，attachmentId={}，taskId={}",
                    current.getId(), attachmentId, attachment.getTaskId());
            throw new BusinessException(403, "这条附件不属于你的任务，无权下载");
        }

        return attachmentService.load(attachment);
    }

    /**
     * 这条任务是不是派给这个人的 —— 用于「或」型的判断。
     *
     * <p>为什么不复用 {@link #requireMyTask}：那个方法**会抛异常**，
     * 而下载这里需要的是「不满足就换个说法拒绝」，用 boolean 更贴合。
     * 两处都只做同一件小事（比对 pilot_id），不构成两套规则：
     * 权限的判据（谁能看哪条任务）仍然只有「本人或 task:list」这一个定义。
     */
    private boolean isMyTask(Long taskId, LoginUser current) {
        TaskPO task = taskDao.selectById(taskId);
        return task != null && current.getId().equals(task.getPilotId());
    }

    // ------------------------------------------------------------------
    // 报告线：任务收尾（完成 / 失败）与报告
    // ------------------------------------------------------------------

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TaskVO finishTask(TaskFinishParam param) {
        return settleTask(param, true);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TaskVO failTask(TaskFinishParam param) {
        return settleTask(param, false);
    }

    /**
     * 收尾的公共实现 —— 完成与失败只差两处：落地状态、以及「失败必须写原因」。
     *
     * <h2>四步的顺序不能变</h2>
     * <ol>
     *   <li>改状态（CAS：只有还是执行中才改）</li>
     *   <li>写报告（多版本）</li>
     *   <li>绑附件（校验归属）</li>
     *   <li>释放资源：设备回待命；飞手<b>仅当手上没别的活</b>时回空闲</li>
     * </ol>
     * 第 4 步必须在第 1 步之后：{@code countActiveByPilot} 统计的是
     * 「已派发 + 执行中」，先统计的话**这条任务自己会被算进去**，
     * COUNT 至少是 1，飞手永远回不到空闲。设计文档 3.3 节专门标了这个陷阱。
     *
     * @param success true 走完成，false 走标记失败
     */
    private TaskVO settleTask(TaskFinishParam param, boolean success) {
        if (param == null || param.getTaskId() == null) {
            throw new BusinessException("请选择要收尾的任务");
        }

        TaskPO task = requireMyTask(param.getTaskId());
        String action = success ? TaskStatusFlow.ACTION_FINISH : TaskStatusFlow.ACTION_FAIL;
        // 状态机说了算：只允许从「执行中」出发
        TaskStatusFlow.assertCanTransfer(action, task.getStatus());

        // 结论：完成时由飞手选（只收 success/partial），失败时服务端写死。
        // 这样 result 只描述「作业完成度」，不与任务状态重叠 —— 见设计文档 12.1
        String result = success ? requireFinishResult(param.getResult()) : RESULT_FAILED;

        // 标记失败必须说明为什么 —— 不写原因的「失败」和不填一样，事后复盘时是黑盒
        if (!success && !StringUtils.hasText(param.getProblemLog())) {
            throw new BusinessException("标记失败时必须填写失败原因");
        }
        validateFlightNumbers(param);
        // 附件归属也放在**改状态之前**校验：不通过时什么都还没发生，
        // 不用依赖「抛异常让事务回滚」来收拾残局（设计文档 2.3 的校验清单）
        attachmentService.prepareReportAttachments(task.getId(), param.getAttachmentIds());

        // ---- 第 1 步：改状态。CAS 返回 0 说明状态刚被别处改过，此时**不能**继续释放资源 ----
        LocalDateTime endAt = LocalDateTime.now();
        int updated = success
                ? taskDao.markFinished(task.getId(), endAt)
                : taskDao.markFailed(task.getId(), endAt);
        if (updated == 0) {
            throw new BusinessException("任务状态刚刚发生了变化，请刷新后重试");
        }

        // ---- 第 2 步：写报告（完成与失败都要写，字段完全一样）----
        saveReport(task, result, param);

        // ---- 第 4 步：释放资源 ----
        // 刻意**不用** releaseAssignment/clearDispatch：那套会把 pilot_id / drone_code
        // 一起清空，而完成必须保留指派关系 —— 报告要写清「是谁飞的、用哪架飞的」
        if (!deviceService.changeStatusIfMatch(task.getDroneId(), DRONE_FLYING, DRONE_IDLE)) {
            // 设备状态可能被人工改成了「维护中」之类，不该因此拦住收尾 ——
            // 飞手确实落地了。设备列表里能看到它真实的状态
            log.warn("收尾时设备状态未回到待命，taskId={}，droneId={}，请人工确认",
                    task.getId(), task.getDroneId());
        }
        // 飞手只有在**手上没有别的活**时才回到空闲（他可能同时背着别的时段的任务）。
        // 这一步必须在改状态之后，理由见方法注释
        if (taskDao.countActiveByPilot(task.getPilotId()) == 0) {
            userService.changeStateIfMatch(task.getPilotId(), USER_BUSY, USER_IDLE);
        }

        String note = success
                ? appendNote("任务完成", param.getSummary())
                : appendNote("标记执行失败", param.getProblemLog());
        writeLog(task.getId(), action, TaskStatusFlow.EXECUTING,
                success ? TaskStatusFlow.FINISHED : TaskStatusFlow.FAILED, note);

        TaskPO settled = taskDao.selectById(task.getId());
        log.info("任务收尾：taskId={}，编号={}，状态={}，结论={}，飞手={}，设备={}",
                settled.getId(), settled.getTaskNo(), settled.getStatus(), result,
                settled.getPilotName(), settled.getDroneCode());

        // 通知等提交后再推：现在还在事务里，万一提交失败，
        // 推出去的消息就成了「通知别人一件并不存在的事」
        String resultForNotice = result;
        afterCommit(() -> taskNotifier.notifyTaskSettled(settled, resultForNotice));
        return toTaskVO(settled);
    }

    /**
     * 写报告 —— <b>多版本</b>。
     *
     * <p>顺序是先「把旧版本翻成历史」再「插新版本」：反过来的话，
     * 中间会有一小段「两条都是 is_latest = 1」的时间窗，
     * 而那一瞬间查报告的人可能拿到任意一条。
     *
     * <p>为什么要允许改：报告是交付物，飞手笔误、管理员要求补充、
     * 客户提异议都会导致修订。但改过必须看得出来 —— 所以旧版本留着，
     * 靠 {@code version} 和 {@code is_latest} 区分。见设计文档第六章。
     */
    private void saveReport(TaskPO task, String result, TaskFinishParam param) {
        writeReportVersion(task.getId(), result,
                param.getSummary(), param.getProblemLog(),
                param.getFlightHours(), param.getFlightCount(),
                param.getAttachmentIds());
    }

    /**
     * 写一份新版本的报告（收尾与修订共用）。
     *
     * <p>「新版本 = 当前最大版本 + 1，且 is_latest = 1，旧的翻成 0」这套动作
     * 只写在这一处 —— 收尾和修订各写一份的话，迟早有一处忘了翻旧版本，
     * 于是同一条任务出现两条「当前版本」。
     */
    private void writeReportVersion(Long taskId, String result, String summary, String problemLog,
                                    BigDecimal flightHours, Integer flightCount,
                                    List<Long> attachmentIds) {
        Integer maxVersion = taskReportDao.selectMaxVersion(taskId);
        int nextVersion = (maxVersion == null ? 0 : maxVersion) + 1;

        // 先翻旧版本，再插新版本（顺序理由见 saveReport 的注释）
        taskReportDao.markOldVersionsNotLatest(taskId);

        LoginUser current = UserContext.get();
        taskReportDao.insert(new TaskReportPO()
                .setTaskId(taskId)
                .setVersion(nextVersion)
                .setIsLatest(1)
                .setResult(result)
                .setSummary(trimToNull(summary))
                .setProblemLog(trimToNull(problemLog))
                .setFlightHours(flightHours)
                .setFlightCount(flightCount)
                .setReporterId(current == null ? null : current.getId())
                .setReporterName(current == null ? null : resolveUserName(current.getId(), current)));

        log.info("报告已写入：taskId={}，版本={}，结论={}，附件 {} 个",
                taskId, nextVersion, result,
                attachmentIds == null ? 0 : attachmentIds.size());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TaskReportVO reviseReport(TaskReportReviseParam param) {
        if (param == null || param.getTaskId() == null) {
            throw new BusinessException("请选择要修订的任务");
        }
        TaskPO task = requireTask(param.getTaskId());
        // 修订的权限与「看报告」同一条规则：管理侧能改任意任务的，否则必须是本人的任务。
        // 用同一处判断，不另起一套 —— 两套规则迟早会出现「能改但不能看」这种怪事
        assertCanReadTask(task);

        String result = param.getResult();
        if (result == null || !RESULT_LABELS.containsKey(result)) {
            throw new BusinessException("结论只能是 success（成功）/ partial（部分完成）/ failed（失败）");
        }

        TaskFinishParam asFinish = new TaskFinishParam()
                .setTaskId(param.getTaskId())
                .setResult(result)
                .setSummary(param.getSummary())
                .setProblemLog(param.getProblemLog())
                .setFlightHours(param.getFlightHours())
                .setFlightCount(param.getFlightCount())
                .setAttachmentIds(param.getAttachmentIds());
        validateFlightNumbers(asFinish);
        // 和收尾一样，附件归属在校验阶段就查掉（不通过时什么都还没动）
        attachmentService.prepareReportAttachments(task.getId(), param.getAttachmentIds());

        // **不动任务状态**（也不动设备与飞手资源）：修订改的是一份交付物，
        // 不是任务本身走到了哪一步。见设计文档 6.2 节
        writeReportVersion(task.getId(), result,
                param.getSummary(), param.getProblemLog(),
                param.getFlightHours(), param.getFlightCount(),
                param.getAttachmentIds());

        log.info("报告已修订：taskId={}，编号={}，结论={}，修订人={}",
                task.getId(), task.getTaskNo(), result,
                UserContext.get() == null ? "系统" : UserContext.get().getUsername());

        return getTaskReport(task.getId());
    }

    /** 完成时的结论白名单。**收不到 failed** —— 失败走 failTask，见 TaskFinishParam 的注释 */
    private static String requireFinishResult(String result) {
        if (result == null || !FINISH_RESULTS.contains(result)) {
            throw new BusinessException("结论只能是 success（成功）或 partial（部分完成）；"
                    + "执行失败请用「标记失败」");
        }
        return result;
    }

    /**
     * 飞行时长与架次的合理性校验。
     *
     * <p>为什么不做到"完全自动、不给改"：飞了两架次、中途落地换电池这类情况，
     * 自动算不出来。也不完全放开手填 —— 人就会瞎填。
     * 所以界面上的做法是<b>预填自动算出来的值 + 允许改</b>，
     * 而这里只挡住明显是手滑的（比如把 1.25 填成 125）。
     */
    private static void validateFlightNumbers(TaskFinishParam param) {
        BigDecimal hours = param.getFlightHours();
        if (hours != null && (hours.signum() < 0 || hours.compareTo(MAX_FLIGHT_HOURS) > 0)) {
            throw new BusinessException("飞行时长必须在 0 ~ 24 小时之间");
        }
        Integer count = param.getFlightCount();
        if (count != null && count < 0) {
            throw new BusinessException("飞行架次不能是负数");
        }
    }

    private static String trimToNull(String text) {
        if (!StringUtils.hasText(text)) {
            return null;
        }
        return text.trim();
    }

    @Override
    public TaskReportVO getTaskReport(Long taskId) {
        TaskPO task = requireTask(taskId);
        assertCanReadTask(task);

        TaskReportPO report = taskReportDao.selectLatestByTaskId(taskId);
        if (report == null) {
            throw new BusinessException("这条任务还没有报告");
        }

        // ---- 自动汇总那一半：每次现算，不落库（证据可以事后追加，存下来会过时）----
        TaskEvidenceSummary evidence = taskEvidenceDao.selectSummary(taskId);

        return new TaskReportVO()
                .setId(report.getId())
                .setVersion(report.getVersion())
                .setResult(report.getResult())
                .setResultLabel(RESULT_LABELS.getOrDefault(report.getResult(), report.getResult()))
                .setSummary(report.getSummary())
                .setProblemLog(report.getProblemLog())
                .setFlightHours(report.getFlightHours())
                .setFlightCount(report.getFlightCount())
                .setReporterName(report.getReporterName())
                .setCreateTime(report.getCreateTime())
                .setTaskNo(task.getTaskNo())
                .setTaskName(task.getTaskName())
                .setTaskStatus(task.getStatus())
                .setPilotName(task.getPilotName())
                .setDroneCode(task.getDroneCode())
                .setActualStartTime(task.getActualStartTime())
                .setActualEndTime(task.getActualEndTime())
                .setComputedHours(computedHours(task))
                .setTelemetryCount(evidence == null ? 0 : evidence.getTelemetryCount())
                .setDataFrom(evidence == null ? null : evidence.getFirstObservedAt())
                .setDataTo(evidence == null ? null : evidence.getLastObservedAt())
                .setMinBattery(evidence == null ? null : evidence.getMinBattery())
                .setMaxAlt(evidence == null ? null : evidence.getMaxAlt())
                .setEvidenceState(task.getEvidenceState())
                .setEvidenceLabel(EvidenceState.label(task.getEvidenceState()))
                // 「本次执行只有飞手的声明、没有设备数据佐证」。
                // 完成**不要求**这个条件（强制会把「数据链路不通」变成「飞手无法收工」），
                // 但界面要把它标出来让人看见 —— 见设计文档 2.3
                .setOnlyDeclared(!EvidenceState.CONFIRMED.equals(task.getEvidenceState()))
                .setAlertCount(countTaskAlerts(taskId))
                .setAttachments(attachmentService.mapByTaskId(taskId));
    }

    /**
     * 谁能看一条任务的报告：管理侧（{@code task:list}）看全部，否则必须是这条任务的飞手。
     *
     * <p>和附件下载是同一条规则、同一个理由 ——
     * 「或」的关系 {@code @RequirePermission} 表达不了（它的多个值是 AND），
     * 所以放行到服务层判。判据仍然是权限码本身，不是角色名。
     */
    private void assertCanReadTask(TaskPO task) {
        LoginUser current = UserContext.get();
        if (current == null) {
            throw new BusinessException(401, "未登录，请先登录");
        }
        if (current.hasPermission(PERMISSION_TASK_LIST) || current.getId().equals(task.getPilotId())) {
            return;
        }
        log.warn("越权查看任务报告被拒：userId={}，taskId={}", current.getId(), task.getId());
        throw new BusinessException(403, "这条任务不是派给你的，无权查看报告");
    }

    /** 按实际起止算时长，保留两位。起止缺一个就返回空 —— 不猜 */
    private static BigDecimal computedHours(TaskPO task) {
        if (task.getActualStartTime() == null || task.getActualEndTime() == null) {
            return null;
        }
        long minutes = Duration.between(task.getActualStartTime(), task.getActualEndTime()).toMinutes();
        return BigDecimal.valueOf(minutes)
                .divide(BigDecimal.valueOf(60), 2, RoundingMode.HALF_UP);
    }

    /**
     * 这条任务上一共有几条告警（不分状态）。
     *
     * <p>口径与「飞手端角标」不同：角标只数未处理的（回答「有什么要管的」），
     * 报告里要的是「这次任务期间共发生了几条」（回答「当时发生了什么」）——
     * 否则报告上的数字会随处置进度变来变去。
     */
    private int countTaskAlerts(Long taskId) {
        return alertService.countAlertsOfTask(taskId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TaskVO dispatchTask(TaskDispatchParam param) {
        if (param == null || param.getTaskId() == null) {
            throw new BusinessException("请选择要派发的任务");
        }
        if (param.getPilotId() == null) {
            throw new BusinessException("请选择飞手");
        }
        if (param.getDroneId() == null) {
            throw new BusinessException("请选择设备");
        }

        TaskPO task = requireTask(param.getTaskId());
        // 第一道关：状态机说了算。这一步挡掉「已完成的任务又被派发一次」之类的问题
        TaskStatusFlow.assertCanTransfer(TaskStatusFlow.ACTION_DISPATCH, task.getStatus());

        UserPO pilot = requireDispatchablePilot(param.getPilotId());
        DeviceVO drone = requireDispatchableDrone(param.getDroneId());
        assertNoScheduleConflict(task, pilot, drone);

        // 禁飞区：派发前再看一眼 —— 任务可能是上周建的，而区域是昨天才画的，
        // 或者今天 8 点起进入临时管制。所以只查创建那一次是不够的，
        // 这两次的结果**可以不一样**（这也是「生效期」那一列存在的意义）。
        // 位置放在占设备之前：被拦下时不该留下「设备已被派出」这种半成品状态
        List<ZoneHitVO> zoneHits = zoneHitsOfTask(task);
        assertZoneAcceptable(zoneHits, param.getAcknowledgedZones(), param.getZoneReason());

        String note = appendNote("指派" + pilot.getName() + "驾驶" + drone.getCode(), param.getNote());
        String zonePart = zoneNote(zoneHits, param.getZoneReason());
        if (zonePart != null) {
            note = note + "；" + zonePart;
        }

        // 先占住设备：状态对不上说明别人刚动过这台设备（改成维护中、或派给了别的任务），
        // 这时候继续往下写就是不诚实的 —— 任务说已派发，设备却没有对应的状态
        if (!deviceService.changeStatusIfMatch(drone.getId(), DRONE_IDLE, DRONE_ASSIGNED)) {
            throw new BusinessException("「" + drone.getCode()
                    + "」的状态刚刚被改动，请刷新后重新选择设备");
        }

        taskDao.updateDispatch(task.getId(), pilot.getId(), pilot.getName(),
                drone.getId(), drone.getCode(), TaskStatusFlow.DISPATCHED);
        writeLog(task.getId(), TaskStatusFlow.ACTION_DISPATCH,
                TaskStatusFlow.PENDING, TaskStatusFlow.DISPATCHED, note);

        // 飞手的在线状态只是展示位，用 CAS 改「空闲 → 作业中」：
        // 已经被标成「已停飞」的人不会因此被改成「作业中」，人工标记得以保留
        userService.changeStateIfMatch(pilot.getId(), USER_IDLE, USER_BUSY);

        log.info("派发任务成功，id={}，编号={}，飞手={}，设备={}（设备状态 {} → {}）",
                task.getId(), task.getTaskNo(), pilot.getName(), drone.getCode(),
                DRONE_IDLE, DRONE_ASSIGNED);

        // 推送放在事务提交之后：现在还在事务里，万一后面提交失败（或测试里回滚），
        // 消息却已经推给飞手了 —— 那就成了「通知他去干一件并不存在的活」。
        // 重新查一次是为了拿到库里的 create_time 等字段，同时也保证推的是最终状态
        TaskPO dispatched = taskDao.selectById(task.getId());
        afterCommit(() -> taskNotifier.notifyTaskDispatched(dispatched));

        return toTaskVO(taskDao.selectById(task.getId()));
    }

    /**
     * 事务提交成功后再执行一段动作（本项目用它推送通知）。
     *
     * <p>{@link TransactionSynchronizationManager} 是 Spring 提供的「事务钩子」：
     * 注册进去的回调会在事务的不同阶段被调用，这里用 {@code afterCommit}。
     *
     * <p><b>为什么非要等提交后</b>：现在还在事务里，万一后面提交失败（或测试里回滚），
     * 消息却已经推出去了 —— 那就成了「通知别人去干一件并不存在的事」。
     *
     * <p>没有事务时（比如直接调 Service 的单测）就立刻执行 ——
     * 那种场景下不存在「后面可能回滚」的问题。
     *
     * <p>推送自身的失败不会影响业务：{@link cn.tedu.drone.task.ws.TaskNotifier}
     * 只记日志、不抛异常。理由见 {@code docs/任务通知.md} 的「消息以谁为准」一节 ——
     * 推送负责「快」，数据以查询为准，收不到最多是晚几秒知道。
     */
    private void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
            return;
        }
        action.run();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TaskVO withdrawDispatch(TaskWithdrawParam param) {
        if (param == null || param.getTaskId() == null) {
            throw new BusinessException("请选择要撤回的任务");
        }

        TaskPO task = requireTask(param.getTaskId());
        TaskStatusFlow.assertCanTransfer(TaskStatusFlow.ACTION_WITHDRAW, task.getStatus());

        // 把「撤回的是谁和哪台设备」记进流水：主表清空之后，
        // 只有流水还记得原指派，事后复盘问「这任务原本给谁的」就靠它
        String note = appendNote(
                "撤回派发，原指派 " + task.getPilotName() + " / " + task.getDroneCode(),
                param.getNote());

        if (!releaseAssignment(task, TaskStatusFlow.ACTION_WITHDRAW, note)) {
            // 状态刚刚被别人改过（比如定时释放抢先了一步），这次撤回没有生效
            throw new BusinessException("任务状态刚刚发生了变化，请刷新后重试");
        }

        log.info("撤回派发成功，id={}，编号={}，原飞手={}，原设备={}",
                task.getId(), task.getTaskNo(), task.getPilotName(), task.getDroneCode());

        return toTaskVO(taskDao.selectById(task.getId()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int releaseExpiredDispatched() {
        // 用应用本地时间算判定线，不用 SQL 的 NOW() —— mysqld 时区是 UTC，
        // 而 plan_end_time 存的是应用写入的本地时间，两者差 8 小时
        LocalDateTime deadline = LocalDateTime.now().minusMinutes(graceMinutes);

        List<TaskPO> expired = taskDao.selectExpiredDispatched(deadline, RELEASE_BATCH_SIZE);
        if (expired.isEmpty()) {
            // 空扫描也要留一行：否则「任务跑了但没命中」和「任务压根没跑」
            // 在日志里长得一模一样，排查时很要命
            log.debug("派发超时释放扫描：没有需要释放的任务（判定线 {}）", deadline);
            return 0;
        }

        int released = 0;
        for (TaskPO task : expired) {
            String note = "计划结束时间（" + describeWindow(task.getPlanStartTime(), task.getPlanEndTime())
                    + "）已过 " + graceMinutes + " 分钟仍未开始执行，系统自动释放占用的飞手与设备";
            // releaseAssignment 内部有比较并交换：状态变了就跳过，
            // 所以这里不需要再判断一次，也不会和别人重复释放
            if (releaseAssignment(task, TaskStatusFlow.ACTION_EXPIRE, note)) {
                released++;
                log.info("超时释放占用，id={}，编号={}，原飞手={}，原设备={}",
                        task.getId(), task.getTaskNo(), task.getPilotName(), task.getDroneCode());
            }
        }

        log.info("派发超时释放扫描完成，命中 {} 条，实际释放 {} 条", expired.size(), released);
        return released;
    }

    @Override
    public int markStaleEvidence(int staleSeconds) {
        // 判定线 = 现在 - 阈值。同样的理由：不用 SQL 的 NOW()，
        // mysqld 时区是 UTC，而 actual_start_time 写的是应用本地时间，差 8 小时
        LocalDateTime deadline = LocalDateTime.now().minusSeconds(Math.max(0, staleSeconds));

        List<TaskPO> candidates = taskDao.selectStaleEvidenceTasks(deadline, EVIDENCE_SCAN_BATCH_SIZE);
        if (candidates.isEmpty()) {
            log.debug("证据过期扫描：没有需要标记的任务（判定线 {}）", deadline);
            return 0;
        }

        int marked = 0;
        for (TaskPO task : candidates) {
            // 逐条走条件更新：状态在这几毫秒里刚被遥测证实的话，这里会拿到 0 行，
            // 跳过就好 —— 绝不能「先判断再无条件改」，那会把刚拿到的证据覆盖掉
            if (taskDao.markEvidenceStale(task.getId()) == 0) {
                continue;
            }
            marked++;
            // 这条日志是给人看的信号，不是给机器看的：它意味着「这里对不上，需要有人过问」。
            // 所以把能帮助判断的信息都带上 —— 谁、哪台设备、什么时候声明的、等了多少秒
            log.info("任务证据已标记为过期：声明后 {} 秒内未收到设备数据佐证，"
                            + "taskId={}，编号={}，飞手={}，设备={}，声明时刻={}。"
                            + "任务状态保持「执行中」不变，请人工确认是飞手未起飞还是设备数据链路有问题",
                    staleSeconds, task.getId(), task.getTaskNo(), task.getPilotName(),
                    task.getDroneCode(), task.getActualStartTime());
        }

        log.info("证据过期扫描完成，命中 {} 条，实际标记 {} 条（原依据均为 {}）",
                candidates.size(), marked, EvidenceState.label(EvidenceState.DECLARED));
        return marked;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TaskVO startTask(Long taskId) {
        TaskPO task = requireMyTask(taskId);
        TaskStatusFlow.assertCanTransfer(TaskStatusFlow.ACTION_START, task.getStatus());

        // 状态、实际开始时间、证据状态三件事一次改完：
        // 前两个是业务流转，第三个（evidence_state → declared）是给「这句话有没有依据」
        // 这个问题留一个可对账的锚点。此刻还只是飞手的声明，没有任何旁证 ——
        // 遥测到了才会被抬到 confirmed，见 docs/任务业务设计.md 第十章
        taskDao.markStarted(task.getId(), LocalDateTime.now());
        writeLog(task.getId(), TaskStatusFlow.ACTION_START,
                TaskStatusFlow.DISPATCHED, TaskStatusFlow.EXECUTING, "飞手开始执行");

        // 设备状态只是「如实反映」，改不动不该拦住执行 —— 飞手确实已经开干了，
        // 不能因为设备档案被别人手动改过就报错。设备列表里能看到真实状态
        if (!deviceService.changeStatusIfMatch(task.getDroneId(), DRONE_ASSIGNED, DRONE_FLYING)) {
            log.warn("开始执行时设备状态未变为飞行中，taskId={}，droneId={}，请人工确认",
                    task.getId(), task.getDroneId());
        }

        log.info("飞手开始执行，id={}，编号={}，飞手={}", task.getId(), task.getTaskNo(), task.getPilotName());

        // 重新查一次拿最新状态（executing），提交后再推给能重新指派的人
        TaskPO started = taskDao.selectById(task.getId());
        afterCommit(() -> taskNotifier.notifyTaskStarted(started));
        return toTaskVO(taskDao.selectById(task.getId()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TaskVO refuseTask(TaskRefuseParam param) {
        if (param == null || param.getTaskId() == null) {
            throw new BusinessException("请选择要拒绝的任务");
        }

        TaskPO task = requireMyTask(param.getTaskId());
        TaskStatusFlow.assertCanTransfer(TaskStatusFlow.ACTION_REFUSE, task.getStatus());

        // 拒绝的释放动作与「撤回派发」完全一样：任务回待派发、设备回待命、飞手视情况回空闲。
        // 区别只在流水里的 action 不同 —— 这样管理员能分清「我撤的」和「他不接」
        String note = appendNote("拒绝执行", param.getReason());

        if (!releaseAssignment(task, TaskStatusFlow.ACTION_REFUSE, note)) {
            throw new BusinessException("任务状态刚刚发生了变化，请刷新后重试");
        }

        log.info("飞手拒绝执行，id={}，编号={}，飞手={}，原因={}",
                task.getId(), task.getTaskNo(), task.getPilotName(),
                StringUtils.hasText(param.getReason()) ? param.getReason() : "（未填）");

        // 通知里的「谁拒的」要用释放前的快照：释放会把 pilot_name 一并清空，
        // 而管理员恰恰最需要知道是哪个飞手不接这活。
        // 任务状态则相反，要用释放后的（pending），前端据此提示「已回到待派发，可重新指派」
        String refusedBy = task.getPilotName();
        String reason = StringUtils.hasText(param.getReason()) ? param.getReason().trim() : null;
        TaskPO released = taskDao.selectById(task.getId());
        afterCommit(() -> taskNotifier.notifyTaskRefused(released, refusedBy, reason));

        return toTaskVO(taskDao.selectById(task.getId()));
    }

    @Override
    public TaskDispatchOptionsVO getDispatchOptions() {
        return new TaskDispatchOptionsVO()
                .setPilots(taskDao.selectDispatchablePilots())
                .setDrones(taskDao.selectDispatchableDrones());
    }

    // ------------------------------------------------------------------
    // 内部方法
    // ------------------------------------------------------------------

    /**
     * 取任务并校验「这是派给当前登录人的」。
     *
     * <p>这是飞手侧操作的第一道关，也是最重要的一道：
     * 没有它，任何有 {@code task:execute} 的飞手只要猜到任务 id，
     * 就能去开始或拒绝<b>别人</b>的任务 —— 典型的水平越权。
     *
     * <p>为什么管理员也过不去：管理员虽然拥有全部权限，
     * 但「开始执行」在业务上是飞手本人的动作，他的 id 不会等于任务的 pilot_id。
     * 这不是限制管理员，而是这个动作本来就不该由他代做
     * （要改状态应该走撤回 / 重新指派）。
     *
     * @throws BusinessException 任务不存在、或不是派给当前登录人的
     */
    private TaskPO requireMyTask(Long taskId) {
        LoginUser current = UserContext.get();
        if (current == null) {
            throw new BusinessException(401, "未登录，请先登录");
        }

        TaskPO task = requireTask(taskId);
        if (!current.getId().equals(task.getPilotId())) {
            // 用 403 而不是 404：告诉调用者「你没权限」，而不是假装任务不存在。
            // 这里不担心暴露任务是否存在 —— 任务 id 本身不是秘密，能操作才是
            throw new BusinessException(403, "这条任务不是派给你的，无权操作");
        }
        return task;
    }

    /**
     * 释放任务占用的资源：任务回待派发、设备回待命、飞手视情况回空闲。
     *
     * <p>「撤回派发」和「超时释放」共用这一份 —— 两者除了流水里的
     * {@code action} 与操作人不同，动作完全一样。抄成两份的话，
     * 迟早有一处漏掉「顺便把 drone_code 也清掉」之类的细节。
     * 以后 ③ 的「取消任务」也是同一套。
     *
     * @return true 表示确实释放了；false 表示任务状态已经不是「已派发」，
     *         什么都没做（别人抢先改过）
     */
    private boolean releaseAssignment(TaskPO task, String action, String note) {
        // 条件更新把「判断」和「改」合成一步：只有还是「已派发」才清空
        if (taskDao.clearDispatch(task.getId(), TaskStatusFlow.DISPATCHED, TaskStatusFlow.PENDING) == 0) {
            log.debug("任务状态已变化，跳过释放，taskId={}", task.getId());
            return false;
        }

        // 设备放回「待命」。这一步失败只记 warn，不让释放失败 ——
        // 语义是「取消这次占用」，不能因为设备被人工改成了「维护中」就撤不回来。
        // 设备列表中能看到它真实的「维护中」，所以这里不额外造提示
        if (!deviceService.changeStatusIfMatch(task.getDroneId(), DRONE_ASSIGNED, DRONE_IDLE)) {
            log.warn("释放占用时设备状态未回到待命，taskId={}，droneId={}，请人工确认",
                    task.getId(), task.getDroneId());
        }

        // 飞手状态改回「空闲」前先确认他手上再没有别的活：
        // 一个人可以同时背着不同时段的好几个任务，释放掉一个不代表他就闲下来了
        if (taskDao.countActiveByPilot(task.getPilotId()) == 0) {
            userService.changeStateIfMatch(task.getPilotId(), USER_BUSY, USER_IDLE);
        }

        writeLog(task.getId(), action, TaskStatusFlow.DISPATCHED, TaskStatusFlow.PENDING, note);
        return true;
    }

    /** 取任务，取不到就报错 —— 详情、派发、撤回三处都要这一步，抽出来免得各写一遍 */
    private TaskPO requireTask(Long taskId) {
        TaskPO task = taskDao.selectById(taskId);
        if (task == null) {
            throw new BusinessException("任务不存在");
        }
        return task;
    }

    /**
     * 校验这个人能不能接任务。
     *
     * <p>三层判断的理由：
     * <ul>
     *   <li>存在 —— 前端传了个不存在的 ID</li>
     *   <li>账号启用 —— 离职或停用的账号不该继续接活</li>
     *   <li>有飞手角色 —— 不能把任务派给机务或管理员。
     *       这里判的是<b>角色</b>而不是 {@code task:execute} 权限：
     *       角色是「他是干什么的」，权限是「他现在能点什么」，
     *       派活看的是前者</li>
     * </ul>
     */
    private UserPO requireDispatchablePilot(Long pilotId) {
        UserPO pilot = userDao.selectById(pilotId);
        if (pilot == null) {
            throw new BusinessException("飞手不存在");
        }

        if (!USER_ENABLED.equals(pilot.getStatus())) {
            throw new BusinessException("「" + pilot.getName() + "」的账号已被禁用，不能派发任务");
        }

        Set<String> roles = userService.listRoles(pilotId);
        if (roles == null || !roles.contains(PILOT_ROLE_CODE)) {
            throw new BusinessException("「" + pilot.getName() + "」不是飞手，不能派发任务");
        }

        return pilot;
    }

    /**
     * 校验这台设备能不能出机。
     *
     * <p>只认「待命」：飞行中、充电中、维护中、离线的设备都派不了。
     * 前端下拉里已经只列待命的设备，这里再判一次是因为
     * <b>下拉是打开弹窗那一刻的快照</b> —— 别人可能在这期间把设备改了状态。
     */
    private DeviceVO requireDispatchableDrone(Long droneId) {
        // 设备不存在时它自己会抛「设备不存在」，这里不重复写一遍文案
        DeviceVO drone = deviceService.getDeviceDetail(droneId);

        if (!DRONE_IDLE.equals(drone.getStatus())) {
            throw new BusinessException("「" + drone.getCode()
                    + "」当前不是待命状态，不能派发，请刷新后重新选择设备");
        }

        return drone;
    }

    /**
     * 校验飞手与设备在任务的时间段内是否空闲。
     *
     * <p>冲突检查放在派发这一步，是因为<b>派发是唯一真正占用资源的动作</b>。
     * 创建任务时还没有人和设备，自然也谈不上冲突。
     *
     * <p>这里刻意不看「有没有未结束的任务」，而看<b>时间段是否重叠</b>：
     * 一个飞手下周有两个任务很正常，那叫排班；要挡的是同一时段被派了两件事。
     *
     * <p>两条检查的分量不一样：
     * <ul>
     *   <li><b>飞手</b>是主要防线 —— 派发不会因为「他手上还有活」而拒绝，
     *       所以不同时段的任务可以排给他</li>
     *   <li><b>设备</b>是兜底 —— 派发已经把设备置成「已派出」，正常路径下
     *       它压根不会再出现在下拉里。这条检查防的是<b>有人手工把设备状态改回「待命」</b>
     *       之后又去派发，那种情况下仍不该让一台设备同时扛两个重叠的任务</li>
     * </ul>
     */
    private void assertNoScheduleConflict(TaskPO task, UserPO pilot, DeviceVO drone) {
        TaskPO pilotConflict = taskDao.selectPilotConflict(
                pilot.getId(), task.getPlanStartTime(), task.getPlanEndTime());
        if (pilotConflict != null) {
            throw new BusinessException("「" + pilot.getName() + "」在 "
                    + describeWindow(pilotConflict.getPlanStartTime(), pilotConflict.getPlanEndTime())
                    + " 已有任务 " + pilotConflict.getTaskNo() + "，同一时段不能重复派发");
        }

        TaskPO droneConflict = taskDao.selectDroneConflict(
                drone.getId(), task.getPlanStartTime(), task.getPlanEndTime());
        if (droneConflict != null) {
            throw new BusinessException("「" + drone.getCode() + "」在 "
                    + describeWindow(droneConflict.getPlanStartTime(), droneConflict.getPlanEndTime())
                    + " 已被任务 " + droneConflict.getTaskNo() + " 占用，请换一台设备");
        }
    }

    /**
     * 写一条流转流水。
     *
     * <p>操作人取自登录态（{@link UserContext}）；<b>取不到就记为「系统」</b>。
     *
     * <p>为什么可以这么判：所有人工触发的流转接口都在拦截器后面，一定登录过；
     * 而定时任务跑在调度线程上，那个线程的 ThreadLocal 口袋里是空的 ——
     * 这也正说明 ThreadLocal 是「每个线程一个口袋」，不是全局变量
     * （详见 docs/ThreadLocal学习笔记.md）。所以「口袋里没人」就等于「这不是人干的」。
     *
     * <p>抽出来的原因很实际：创建、派发、撤回、超时释放四处都要写这一串，
     * 抄四遍迟早有一处忘了带 {@code operatorRole} ——
     * 而那正是事后复盘权限问题最需要的字段。
     */
    private void writeLog(Long taskId, String action, String fromStatus, String toStatus, String note) {
        LoginUser current = UserContext.get();

        String operatorName = SYSTEM_OPERATOR_NAME;
        String operatorRole = SYSTEM_OPERATOR_ROLE;
        if (current != null) {
            operatorName = resolveUserName(current.getId(), current);
            operatorRole = firstRole(current);
        }

        taskLogDao.insert(new TaskLogPO()
                .setTaskId(taskId)
                .setAction(action)
                .setFromStatus(fromStatus)
                .setToStatus(toStatus)
                .setOperatorId(current == null ? null : current.getId())
                .setOperatorName(operatorName)
                .setOperatorRole(operatorRole)
                .setNote(note));
    }

    /** 用户填了附言就拼在自动生成的说明后面，两句都保留，信息不丢 */
    private String appendNote(String base, String userNote) {
        return StringUtils.hasText(userNote) ? base + "：" + userNote.trim() : base;
    }

    /** 把任务时间段格式化成「2026-09-18 15:00 ~ 17:00」，用于报错文案 */
    private String describeWindow(LocalDateTime start, LocalDateTime end) {
        if (start == null || end == null) {
            return "计划时间段";
        }
        // 跨天的任务两端都写全日期，否则「20:00 ~ 02:00」看不出是第二天
        if (start.toLocalDate().equals(end.toLocalDate())) {
            return start.format(WINDOW_FORMATTER) + " ~ " + end.format(TIME_ONLY_FORMATTER);
        }
        return start.format(WINDOW_FORMATTER) + " ~ " + end.format(WINDOW_FORMATTER);
    }

    private void validate(TaskCreateParam param) {
        if (param == null || !StringUtils.hasText(param.getTaskName())) {
            throw new BusinessException("任务名称不能为空");
        }
        if (param.getTaskName().trim().length() > MAX_NAME_LENGTH) {
            throw new BusinessException("任务名称不能超过 " + MAX_NAME_LENGTH + " 个字");
        }

        if (!StringUtils.hasText(param.getPriority())) {
            throw new BusinessException("请选择优先级");
        }
        // 白名单校验：不让前端传个 P9 进来
        if (!VALID_PRIORITIES.contains(param.getPriority())) {
            throw new BusinessException("优先级只能是 P0~P3");
        }

        if (param.getPlanStartTime() == null || param.getPlanEndTime() == null) {
            throw new BusinessException("请填写计划起止时间");
        }
        if (param.getPlanEndTime().isBefore(param.getPlanStartTime())) {
            throw new BusinessException("计划结束时间不能早于开始时间");
        }

        // 起降点可空（不选就没有）；但只要给了其中一个，两个都必须给 ——
        // 只有经度画不出位置，只有坐标没有名称飞手不知道是哪个起降点。
        // 坐标系换算不在这里做：校验只关心「数字本身合不合理」
        if (param.getTakeoffLng() != null || param.getTakeoffLat() != null) {
            GeoInput.require(param.getTakeoffLng(), param.getTakeoffLat(), "起降点");
        }
    }

    /**
     * 任务列表上那句人看的「任务内容」。
     *
     * <p>选了航线而没自己写描述时，自动填一句摘要（「东线巡检 · 6 个点 · 3.2 公里」）——
     * 不填的话，用航线建出来的任务在列表上看不出「要做的是什么」，
     * 得点进详情才知道，等于列表页白给。
     *
     * <p><b>人自己写的优先</b>：管理员写了「顺便看一下南边的闸门」，
     * 那句话比自动摘要重要得多，不能覆盖掉。
     */
    private String resolveRouteInfo(TaskCreateParam param, RouteVO route) {
        if (StringUtils.hasText(param.getRouteInfo()) || route == null) {
            return param.getRouteInfo();
        }
        return route.getName() + " · " + route.getPointCount() + " 个点 · " + formatDistance(route.getDistanceM());
    }

    /**
     * 航程的展示文案。
     *
     * <p>不足 1 公里时说成「0.1 公里」很别扭（几十米的短航线真会出现），
     * 所以这里按量级换单位 —— 这是给人看的一句话，不是数据字段。
     */
    private String formatDistance(Integer meters) {
        if (meters == null) {
            return "航程未知";
        }
        return (meters < 1000) ? meters + " 米" : String.format("%.1f 公里", meters / 1000.0);
    }

    /**
     * 生成编号并插入。
     *
     * <p><b>这里有个并发窗口</b>：两个人同时创建时，「查最大编号 + 1」可能算出同一个编号。
     * 靠 {@code uk_task_no} 唯一索引挡下来，捕获冲突后重取一次。
     * 只重试一次是有意的 —— 连续撞两次说明不是偶发冲突，宁可报错也不要死循环。
     *
     * <p>这是 MVP 的权宜之计。量大了应换成号段表或 Redis 自增，
     * 避免把并发控制交给数据库异常。
     */
    private void insertWithGeneratedNo(TaskPO task) {
        String prefix = TASK_NO_PREFIX + LocalDate.now().format(DATE_PART) + "-";
        task.setTaskNo(nextTaskNo(prefix));

        try {
            taskDao.insert(task);
        } catch (DuplicateKeyException e) {
            log.warn("任务编号冲突，重取一次：{}", task.getTaskNo());
            task.setTaskNo(nextTaskNo(prefix));
            taskDao.insert(task);
        }
    }

    private String nextTaskNo(String prefix) {
        String max = taskDao.selectMaxTaskNo(prefix);

        int seq = 1;
        if (max != null && max.length() > prefix.length()) {
            try {
                seq = Integer.parseInt(max.substring(prefix.length())) + 1;
            } catch (NumberFormatException e) {
                // 编号格式异常时退回从 1 开始，不让整个功能因为一条脏数据不可用
                log.warn("任务编号格式异常，重置序号：{}", max);
            }
        }
        return prefix + String.format("%03d", seq);
    }

    /** 取创建人真实姓名；查不到就退回登录名 */
    private String resolveUserName(Long userId, LoginUser current) {
        if (userId != null) {
            UserPO user = userDao.selectById(userId);
            if (user != null && StringUtils.hasText(user.getName())) {
                return user.getName();
            }
        }
        return current == null ? null : current.getUsername();
    }

    private String firstRole(LoginUser current) {
        if (current == null || current.getRoles() == null || current.getRoles().isEmpty()) {
            return null;
        }
        return current.getRoles().iterator().next();
    }

    /**
     * PO → VO。
     *
     * <p>字段同名同类型，用 {@code BeanUtils.copyProperties} 一行搞定，
     * 不必手写二十几行 set —— 手写容易漏字段，而且加了新字段没人会记得回来补。
     *
     * <p>它按「VO 有哪些字段」来搬，不是全量搬运：哪天不想把某个字段
     * （比如内部的 {@code createBy}）给前端，把 VO 里那个字段删掉就行，
     * 这里一行都不用改。
     */
    private TaskVO toTaskVO(TaskPO task) {
        TaskVO vo = new TaskVO();
        BeanUtils.copyProperties(task, vo);

        // 起降点在 VO 里是 GCJ-02（见 TaskVO 的字段说明），所以这里要转一次。
        // 注意转换只在这一个方法里发生，别处（DAO、其它 VO）都是 WGS84
        if (task.getTakeoffLng() != null && task.getTakeoffLat() != null) {
            double[] gcj = CoordinateUtil.wgs84ToGcj02(task.getTakeoffLng(), task.getTakeoffLat());
            vo.setTakeoffLng(gcj[0]).setTakeoffLat(gcj[1]);
        }
        return vo;
    }

    /**
     * 流水 PO → VO。同样只挑 VO 里有的字段复制，
     * 所以 {@code taskId}、{@code operatorId} 这些内部 ID 不会漏给前端。
     */
    private TaskLogVO toLogVO(TaskLogPO log) {
        TaskLogVO vo = new TaskLogVO();
        BeanUtils.copyProperties(log, vo);
        return vo;
    }
}
