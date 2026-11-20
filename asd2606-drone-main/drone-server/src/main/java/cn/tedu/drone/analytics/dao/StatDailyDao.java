package cn.tedu.drone.analytics.dao;

import cn.tedu.drone.analytics.pojo.StatDailyPO;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 预聚合日表 {@code stat_daily} 的读写（见 {@code docs/业务分析方案.md} 4.3 ③）。
 *
 * <h2>写路径的两条纪律</h2>
 * <ol>
 *   <li><b>幂等</b>：重算同一天不能翻倍。靠唯一键
 *       {@code (stat_date, metric, dimension, caliber_version)} + upsert 做到 ——
 *       所以「按日期重算」可以随便重跑，这也是补算任务敢每半小时看一次的原因</li>
 *   <li><b>先删后插</b>：某天如果有设备当天不再有数据（或设备改了名），光 upsert 会把旧行留着，
 *       趋势就会多出一台「幽灵设备」。所以重算一天是 {@code delete} + {@code insert} 两步</li>
 * </ol>
 */
public interface StatDailyDao {

    /** 批量 upsert（一行 = 某天某指标某维度的一个值） */
    int upsertBatch(@Param("rows") List<StatDailyPO> rows);

    /**
     * 清掉某天、某口径下的**所有指标**行（重算的第一步）。
     *
     * <p>按天整体删而不是按指标删：重算一天时两个指标是一起重写的，
     * 分开删只会多一次参数、多一个漏删的机会。
     */
    int deleteByDay(@Param("statDate") LocalDate statDate,
                    @Param("caliberVersion") int caliberVersion);

    /**
     * 区间内每天每个指标的合计：{@code [{statDate, metric, total}]}。
     *
     * <p>一次把多个指标查回来（{@code metric IN (...)}），然后 {@code GROUP BY 天, 指标}。
     * 日级指标一天一行，{@code SUM} 就是它自己；粒度更细的指标（天 × 设备）
     * {@code SUM} 出来正是「这一天的总量」—— 同一句 SQL 服务两种粒度，
     * 这也是窄表 + 统一用 SUM 的好处。
     */
    List<Map<String, Object>> selectDailyTotals(@Param("metrics") List<String> metrics,
                                                @Param("caliberVersion") int caliberVersion,
                                                @Param("from") LocalDate from,
                                                @Param("to") LocalDate to);

    /**
     * 区间内**已经算过**的日期（用来判断哪几天要实时补算）。
     *
     * <p>传的指标应当是 {@code StatMetric.PRESENCE} 那个「每天都写」的 ——
     * 见 {@code StatMetric} 上关于「空数据的那天也要留一行」的说明。
     */
    List<LocalDate> selectComputedDates(@Param("metric") String metric,
                                        @Param("caliberVersion") int caliberVersion,
                                        @Param("from") LocalDate from,
                                        @Param("to") LocalDate to);
}
