package cn.tedu.drone.analytics.job;

import cn.tedu.drone.analytics.service.StatDailyService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * 预算「昨天及以前」的预聚合（见 {@code docs/业务分析方案.md} 4.3 ③）。
 *
 * <h2>为什么不用 cron 定在凌晨 3 点</h2>
 * 因为**它算得起**：算一天只要几十毫秒（读一天的明细、写几行），
 * 所以「每 30 分钟看一次有没有该算的天」比「定死在凌晨 3 点」省心得多：
 * <ul>
 *   <li>服务重启、周末没人看、机器半夜关机 —— 都不会漏掉那一天（下一轮自动补上）</li>
 *   <li>不用维护一个「几点跑」的配置，也不用担心 cron 表达式写错</li>
 * </ul>
 * 算出来的数只服务按天/按周看的分析，所以「晚几个小时」没有任何影响 ——
 * 真正对时效敏感的是「今天」，那一段由读的时候实时补（见 AnalyticsService.evidenceTrend）。
 *
 * <h2>它只做「补齐」，不做「重算」</h2>
 * 算错某天、或那天之后才补传了数据，要用 {@code StatDailyService.rebuildDay/Range}
 * 定点重算 —— 定时任务每次都重算一遍一年的话，就让这张表的性价比没意义了。
 *
 * <h2>⚠️ 这个类上不能放 {@code @EnableScheduling}</h2>
 * 它是全局开关，跟着一个带 {@code @ConditionalOnProperty} 的类一起消失，
 * 会让所有定时任务静默停摆 —— 详见启动类上的注释。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "drone.analytics.stat-daily.enabled", havingValue = "true", matchIfMissing = true)
public class StatDailyJob {

    @Autowired
    private StatDailyService statDailyService;

    /** 往回看多少天找空洞（停机、漏跑留下的）。默认 30 天够覆盖大多数意外 */
    @Value("${drone.analytics.stat-daily.lookback-days:30}")
    private int lookbackDays;

    /**
     * 每 30 分钟一轮。用 fixedDelay 而不是 cron：上一轮跑完再计时，不会叠着跑；
     * 频率做成配置是因为「数据多久算一次」完全看业务节奏。
     */
    @Scheduled(fixedDelayString = "${drone.analytics.stat-daily.interval-ms:1800000}")
    public void backfill() {
        try {
            statDailyService.ensureUpToDate(LocalDate.now(), lookbackDays);
        } catch (Exception e) {
            // 一个补算任务失败不该把调度线程带崩（下一次还会跑）
            log.error("预聚合补算失败，本轮跳过", e);
        }
    }
}
