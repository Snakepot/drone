package cn.tedu.drone.analytics.service.impl;

import cn.tedu.drone.analytics.StatMetric;
import cn.tedu.drone.analytics.dao.AnalyticsDao;
import cn.tedu.drone.analytics.dao.StatDailyDao;
import cn.tedu.drone.analytics.pojo.StatDailyPO;
import cn.tedu.drone.analytics.service.StatDailyService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 预聚合的写与补算（见 {@code docs/业务分析方案.md} 4.3 ③）。
 *
 * <h2>算一天只做两件事：读明细一次，写两行/几行</h2>
 * 一天的数据量是「设备数 × 条数」级别（比如 10 台 × 5400 条），
 * 聚合出来的行数是「设备数 + 1」—— 这正是预聚合的性价比来源：
 * 算一次要读一天的明细，但之后任何查询都只读那几行。
 *
 * <h2>为什么「按天算」而不是「按区间算」</h2>
 * 按天算的好处是**可重算的单位就是天**：某天算错了，重算那一天就行，
 * 不会牵动邻居。区间版本（一次算一年）只在初次回填时快，但出错就要整段重来。
 */
@Slf4j
@Service
public class StatDailyServiceImpl implements StatDailyService {

    @Autowired
    private AnalyticsDao analyticsDao;

    @Autowired
    private StatDailyDao statDailyDao;

    /**
     * 口径版本。口径改了要 +1 再整段重算 ——
     * 否则同一张表里混着两套口径的数据，趋势图上会突然断层（见 4.3 ③）。
     */
    @Value("${drone.analytics.stat-daily.caliber-version:1}")
    private int caliberVersion;

    @Override
    @Transactional
    public int rebuildDay(LocalDate day) {
        LocalDateTime from = day.atStartOfDay();
        LocalDateTime to = day.plusDays(1).atStartOfDay();

        // 一条查询既是「粒度行」的来源，也是「活跃设备数」的来源（同一份数据，两种看法）
        List<Map<String, Object>> byDevice = analyticsDao.countEvidenceByDayAndDevice(from, to);

        // ① 先删：那天如果有设备不再有数据（或改了名），光 upsert 会留下「幽灵设备」
        statDailyDao.deleteByDay(day, caliberVersion);

        // ② 粒度行：每天每台设备一行
        List<StatDailyPO> rows = new ArrayList<>(byDevice.size() + 1);
        Set<String> devices = new HashSet<>();
        for (Map<String, Object> row : byDevice) {
            String deviceCode = String.valueOf(row.get("deviceCode"));
            devices.add(deviceCode);
            rows.add(new StatDailyPO()
                    .setStatDate(day)
                    .setMetric(StatMetric.EVIDENCE_COUNT)
                    .setDimension(deviceCode)
                    .setValue(BigDecimal.valueOf(num(row.get("cnt"))))
                    .setCaliberVersion(caliberVersion));
        }

        // ③ 日级行：活跃设备数。**哪怕 0 也要写** —— 它是「这天算过没有」的标记，
        //    见 StatMetric.ACTIVE_DEVICES 上的说明（不写的话，空数据那天永远像没算过）
        rows.add(new StatDailyPO()
                .setStatDate(day)
                .setMetric(StatMetric.ACTIVE_DEVICES)
                .setDimension(StatMetric.NO_DIMENSION)
                .setValue(BigDecimal.valueOf(devices.size()))
                .setCaliberVersion(caliberVersion));

        statDailyDao.upsertBatch(rows);
        return rows.size();
    }

    @Override
    public int rebuildRange(LocalDate from, LocalDate to) {
        int days = 0;
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            rebuildDay(day);
            days++;
        }
        log.info("预聚合重算完成：{} → {}，共 {} 天（口径版本 {}）", from, to, days, caliberVersion);
        return days;
    }

    @Override
    public int ensureUpToDate(LocalDate today, int lookbackDays) {
        LocalDate to = today.minusDays(1);                 // 今天还在涨，留给读的时候实时补
        LocalDate from = to.minusDays(Math.max(1, lookbackDays) - 1L);
        if (from.isAfter(to)) {
            return 0;
        }

        // 只看那个「每天都写」的指标来判断哪天算过（见 StatMetric.PRESENCE）
        Set<LocalDate> computed = new HashSet<>(
                statDailyDao.selectComputedDates(StatMetric.PRESENCE, caliberVersion, from, to));

        int rebuilt = 0;
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            if (computed.contains(day)) {
                continue;
            }
            rebuildDay(day);
            rebuilt++;
        }

        if (rebuilt > 0) {
            log.info("预聚合补算：回看 {} 天，补了 {} 天（口径版本 {}）",
                    lookbackDays, rebuilt, caliberVersion);
        }
        return rebuilt;
    }

    private long num(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }
}
