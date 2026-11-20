package cn.tedu.drone.dashboard.service.impl;

import cn.tedu.drone.common.LoginUser;
import cn.tedu.drone.common.PageResult;
import cn.tedu.drone.common.UserContext;
import cn.tedu.drone.device.dao.DeviceDao;
import cn.tedu.drone.route.dao.RouteDao;
import cn.tedu.drone.route.dao.SurveyPointDao;
import cn.tedu.drone.route.pojo.RouteQueryParam;
import cn.tedu.drone.route.pojo.SurveyPointQueryParam;
import cn.tedu.drone.task.dao.AlertDao;
import cn.tedu.drone.task.dao.TaskDao;
import cn.tedu.drone.task.dao.TaskEvidenceDao;
import cn.tedu.drone.dashboard.cache.TtlCache;
import cn.tedu.drone.dashboard.config.ScreenProperties;
import cn.tedu.drone.dashboard.pojo.DashboardSummaryVO;
import cn.tedu.drone.dashboard.service.DashboardService;
import cn.tedu.drone.task.pojo.AlertQueryParam;
import cn.tedu.drone.task.pojo.AlertVO;
import cn.tedu.drone.task.service.AlertService;
import cn.tedu.drone.user.dao.UserDao;
import cn.tedu.drone.zone.dao.NoFlyZoneDao;
import cn.tedu.drone.zone.pojo.NoFlyZoneQueryParam;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 数字大屏的实现。
 *
 * <h2>它为什么在顶层 {@code dashboard} 包，而不是挂在某个模块下面</h2>
 * 大屏**不属于任何一个模块** —— 它横跨任务 / 设备 / 告警 / 人员 / 地理五个域，
 * 屏上的数字大半也不是任务的。所以它是**第六个域**，和 user / device / task / route / zone 平级。
 *
 * <p>放在某个模块下（比如 {@code task.dashboard}）会藏起真实的依赖方向：
 * 读起来像「任务模块长出了一块大屏」，而实际是「<b>大屏依赖各模块</b>」。
 * 找大屏的代码时也该去顶层找，不用猜它在谁下面。
 *
 * <h2>⚠️ 它直接读了别人模块的表（一条刻意的破例，理由写在这里）</h2>
 * 本项目有一条规矩：<b>跨模块走 Service，不直接拿别人的 Dao</b>
 * （见 {@code TaskServiceImpl} 里查设备状态那段的原话）。
 * 这个类**破了这条例**：它注入了 8 个 DAO（task / alert / device / user / evidence / point / route / zone）。
 *
 * <p>破例的理由，是这条规矩真正要防的东西在这里不存在：
 * <ul>
 *   <li><b>它只读，一个字都不写别人的表</b>。那条规矩防的是「A 模块偷偷改了 B 模块的状态」——
 *       那种耦合会让状态机的归属变得说不清。而大屏做的全是 {@code COUNT} 与 {@code GROUP BY}</li>
 *   <li>为了守这条形式规则，要给 6 个模块的 Service 各加一个
 *       「只为大屏存在」的统计方法 —— 那些方法对模块自己毫无意义，
 *       却要把它们的 API 撑大、还要跟着各自的口径演进</li>
 *   <li>统计本来就是**跨域的只读视角**：每个模块自己算不清「系统一共怎么样」</li>
 * </ul>
 *
 * <p><b>什么情况下这个破例就不成立了</b>：一旦大屏开始<b>写</b>别人的表
 * （比如在屏上点一下就把告警置为已处理），就必须改回走各模块的 Service ——
 * 因为那时候它不再只是「看」，而是在做业务动作，而业务动作的归属必须清楚。
 *
 * <h2>三条纪律（都写在设计文档里，这里各有一处对应）</h2>
 * <ol>
 *   <li><b>没权限的块为 null</b>，序列化时字段消失 —— 而不是给 0。
 *       给 0 会让人以为「今天一架次都没飞」（4.3 第 1 条）</li>
 *   <li><b>每个块一条聚合 SQL</b>，不把明细拉回来在 Java 里数（3.2）</li>
 *   <li><b>时间点由 Java 传</b>，不用 SQL 的 {@code CURDATE()} / {@code NOW()}：
 *       mysqld 的时区是 UTC 而我们存本地时间，用数据库的「今天」会整整差一天</li>
 * </ol>
 */
@Slf4j
@Service
public class DashboardServiceImpl implements DashboardService {

    /** 总览里那几条滚动告警取多少条（要更长的走 /dashboard/recent） */
    private static final int SUMMARY_RECENT_LIMIT = 6;

    /** /dashboard/recent 的条数上限，防止有人传个 100000 把库拖住 */
    private static final int RECENT_MAX = 50;

    /**
     * 「设备在线」的时间窗（分钟）。
     *
     * <p>我们没有实时心跳表，「在线」只能定义成「最近这么久内有数据上报」。
     * 取 10 分钟：遥测是每秒一条，10 分钟足够容忍明显的网络抖动，
     * 又不会把一台早就不干活的设备算成在线。
     */
    private static final int ONLINE_WINDOW_MINUTES = 10;

    @Autowired
    private TaskDao taskDao;

    @Autowired
    private AlertDao alertDao;

    @Autowired
    private DeviceDao deviceDao;

    @Autowired
    private UserDao userDao;

    @Autowired
    private TaskEvidenceDao taskEvidenceDao;

    @Autowired
    private SurveyPointDao surveyPointDao;

    @Autowired
    private RouteDao routeDao;

    @Autowired
    private NoFlyZoneDao noFlyZoneDao;

    @Autowired
    private AlertService alertService;

    @Autowired
    private ScreenProperties screenProperties;

    /**
     * 总览与告警流的缓存（见 {@code 业务分析方案.md} 5.2 ②）。
     *
     * <p>它们解决的是**「重复请求」**，不是「第一次查询慢」：
     * 值班室一块屏 + 会议室一块屏 + 某个人在电脑上打开大屏 = 同一秒内三次一模一样的查询。
     * 缓存之后只真算一次。
     *
     * <p>⚠️ **key 必须能区分出「谁能看到哪些块」**（见 {@link #cacheKey}）——
     * 用无参或只用 URL 当 key，会让飞手直接看到管理员的数字。
     * 那不是「缓存没命中」，是**越权**。
     */
    private TtlCache<DashboardSummaryVO> summaryCache;

    private TtlCache<List<DashboardSummaryVO.RecentAlert>> recentCache;

    @PostConstruct
    void initCaches() {
        summaryCache = new TtlCache<>("大屏总览", screenProperties.getSummaryCacheSeconds(), 64);
        recentCache = new TtlCache<>("大屏告警流", screenProperties.getRecentCacheSeconds(), 16);

        // 缓存与业务的配合只有一句话：**同一个身份在 TTL 内只真算一次**。
        // 业务负责「拼 key（含身份）+ 真算一遍」，缓存负责「记住这段时间的结果、并记账」。
        // 后面运行时的每一条未命中、以及每 60 秒的小结，都会把这个账报出来
        log.info("大屏缓存就绪：总览 TTL {} 秒、告警流 TTL {} 秒；key = 用户 id + 排序后的权限"
                        + "（含身份是为了防越权，不是防缓存陈旧）—— 之后未命中记 INFO、命中记 DEBUG、每 60 秒一条小结",
                summaryCache.ttlSeconds(), recentCache.ttlSeconds());

        // 缓存比刷新间隔还长 = 大屏「一直在刷、永远看到同一个值」，比不做缓存更糟（看起来像卡住）。
        // 这种事不该等到有人盯着屏幕才发现，所以启动时就说一声
        if (summaryCache.ttlSeconds() > screenProperties.getRefreshStatSeconds()) {
            log.warn("大屏缓存配置有问题：summary 缓存 {} 秒 > 统计刷新 {} 秒 —— "
                            + "大屏会一直在刷但永远看到同一个值，请把 drone.screen.summary-cache-seconds 调小",
                    summaryCache.ttlSeconds(), screenProperties.getRefreshStatSeconds());
        }
        if (recentCache.ttlSeconds() > screenProperties.getRefreshLiveSeconds()) {
            log.warn("大屏缓存配置有问题：recent 缓存 {} 秒 > 动态刷新 {} 秒，请调小 drone.screen.recent-cache-seconds",
                    recentCache.ttlSeconds(), screenProperties.getRefreshLiveSeconds());
        }
    }

    /**
     * 缓存的 key：**用户 + 权限集合**。
     *
     * <h2>为什么一定要带这两样</h2>
     * 总览是**按权限裁块**的：管理员拿到 8 个块、飞手只拿 4 个。
     * 如果 key 里没有「谁在问」，第二个人的请求就会命中第一个人的结果 ——
     * 飞手会看到「全量任务总数」，那是**越权**，不是「缓存陈旧」。
     *
     * <p>带上 userId 是因为 {@code myTasks}/{@code myAlerts} 是**按人算**的：
     * 两个飞手的权限完全一样，但数据必须不同。
     *
     * <p>权限集合先排序再拼，是为了让「同一组权限」在任何顺序下得到同一个 key
     * （{@code Set} 的迭代顺序不保证稳定，不排序会让缓存条数无故变多、命中率变低）。
     */
    private String cacheKey(LoginUser user) {
        if (user == null) {
            return "anonymous";
        }
        String permissions = user.getPermissions() == null
                ? ""
                : user.getPermissions().stream().sorted().reduce((a, b) -> a + "," + b).orElse("");
        return "u" + user.getId() + "|" + permissions;
    }

    @Override
    public DashboardSummaryVO summary() {
        LoginUser me = UserContext.get();

        // 缓存与业务的配合就在这里，三步：
        //   ① 按「身份」拼 key —— 拼错不是「缓存没命中」，是越权（见 cacheKey 的注释）
        //   ② 交给缓存取：命中就直接返回，业务一行库都不查
        //   ③ 没命中才由这里按权限裁块真算一遍，算完连同**这次的耗时**一起存回去
        // 每一步的日志由 TtlCache 打（未命中 INFO / 命中 DEBUG / 每 60 秒小结），
        // 这样「缓存干了什么」和「业务算了什么」在同一行日志里对得上
        return summaryCache.getOrLoad(
                cacheKey(me),
                "总览（按权限裁块：管理员多、飞手只有自己那份）",
                () -> buildSummary(me));
    }

    /** 真正算一遍总览（缓存没命中时才走到这里） */
    private DashboardSummaryVO buildSummary(LoginUser me) {
        Long myId = (me == null) ? null : me.getId();

        // 时间点在这里算好、传下去（见类注释第 3 条）
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime dayStart = LocalDate.now().atStartOfDay();

        DashboardSummaryVO vo = new DashboardSummaryVO().setGeneratedAt(now);

        // ---- 任务总览（管理侧）----
        if (has(me, "task:list")) {
            Map<String, Long> byStatus = toCountMap(taskDao.countGroupByStatus(null), "status");
            vo.setTasks(new DashboardSummaryVO.Tasks()
                    .setTotal(sum(byStatus))
                    .setByStatus(byStatus)
                    .setTodayNew(taskDao.countCreatedSince(dayStart, null))
                    .setOverdue(taskDao.countOverdue(now, null)));

            // 证据活跃度也归这一档：它回答的是「设备在不在发数据」，只有管理侧关心。
            // 不给飞手看，是因为它和「我今天要干什么」没关系
            vo.setEvidence(new DashboardSummaryVO.Evidence()
                    .setToday(taskEvidenceDao.countSince(dayStart))
                    .setLastHour(taskEvidenceDao.countSince(now.minusHours(1))));
        }

        // ---- 我的任务（飞手侧）----
        // 与上面**不是同一个查询**：那个是全表，这个是 pilot_id = 自己。
        // 结构也刻意不同（pending/doing/done 三档），免得有人把两者弄混。
        //
        // ⚠️ 条件里那个 `!has(task:list)` 不是笔误：这两个块是**二选一**的 ——
        // 「我的任务」是给**看不到总表**的人（飞手）看的；能看总表的人不需要它，
        // 否则管理员屏上会多出一张「我的任务 0」的卡，纯噪音。
        // 管理员如果自己也飞任务，他看的是上面的任务总表（能筛飞手）。
        if (has(me, "task:execute") && !has(me, "task:list") && myId != null) {
            Map<String, Long> mine = toCountMap(taskDao.countGroupByStatus(myId), "status");
            vo.setMyTasks(new DashboardSummaryVO.MyTasks()
                    .setTotal(sum(mine))
                    .setPending(get(mine, "pending"))
                    // 已派发 + 执行中 = 手头正在办的
                    .setDoing(get(mine, "dispatched") + get(mine, "executing"))
                    // 三个终态都算「办完了」（取消也是结束，只是不是完成）
                    .setDone(get(mine, "finished") + get(mine, "failed") + get(mine, "cancelled")));

            vo.setMyAlerts(new DashboardSummaryVO.MyAlerts()
                    .setOpen(sum(toCountMap(alertDao.countOpenGroupBySeverity(myId), "severity"))));
        }

        // ---- 告警总览（管理侧）----
        if (has(me, "alert:list")) {
            Map<String, Long> bySeverity = toCountMap(alertDao.countOpenGroupBySeverity(null), "severity");
            vo.setAlerts(new DashboardSummaryVO.Alerts()
                    .setOpen(sum(bySeverity))
                    .setBySeverity(bySeverity)
                    .setTodayNew(alertDao.countObservedSince(dayStart))
                    .setRecent(recentAlerts(SUMMARY_RECENT_LIMIT)));
        }

        // ---- 设备 ----
        if (has(me, "device:list")) {
            Map<String, Long> byStatus = toCountMap(deviceDao.countGroupByStatus(), "status");
            long total = sum(byStatus);
            Map<String, Object> stats = deviceDao.sumFlightStats();
            long online = taskEvidenceDao.countActiveDevicesSince(now.minusMinutes(ONLINE_WINDOW_MINUTES));

            vo.setDevices(new DashboardSummaryVO.Devices()
                    .setTotal(total)
                    .setByStatus(byStatus)
                    .setTotalFlights(longOf(stats.get("flights")))
                    .setTotalHours(decimalOf(stats.get("hours")))
                    .setOnlineRate(rate(online, total))
                    .setOnlineWindowMinutes(ONLINE_WINDOW_MINUTES));
        }

        // ---- 人员 ----
        if (has(me, "staff:list")) {
            Map<String, Long> byState = toCountMap(userDao.countEnabledGroupByState(), "state");
            long total = sum(byState);
            long idle = get(byState, "idle");
            long busy = get(byState, "busy");
            vo.setStaff(new DashboardSummaryVO.Staff()
                    .setTotal(total)
                    .setIdle(idle)
                    .setBusy(busy)
                    // 剩下的都归「其他」：将来加了新的在线状态，这里不会漏掉人
                    .setOther(Math.max(0, total - idle - busy)));
        }

        // ---- 地理资源：**登录即可**（2026-09-29 拍板：飞手也看）----
        // 理由：只有三个数字、不是明细；而且飞手知道「系统里有几块禁飞区」对他有安全价值。
        // 明细（点位/航线的具体内容）仍然要 route:list / zone:list
        vo.setGeo(new DashboardSummaryVO.Geo()
                .setPoints(surveyPointDao.countByCondition(new SurveyPointQueryParam()))
                .setRoutes(routeDao.countByCondition(new RouteQueryParam()))
                .setZones(noFlyZoneDao.countByCondition(new NoFlyZoneQueryParam())));

        log.debug("大屏总览已生成：user={}，块={}", myId, describeBlocks(vo));
        return vo;
    }

    @Override
    public List<DashboardSummaryVO.RecentAlert> recentAlerts(int limit) {
        int size = Math.min(Math.max(limit, 1), RECENT_MAX);

        // 这条接口只对 alert:list 开放，所有人看到的都是同一份数据 ——
        // 所以 key 只要带条数就够（不像总览那样必须带身份）
        String key = "recent:" + size;
        return recentCache.getOrLoad(
                key,
                "告警流（最近 " + size + " 条，按时间倒序）",
                () -> buildRecent(size));
    }

    /** 真正取一遍告警流（缓存没命中时才走到这里） */
    private List<DashboardSummaryVO.RecentAlert> buildRecent(int size) {
        AlertQueryParam param = new AlertQueryParam();
        param.setPage(1);
        param.setPageSize(size);
        PageResult<AlertVO> page = alertService.pageAlerts(param);

        // 告警中心那个列表按「未处理在前、级别高的在前」排 —— 那是给「我要去处理」的人用的。
        // 大屏这条流回答的是「最近发生了什么」，所以按时间倒序重排一遍
        return page.getList().stream()
                .sorted((a, b) -> {
                    LocalDateTime left = a.getObservedAt();
                    LocalDateTime right = b.getObservedAt();
                    if (left == null || right == null) {
                        return 0;
                    }
                    return right.compareTo(left);
                })
                .map(alert -> new DashboardSummaryVO.RecentAlert()
                        .setLabel(alert.getAlertCodeLabel() == null
                                ? alert.getAlertCode() : alert.getAlertCodeLabel())
                        .setSeverity(alert.getSeverity())
                        .setDeviceCode(alert.getDeviceCode())
                        .setMessage(alert.getMessage())
                        .setObservedAt(alert.getObservedAt()))
                .toList();
    }

    @Override
    public ScreenProperties config() {
        return screenProperties;
    }

    // ------------------------------------------------------------------
    // 小工具
    // ------------------------------------------------------------------

    private boolean has(LoginUser user, String permission) {
        return user != null && user.hasPermission(permission);
    }

    /**
     * 把 {@code [{某列, cnt}, ...]} 收成 {@code Map<值, 条数>}。
     *
     * <p>为什么用 Map 接而不是为每种分组建一个类：分组结果的形状天然就是
     * 「一个值 → 一个条数」，四处分组各建一个只有两个字段的类，
     * 只是把同样两列抄四遍。用 {@code LinkedHashMap} 保持 SQL 给出来的顺序，
     * 前端画图时顺序就是稳定的。
     */
    private static Map<String, Long> toCountMap(List<Map<String, Object>> rows, String keyColumn) {
        Map<String, Long> result = new LinkedHashMap<>();
        if (rows == null) {
            return result;
        }
        for (Map<String, Object> row : rows) {
            Object key = row.get(keyColumn);
            if (key == null) {
                continue;
            }
            result.put(String.valueOf(key), longOf(row.get("cnt")));
        }
        return result;
    }

    private static long sum(Map<String, Long> counts) {
        return counts.values().stream().mapToLong(Long::longValue).sum();
    }

    private static long get(Map<String, Long> counts, String key) {
        return counts.getOrDefault(key, 0L);
    }

    private static long longOf(Object value) {
        return (value instanceof Number number) ? number.longValue() : 0L;
    }

    private static BigDecimal decimalOf(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        return (value instanceof Number number)
                ? BigDecimal.valueOf(number.doubleValue()).setScale(2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
    }

    /** 在线率（0~1，四位小数）。分母为 0 时给 0 —— 一台设备都没有时「在线率」没有意义 */
    private static BigDecimal rate(long online, long total) {
        if (total <= 0) {
            return BigDecimal.ZERO;
        }
        double value = Math.min(1.0, (double) online / total);
        return BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_UP);
    }

    /** 日志里只打「有哪些块」，不打具体数字 —— 排查「为什么某人看不到某块」时看这一行就够 */
    private static String describeBlocks(DashboardSummaryVO vo) {
        StringBuilder text = new StringBuilder();
        if (vo.getTasks() != null) {
            text.append("tasks ");
        }
        if (vo.getMyTasks() != null) {
            text.append("myTasks ");
        }
        if (vo.getAlerts() != null) {
            text.append("alerts ");
        }
        if (vo.getMyAlerts() != null) {
            text.append("myAlerts ");
        }
        if (vo.getDevices() != null) {
            text.append("devices ");
        }
        if (vo.getStaff() != null) {
            text.append("staff ");
        }
        if (vo.getEvidence() != null) {
            text.append("evidence ");
        }
        text.append("geo");
        return text.toString();
    }
}
