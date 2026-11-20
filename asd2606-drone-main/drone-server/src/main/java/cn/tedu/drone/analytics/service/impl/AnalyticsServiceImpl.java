package cn.tedu.drone.analytics.service.impl;

import cn.tedu.drone.analytics.StatMetric;
import cn.tedu.drone.analytics.dao.AnalyticsDao;
import cn.tedu.drone.analytics.dao.StatDailyDao;
import cn.tedu.drone.analytics.pojo.AnalyticsOverviewVO;
import cn.tedu.drone.analytics.pojo.DispatchBlockStatsVO;
import cn.tedu.drone.analytics.pojo.EfficiencyVO;
import cn.tedu.drone.analytics.pojo.EvidenceTrendVO;
import cn.tedu.drone.analytics.service.AnalyticsService;
import cn.tedu.drone.common.BusinessException;
import cn.tedu.drone.task.dispatch.DispatchBlockReason;
import cn.tedu.drone.task.pojo.AlertSeverity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 业务分析查询的实现。
 *
 * <h2>它只做三件事：定区间、取数、算口径</h2>
 * SQL 里能一次算完的（分桶、求和、平均）不搬到这里；搬过来的只有三种：
 * <ul>
 *   <li><b>需要「清单」的</b>：某个归因这一周一个点都没有，SQL 的 GROUP BY 不会给它一行，
 *       但界面上要显示它占 0% —— 补齐清单放在这里</li>
 *   <li><b>需要「横轴」的</b>：趋势图要把没有数据的那几天也画出来（补 0），
 *       而 SQL 只知道「有数据的那几天」</li>
 *   <li><b>SQL 写不清楚的</b>：中位数。MySQL 没有 {@code PERCENTILE_CONT}，
 *       用窗口函数绕出来那段 SQL 没人能维护，而区间内只有几百行，拉回来算更清楚</li>
 * </ul>
 *
 * <h2>默认区间与校验只有一份</h2>
 * 两个接口在同一个页面上，如果各算各的默认区间，页面上会出现「上半屏是本周、
 * 下半屏是本月」这种对不上的画面。所以 {@link #resolveRange} 是唯一的入口。
 */
@Slf4j
@Service
public class AnalyticsServiceImpl implements AnalyticsService {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 默认区间：最近 7 天 —— 短到能看出「这周是不是又攒活了」，长到能有一批样本 */
    private static final int DEFAULT_RANGE_DAYS = 7;

    /**
     * 区间上限：一年。
     *
     * <p>不是怕慢（现在这个数据量扫一年也就几百行），而是**口径**问题：
     * 一年以上就该走预聚合日表（第三步），实时扫明细出来的数没有意义 ——
     * 而且归因点本身只攒了一年（见 8.3 的冷热分流）。
     */
    private static final int MAX_RANGE_DAYS = 366;

    /**
     * 告警趋势的线条顺序：严重的画在最上面。
     *
     * <p>写在这里而不是加进 {@link AlertSeverity}：那是**词汇表**（有哪些取值），
     * 而「哪条线画在上面」是这一屏的展示决定。
     */
    private static final List<String> SEVERITY_ORDER =
            List.of(AlertSeverity.CRITICAL, AlertSeverity.WARNING, AlertSeverity.INFO);

    @Autowired
    private AnalyticsDao analyticsDao;

    /** 预聚合表的读写（读的时候要判断哪几天算过，见 evidenceTrend） */
    @Autowired
    private StatDailyDao statDailyDao;

    /** 口径版本：读的时候只看这个版本的行，否则会读到按旧口径算的历史 */
    @Value("${drone.analytics.stat-daily.caliber-version:1}")
    private int caliberVersion;

    /**
     * 实时补算超过这么多天就记 warn（正常只有「今天」一天）。
     *
     * <p>3 天是个经验值：周末连休 + 一次重启也不该超过它，
     * 超过了基本就是补算任务没在跑。
     */
    private static final int LIVE_TAIL_WARN_DAYS = 3;

    /**
     * 设备利用率的「每日可飞小时」：可飞时长 = 在役天数 × 这个值。
     *
     * <p>⚠️ 它是**近似** —— 维护/充电的日子也算进分母，所以算出来偏低（保守）。
     * 要做准就得有设备状态变更历史，而那件事已经拍板不做（见文档 8.2 第 7 条）。
     * 做成配置是因为「一天能飞几小时」会随作业类型变（巡检班次 vs 全天待命）。
     */
    @Value("${drone.analytics.flyable-hours-per-day:8}")
    private int flyableHoursPerDay;

    // ------------------------------------------------------------------
    // 第一步：任务与告警概览
    // ------------------------------------------------------------------

    @Override
    public AnalyticsOverviewVO overview(LocalDateTime from, LocalDateTime to) {
        Range range = resolveRange(from, to);
        List<String> days = dayAxis(range.start(), range.end());

        return new AnalyticsOverviewVO()
                .setFrom(range.start().format(TIME))
                .setTo(range.end().format(TIME))
                .setDays(days)
                .setTasks(taskOverview(range, days))
                .setAlerts(alertOverview(range, days));
    }

    private AnalyticsOverviewVO.Tasks taskOverview(Range range, List<String> days) {
        Map<String, Long> createdByDay = new HashMap<>();
        for (Map<String, Object> row : analyticsDao.countTaskCreatedByDay(
                range.start(), range.end(), shiftSeconds())) {
            createdByDay.put(String.valueOf(row.get("day")), num(row.get("cnt")));
        }
        List<Long> created = fillDays(days, createdByDay);

        Map<String, Object> outcome = analyticsDao.countTaskOutcome(range.start(), range.end());
        long finished = num(outcome, "finished");
        long failed = num(outcome, "failed");
        long onTimeBase = num(outcome, "onTimeBase");
        long durationBase = num(outcome, "durationBase");

        return new AnalyticsOverviewVO.Tasks()
                .setCreated(created)
                .setCreatedTotal(created.stream().mapToLong(Long::longValue).sum())
                .setFinished(finished)
                .setFailed(failed)
                // 没有「结束过的活」时给 null（界面显示「—」），不给 0 ——
                // 0% 是在说「全失败了」，而那段时间可能什么都没结束
                .setFinishRate(finished + failed == 0 ? null : percent(finished, finished + failed))
                .setOnTimeBase(onTimeBase)
                .setOnTimeRate(onTimeBase == 0 ? null : percent(num(outcome, "onTime"), onTimeBase))
                .setDurationBase(durationBase)
                .setAvgMinutes(rounded(outcome, "avgMinutes"));
    }

    private AnalyticsOverviewVO.Alerts alertOverview(Range range, List<String> days) {
        // severity → day → 条数。先按固定顺序铺好三个级别，
        // 于是图上图例的顺序与颜色不随数据变
        Map<String, Map<String, Long>> bySeverity = new LinkedHashMap<>();
        for (String severity : SEVERITY_ORDER) {
            bySeverity.put(severity, new HashMap<>());
        }
        for (Map<String, Object> row : analyticsDao.countAlertByDayAndSeverity(range.start(), range.end())) {
            String severity = String.valueOf(row.get("severity"));
            // 认不出来的级别也照收（拼错的词是脏数据，但把它藏起来更难发现）——
            // 它会多出一条线，中文名兜底成原样
            bySeverity.computeIfAbsent(severity, key -> new HashMap<>())
                    .merge(String.valueOf(row.get("day")), num(row.get("cnt")), Long::sum);
        }

        List<AnalyticsOverviewVO.AlertSeries> trend = new ArrayList<>();
        for (Map.Entry<String, Map<String, Long>> entry : bySeverity.entrySet()) {
            trend.add(new AnalyticsOverviewVO.AlertSeries()
                    .setSeverity(entry.getKey())
                    .setLabel(AlertSeverity.label(entry.getKey()))
                    .setValues(fillDays(days, entry.getValue())));
        }

        Map<String, Object> summary = analyticsDao.countAlertSummary(range.start(), range.end());
        return new AnalyticsOverviewVO.Alerts()
                .setTrend(trend)
                .setTotal(num(summary, "total"))
                .setCritical(num(summary, "critical"))
                .setHandled(num(summary, "handled"))
                .setMttrMinutes(rounded(summary, "mttrMinutes"))
                .setOpenNow(num(summary, "openNow"));
    }

    // ------------------------------------------------------------------
    // 第二步半：待派发积压的归因
    // ------------------------------------------------------------------

    @Override
    public DispatchBlockStatsVO dispatchBlockStats(LocalDateTime from, LocalDateTime to) {
        Range range = resolveRange(from, to);

        DispatchBlockStatsVO vo = new DispatchBlockStatsVO()
                .setFrom(range.start().format(TIME))
                .setTo(range.end().format(TIME));

        fillReasonShare(vo, range);
        fillBacklogAge(vo);
        fillCreateToDispatch(vo, range);
        return vo;
    }

    /** ① 归因占比：区间内的归因点按归因分组 */
    private void fillReasonShare(DispatchBlockStatsVO vo, Range range) {
        Map<String, Long> counts = new HashMap<>();
        for (Map<String, Object> row : analyticsDao.countBlockReasonGroup(range.start(), range.end())) {
            counts.put(String.valueOf(row.get("reason")), num(row.get("cnt")));
        }

        long total = counts.values().stream().mapToLong(Long::longValue).sum();
        List<DispatchBlockStatsVO.ReasonShare> shares = new ArrayList<>();
        // 按**清单**补齐，而不是照着查询结果给：一个归因这周一个点都没有时，
        // SQL 里没有这一行，但界面上要显示「缺飞手 0%」——
        // 而且环形图的图例才能保持稳定，不会每刷新一次换一套颜色
        for (String reason : DispatchBlockReason.ALL) {
            long count = counts.getOrDefault(reason, 0L);
            shares.add(new DispatchBlockStatsVO.ReasonShare()
                    .setReason(reason)
                    .setLabel(DispatchBlockReason.label(reason))
                    .setCount(count)
                    .setPercent(percent(count, total)));
        }
        // 多的排前面：一眼能看到主要矛盾。同一数量时按清单顺序（稳定性）
        shares.sort(Comparator.comparingLong(DispatchBlockStatsVO.ReasonShare::getCount).reversed());

        vo.setBlockPoints(total)
                .setReasonShare(shares)
                .setSampledHours(analyticsDao.countBlockSampledHours(range.start(), range.end()))
                .setExpectedHours(ChronoUnit.HOURS.between(range.start(), range.end()));
    }

    /**
     * ② 积压时长分布：此刻还卡在待派发的任务等了多久。
     *
     * <p><b>它是快照，不随区间变</b> —— 区间说的是「历史那段为什么攒下来」，
     * 这里说的是「现在还剩多少」，两者本就是两个问题（见 VO 上的说明）。
     *
     * <p>分桶的分界点在 SQL 里用 {@code NOW()} 算，不从 Java 传「现在」——
     * {@code task.create_time} 是 mysqld 用自己的时钟填的，与 Java 的墙上时间不同源，
     * 掺着用会让刚创建的任务被判成「等了一天」（见 AnalyticsDao 类注释）。
     */
    private void fillBacklogAge(DispatchBlockStatsVO vo) {
        Map<String, Object> row = analyticsDao.countPendingByAge();

        long lt1h = row == null ? 0L : num(row.get("lt1h"));
        long lt1d = row == null ? 0L : num(row.get("lt1d"));
        long ge1d = row == null ? 0L : num(row.get("ge1d"));

        vo.setPendingTotal(lt1h + lt1d + ge1d)
                .setBacklogAge(List.of(
                        new DispatchBlockStatsVO.BacklogBucket().setKey("lt1h").setLabel("1 小时内").setCount(lt1h),
                        new DispatchBlockStatsVO.BacklogBucket().setKey("lt1d").setLabel("1 小时 ~ 1 天").setCount(lt1d),
                        new DispatchBlockStatsVO.BacklogBucket().setKey("ge1d").setLabel("超过 1 天").setCount(ge1d)));
    }

    /** ③ 从创建到派发的中位时长：管理动作的反应速度 */
    private void fillCreateToDispatch(DispatchBlockStatsVO vo, Range range) {
        List<Long> durations = analyticsDao.selectDispatchDurations(range.start(), range.end());
        vo.setDispatchedSamples(durations == null ? 0 : durations.size())
                .setMedianMinutes(median(durations));
    }

    // ------------------------------------------------------------------
    // 第二步：飞手与设备效率
    // ------------------------------------------------------------------

    /**
     * 两个榜单都从**花名册**出发，再往里填数。
     *
     * <p>反着做（照着查询结果出行）会漏掉「这段时间一条活都没有的人/设备」——
     * 而「谁忙谁闲」里的「闲」正是靠他们体现的。只列有数据的人，这一页就答不了那个问题。
     */
    @Override
    public EfficiencyVO efficiency(LocalDateTime from, LocalDateTime to) {
        Range range = resolveRange(from, to);
        int rangeDays = (int) ChronoUnit.DAYS.between(range.start().toLocalDate(), range.end().toLocalDate());

        return new EfficiencyVO()
                .setFrom(range.start().format(TIME))
                .setTo(range.end().format(TIME))
                .setRangeDays(rangeDays)
                .setFlyableHoursPerDay(flyableHoursPerDay)
                .setPilots(pilotRows(range))
                .setDevices(deviceRows(range, rangeDays));
    }

    private List<EfficiencyVO.PilotRow> pilotRows(Range range) {
        Map<Long, Map<String, Object>> outcome = indexBy(
                analyticsDao.countPilotOutcome(range.start(), range.end()), "pilotId");
        Map<Long, Map<String, Object>> evidence = indexBy(
                analyticsDao.countPilotEvidence(range.start(), range.end()), "pilotId");
        Map<Long, Map<String, Object>> actions = indexBy(
                analyticsDao.countPilotActions(range.start(), range.end(), shiftSeconds()), "pilotId");

        List<EfficiencyVO.PilotRow> rows = new ArrayList<>();
        for (Map<String, Object> pilot : analyticsDao.selectPilotRoster()) {
            Long pilotId = longValue(pilot.get("id"));
            Map<String, Object> o = outcome.getOrDefault(pilotId, Map.of());
            Map<String, Object> e = evidence.getOrDefault(pilotId, Map.of());
            Map<String, Object> a = actions.getOrDefault(pilotId, Map.of());

            // SQL 里已经把「他拒掉的」算进了 offers（拒绝的释放动作会清空 pilot_id，
            // 只数在册的派发会漏掉它），所以这里直接用，不要再加一次 refused
            long offers = num(a, "offers");
            long refused = num(a, "refused");
            long finished = num(o, "finished");
            long taskCount = num(o, "taskCount");
            long onTimeBase = num(o, "onTimeBase");
            long started = num(e, "started");
            long noProof = num(e, "noProof");

            rows.add(new EfficiencyVO.PilotRow()
                    .setPilotId(pilotId)
                    .setName(String.valueOf(pilot.get("name")))
                    .setOffers((int) offers)
                    .setRefused((int) refused)
                    // 一次都没派过的人，拒绝率不是 0% 而是「无从谈起」——给 null，界面显示「—」
                    .setRefuseRate(offers == 0 ? null : percent(refused, offers))
                    .setTaskCount((int) taskCount)
                    .setFinished((int) finished)
                    .setOnTimeRate(onTimeBase == 0 ? null : percent(num(o, "onTime"), onTimeBase))
                    .setStarted((int) started)
                    .setNoProof((int) noProof)
                    // 区间内一条活都没开过的人，同样给 null：占比的分母没意义
                    .setNoProofRate(started == 0 ? null : percent(noProof, started))
                    .setHours(decimal(o, "hours"))
                    .setSorties((int) num(o, "sorties")));
        }

        // 干得多的排前面；一样多按 id 排（结果稳定，不会每次刷新换顺序）
        rows.sort(Comparator.comparingInt(EfficiencyVO.PilotRow::getTaskCount).reversed()
                .thenComparing(EfficiencyVO.PilotRow::getPilotId));
        return rows;
    }

    private List<EfficiencyVO.DeviceRow> deviceRows(Range range, int rangeDays) {
        Map<Long, Map<String, Object>> reports = indexBy(
                analyticsDao.countDeviceReports(range.start(), range.end()), "deviceId");
        Map<String, Map<String, Object>> alerts = indexByCode(
                analyticsDao.countDeviceAlerts(range.start(), range.end()), "deviceCode");

        List<EfficiencyVO.DeviceRow> rows = new ArrayList<>();
        for (Map<String, Object> device : analyticsDao.selectDeviceRoster()) {
            String code = String.valueOf(device.get("code"));
            Map<String, Object> r = reports.getOrDefault(longValue(device.get("id")), Map.of());
            Map<String, Object> alert = alerts.getOrDefault(code, Map.of());

            int inServiceDays = inServiceDays(text(device.get("purchaseDate")), range, rangeDays);
            // 可飞时长把维护/充电的日子也算进去了（见 VO 上的 ⚠️），所以利用率偏低
            double flyable = inServiceDays * (double) flyableHoursPerDay;
            double hours = orZero(decimal(r, "hours"));
            long alertCount = num(alert, "alerts");

            rows.add(new EfficiencyVO.DeviceRow()
                    .setDeviceId(longValue(device.get("id")))
                    .setCode(code)
                    .setName(String.valueOf(device.get("name")))
                    .setStatus(text(device.get("status")))
                    .setInServiceDays(inServiceDays)
                    .setFlyableHours(round1(flyable))
                    .setHours(decimal(r, "hours"))
                    .setSorties((int) num(r, "sorties"))
                    .setUtilization(flyable <= 0 ? null : percent2(hours, flyable))
                    .setAlerts((int) alertCount)
                    .setCriticalAlerts((int) num(alert, "criticalAlerts"))
                    // 没飞过的设备谈不上「每 100 小时报几次警」——给 null，界面显示「—」
                    .setFaultRatePer100h(hours <= 0 ? null : percent2(alertCount, hours)));
        }

        // 飞得多的排前面；一样多按编号（稳定）
        rows.sort(Comparator.comparingDouble((EfficiencyVO.DeviceRow row) -> orZero(row.getHours())).reversed()
                .thenComparing(EfficiencyVO.DeviceRow::getCode));
        return rows;
    }

    /**
     * 一台设备在区间内的**在役天数**：与「采购日 → 区间末」取交集。
     *
     * <p>没有采购日期的按整段算 —— 它至少是在的，当 0 天会让利用率变成「除以 0」。
     * 采购日晚于区间末（还没买）才是 0 天，那时不产生可飞时长，利用率给 null。
     */
    private int inServiceDays(String purchaseDate, Range range, int rangeDays) {
        if (purchaseDate == null || purchaseDate.isBlank()) {
            return rangeDays;
        }
        LocalDate start = range.start().toLocalDate();
        LocalDate purchase = LocalDate.parse(purchaseDate);
        LocalDate effectiveStart = purchase.isAfter(start) ? purchase : start;
        long days = ChronoUnit.DAYS.between(effectiveStart, range.end().toLocalDate());
        return (int) Math.max(0, days);
    }

    // ------------------------------------------------------------------
    // 第三步：证据趋势（历史读预聚合，今天实时补）
    // ------------------------------------------------------------------

    @Override
    public EvidenceTrendVO evidenceTrend(LocalDateTime from, LocalDateTime to) {
        Range range = resolveRange(from, to);
        List<String> days = dayAxis(range.start(), range.end());

        EvidenceTrendVO vo = new EvidenceTrendVO()
                .setFrom(range.start().format(TIME))
                .setTo(range.end().format(TIME))
                .setDays(days);
        if (days.isEmpty()) {
            return vo.setEvidenceCounts(List.of()).setActiveDevices(List.of())
                    .setPrecomputedDays(0).setLiveDays(0);
        }

        LocalDate firstDay = range.start().toLocalDate();
        LocalDate lastDay = LocalDate.parse(days.get(days.size() - 1));   // 右开区间里的最后一天

        // ① 预聚合：两个指标一次取回来（metric → day → 合计）
        Map<String, Map<String, Long>> precomputed = new HashMap<>();
        for (Map<String, Object> row : statDailyDao.selectDailyTotals(
                StatMetric.DAILY_METRICS, caliberVersion, firstDay, lastDay)) {
            precomputed.computeIfAbsent(String.valueOf(row.get("metric")), key -> new HashMap<>())
                    .put(String.valueOf(row.get("statDate")), num(row, "total"));
        }
        // 「算过没有」看那个每天都写的指标（见 StatMetric.PRESENCE）——
        // 空数据的那天也留着 0 的行，所以这里能分清「没算」与「算了、那天确实是 0」
        Map<String, Long> presence = precomputed.getOrDefault(StatMetric.PRESENCE, Map.of());

        // ② 没算过的那几天现场从明细算（正常只有今天；补算任务漏跑时会有更多）
        Map<String, long[]> live = liveTail(days, presence);

        // ③ 按横轴拼两条序列
        Map<String, Long> counts = precomputed.getOrDefault(StatMetric.EVIDENCE_COUNT, Map.of());
        List<Long> evidenceCounts = new ArrayList<>(days.size());
        List<Long> activeDevices = new ArrayList<>(days.size());
        int precomputedDays = 0;
        for (String day : days) {
            if (presence.containsKey(day)) {
                evidenceCounts.add(counts.getOrDefault(day, 0L));
                activeDevices.add(presence.getOrDefault(day, 0L));
                precomputedDays++;
            } else {
                long[] point = live.get(day);
                evidenceCounts.add(point == null ? 0L : point[0]);
                activeDevices.add(point == null ? 0L : point[1]);
            }
        }

        return vo.setEvidenceCounts(evidenceCounts)
                .setActiveDevices(activeDevices)
                .setPrecomputedDays(precomputedDays)
                .setLiveDays(days.size() - precomputedDays);
    }

    /**
     * 把「预聚合还没覆盖的那几天」现场从明细算出来。
     *
     * <p>正常情况下这里只有「今天」一天（几台设备几十分钟的数据），代价可以忽略；
     * 补算任务停过一阵子时会一次补好几天 —— 那就回到明细表的代价了，
     * 所以超过几天就记一条 warn：这是**任务没在跑**的信号，不是查询设计的问题。
     */
    private Map<String, long[]> liveTail(List<String> days, Map<String, Long> presence) {
        List<String> uncovered = days.stream().filter(day -> !presence.containsKey(day)).toList();
        Map<String, long[]> live = new HashMap<>();
        if (uncovered.isEmpty()) {
            return live;
        }

        // 一次查完这段（含中间已覆盖的天，多查一点换取「只发一条 SQL」）
        LocalDate from = LocalDate.parse(uncovered.get(0));
        LocalDate to = LocalDate.parse(uncovered.get(uncovered.size() - 1)).plusDays(1);
        Map<String, Set<String>> devicesByDay = new HashMap<>();
        for (Map<String, Object> row : analyticsDao.countEvidenceByDayAndDevice(
                from.atStartOfDay(), to.atStartOfDay())) {
            String day = String.valueOf(row.get("day"));
            devicesByDay.computeIfAbsent(day, key -> new HashSet<>())
                    .add(String.valueOf(row.get("deviceCode")));
            live.computeIfAbsent(day, key -> new long[2])[0] += num(row, "cnt");
        }
        // 活跃设备数 = 那天出现过的设备个数：与「每天多少条」同一份数据换个数法，不多查一次
        devicesByDay.forEach((day, devices) ->
                live.computeIfAbsent(day, key -> new long[2])[1] = devices.size());

        if (uncovered.size() > LIVE_TAIL_WARN_DAYS) {
            log.warn("证据趋势里 {} 天没有预聚合数据，本次临时从明细算（正常只该有「今天」一天）——"
                            + "看一下补算任务是不是没在跑：drone.analytics.stat-daily.enabled / interval-ms",
                    uncovered.size());
        }
        return live;
    }

    // ------------------------------------------------------------------
    // 共用的口径与工具
    // ------------------------------------------------------------------

    /**
     * 区间：终点默认「明天 0 点」，起点默认「终点往前 7 天」。
     *
     * <p>终点为什么不用「此刻」：那会让今天这一整天的数据有一半落在区间外，
     * 页面上看起来就像「今天什么都没发生」。
     */
    private Range resolveRange(LocalDateTime from, LocalDateTime to) {
        LocalDateTime end = to != null ? to : LocalDate.now().plusDays(1).atStartOfDay();
        LocalDateTime start = from != null ? from : end.minusDays(DEFAULT_RANGE_DAYS);

        if (!start.isBefore(end)) {
            throw new BusinessException("时间范围的起点必须早于终点");
        }
        if (ChronoUnit.DAYS.between(start, end) > MAX_RANGE_DAYS) {
            throw new BusinessException("时间范围最长 " + MAX_RANGE_DAYS + " 天");
        }
        return new Range(start, end);
    }

    /**
     * 趋势图共用的横轴：区间内的每一天（右开，所以终点那天不含）。
     *
     * <p>格式与 SQL 里 {@code DATE_FORMAT(..., '%Y-%m-%d')} 一致 —— 两边对不上，
     * 补 0 就会补出一整条空白线（这是个很难看一眼看出来的坑）。
     */
    private List<String> dayAxis(LocalDateTime from, LocalDateTime to) {
        List<String> days = new ArrayList<>();
        LocalDate cursor = from.toLocalDate();
        LocalDate last = to.toLocalDate();
        while (cursor.isBefore(last)) {
            days.add(cursor.toString());
            cursor = cursor.plusDays(1);
        }
        return days;
    }

    /** 把「有数据的那几天」铺满整条横轴：没有数据的那天补 0，而不是断在中间 */
    private List<Long> fillDays(List<String> axis, Map<String, Long> byDay) {
        List<Long> values = new ArrayList<>(axis.size());
        for (String day : axis) {
            values.add(byDay.getOrDefault(day, 0L));
        }
        return values;
    }

    /**
     * {@code create_time} 这类「mysqld 填的」时间要平移多少秒，才落在应用侧的日界上。
     *
     * <p>= 应用时区相对 UTC 的偏移 − mysqld 相对 UTC 的偏移。
     * 应用与库本来就在同一时区时它是 0，一次运算都不会影响结果；
     * 库跑在 UTC 而应用在东八区时它是 28800，正是那个「差 8 小时」。
     * <b>从库里问出来、而不是写死 +08:00</b>：写死的那份会在换台机器时悄悄算错。
     */
    private int shiftSeconds() {
        long appOffset = ZoneId.systemDefault().getRules().getOffset(Instant.now()).getTotalSeconds();
        return (int) (appOffset - analyticsDao.selectServerClockOffsetSeconds());
    }

    /**
     * 中位数：偶数个取中间两个的平均（四舍五入到分钟）。
     *
     * <p>为什么不用平均数：只要有一条任务被忘了一周，平均数就被它一个人拉走 ——
     * 而「被忘了」恰恰是这个指标要暴露的事，不该被它自己掩盖掉。
     */
    private Long median(List<Long> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        List<Long> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int size = sorted.size();
        if (size % 2 == 1) {
            return sorted.get(size / 2);
        }
        return Math.round((sorted.get(size / 2 - 1) + sorted.get(size / 2)) / 2.0);
    }

    /**
     * 占比（%，一位小数）；总数为 0 时给 0，不给 NaN（NaN 会变成 JSON 里的 null 或报错）。
     *
     * <p>用在人看的比率上（按时率、拒绝率、无旁证占比）—— 它们是几十个百分点的量级。
     */
    private double percent(long count, long total) {
        if (total <= 0) {
            return 0.0;
        }
        return Math.round(count * 1000.0 / total) / 10.0;
    }

    /**
     * 占比（%，**两位小数**）。
     *
     * <p>只用在两个「天然很小」的比率上：设备的利用率与百小时故障率。
     * 它们的分母是「整段区间的可飞时长 / 全部飞行时长」，按一位小数会全变成 0.0% ——
     * 那不是精度问题，是这一列直接失去信息。
     */
    private double percent2(double count, double total) {
        if (total <= 0) {
            return 0.0;
        }
        return Math.round(count * 10000.0 / total) / 100.0;
    }

    /** 保留一位小数（可飞时长这类带小数的中间量） */
    private double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    private double orZero(Double value) {
        return value == null ? 0.0 : value;
    }

    /** 把「按某个键分组」的查询结果收成 Map，方便按 id/编号回填到花名册那一行 */
    private Map<Long, Map<String, Object>> indexBy(List<Map<String, Object>> rows, String key) {
        Map<Long, Map<String, Object>> indexed = new HashMap<>();
        for (Map<String, Object> row : rows) {
            indexed.put(longValue(row.get(key)), row);
        }
        return indexed;
    }

    private Map<String, Map<String, Object>> indexByCode(List<Map<String, Object>> rows, String key) {
        Map<String, Map<String, Object>> indexed = new HashMap<>();
        for (Map<String, Object> row : rows) {
            indexed.put(String.valueOf(row.get(key)), row);
        }
        return indexed;
    }

    private long longValue(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /** 小数（聚合出来的 SUM 是 BigDecimal），没有值时给 null —— 与「等于 0」是两回事 */
    private Double decimal(Map<String, Object> row, String key) {
        Object value = row == null ? null : row.get(key);
        return value instanceof Number number ? number.doubleValue() : null;
    }

    /** 聚合里没命中时给 NULL / 空集时给 NULL，这里统一当 0 —— 别让 null 一路漏到界面上 */
    private long num(Map<String, Object> row, String key) {
        return row == null ? 0L : num(row.get(key));
    }

    private long num(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    /**
     * 平均分钟数（{@code AVG} 给的是 BigDecimal）四舍五入成整数；
     * **没有样本时返回 null 而不是 0** —— 0 分钟是在说「一眨眼就干完了」。
     */
    private Long rounded(Map<String, Object> row, String key) {
        Object value = row == null ? null : row.get(key);
        return value instanceof Number number ? Math.round(number.doubleValue()) : null;
    }

    /** 规范化之后的区间（左闭右开） */
    private record Range(LocalDateTime start, LocalDateTime end) {
    }
}
