package cn.tedu.drone.analytics.dao;

import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 业务分析的**只读聚合**查询（见 {@code docs/业务分析方案.md}）。
 *
 * <h2>它为什么读别人的表</h2>
 * 这里没有一句业务写操作，全是「按区间聚合」。这类查询的归属是**问题**，不是表 ——
 * 「待派发卡了多久」横跨 {@code task} 与 {@code task_dispatch_block}，
 * 硬塞回某一方的 DAO，那个 DAO 就得替另一个模块的指标负责。
 * 所以这里显式地跨模块读（与 dashboard 同一套路，见 {@code docs/数字大屏方案.md}）。
 *
 * <h2>⚠️ 库里有<b>两个时钟</b>，每条 SQL 必须跟自己这条列的时钟比</h2>
 * 这是构建这一屏时踩出来的一个真坑，写在这里免得下一个人再踩：
 * <ul>
 *   <li><b>MySQL 的默认值写的</b>：{@code task.create_time}、{@code task_log.create_time}
 *       —— 没在 INSERT 里写这一列，于是由 mysqld 的 {@code CURRENT_TIMESTAMP} 填。
 *       这台 mysqld 跑在 <b>UTC</b>（{@code SELECT NOW()} 就是 {@code UTC_TIMESTAMP()}），
 *       所以这些列存的是 UTC 时刻</li>
 *   <li><b>Java 传字面量写的</b>：{@code checked_at}、{@code plan_start_time} 等 ——
 *       JDBC 把 {@code LocalDateTime} 原样当字面量发过去，不做时区换算，
 *       所以这些列存的是**应用侧的墙上时间**（+08:00）</li>
 * </ul>
 * 两类列相差 8 小时。所以：
 * <ul>
 *   <li>比 <b>create_time</b> 这类列（MySQL 写的）→ 用 {@code NOW()}，让同一条 SQL 里
 *       两边出自同一个时钟。<b>不要</b>从 Java 传「现在」过来 —— 那是另一个时钟</li>
 *   <li>比 <b>checked_at</b> 这类列（Java 写的）→ 时间端点从 Java 传，
 *       不要用 {@code NOW()}（用它会差 8 小时，{@code DispatchBlockMapper} 上记过同一条）</li>
 *   <li>两个列<b>相减</b>求时长（如「创建→派发」）→ 只要两列同源就没事，
 *       别在中间掺一个另一时钟的点</li>
 * </ul>
 * 这个「两个时钟」是历史遗留（列建表时用了默认值），
 * 彻底解法是统一成一个时钟 + 迁移历史数据 —— 那是独立的一件事，先记在这里。
 *
 * <h2>「区间」的边界约定</h2>
 * 左闭右开：{@code from <= t < to}。端点都对齐在整点上，
 * 这样按天/按周取区间时不会出现「最后一条记录落不进任何区间」。
 */
public interface AnalyticsDao {

    /**
     * 区间内每个归因各有多少个点，返回 {@code [{reason, cnt}, ...]}。
     *
     * <p>这是「积压归因占比」那个指标的数据来源：
     * 「本周积压里 60% 是缺设备、30% 是没人派、10% 是缺飞手」。
     *
     * <p>区间两端从 Java 传：{@code checked_at} 是 Java 写的（见类注释）。
     */
    List<Map<String, Object>> countBlockReasonGroup(@Param("from") LocalDateTime from,
                                                   @Param("to") LocalDateTime to);

    /**
     * 区间内有采样的小时数。
     *
     * <p>它是「归因占比可不可信」的尺子：定时任务停过机的那几个小时没有数据，
     * 与「区间一共多少小时」的差就是缺口。
     */
    long countBlockSampledHours(@Param("from") LocalDateTime from,
                                @Param("to") LocalDateTime to);

    /**
     * 此刻还卡在待派发的任务，按已等待时长分三桶。
     *
     * <p>返回 {@code {lt1h, lt1d, ge1d}}；一条待派发任务都没有时**三个值都是 null**
     * （{@code SUM} 在空集上给 NULL），调用方要当成 0 —— 别把 null 直接当数字用。
     *
     * <p><b>没有时间参数</b>，分界点在 SQL 里用 {@code NOW()} 算。
     * 这里算的是**时长**（现在 − 创建时刻），两边的时钟必须一致，
     * 而 {@code create_time} 是 MySQL 写的（见类注释）——
     * 从 Java 传「现在」进来就会整体差 8 小时：
     * 刚创建的任务会被算成「等了一天」。
     */
    Map<String, Object> countPendingByAge();

    /**
     * 区间内被派发过的任务，各自「创建 → 派发」用了多少分钟。
     *
     * <p>只返回原始时长，**中位数在 Service 里算**：区间内是几百行，
     * 而 MySQL 没有 {@code PERCENTILE_CONT}，硬用窗口函数换来的是一段没人看得懂的 SQL。
     *
     * <p>区间过滤打在**派发流水**的时间上（{@code task_log.create_time}，MySQL 写的时钟），
     * 端点由 Java 传。这两者不同源，但在这里不构成错误：区间只是一个**筛选窗口**
     * （「这段时间里派出去的活各自等了多久」），窗口从哪一刻开始是任意的，
     * 没有「本地日历上的哪一天」这种语义要求 —— 只要前后一致，数出来的时长就是对的。
     * 真正不能原谅的是**掺两个时钟去算时长**（比如拿 Java 的「现在」减 create_time），
     * 那会整体偏 8 小时，见 {@link #countPendingByAge()}。
     */
    List<Long> selectDispatchDurations(@Param("from") LocalDateTime from,
                                       @Param("to") LocalDateTime to);

    // ------------------------------------------------------------------
    // 任务与告警概览（docs/业务分析方案.md 第一步 / 2.1 / 2.4）
    // ------------------------------------------------------------------

    /**
     * mysqld 的墙上时间相对 UTC 差多少秒（这台机器是 0，因为它跑在 UTC）。
     *
     * <p>它只服务一件事：<b>让「按天」这个动作落在应用侧的日界上</b>。
     * {@code create_time} 是 mysqld 用自己的时钟填的，直接 {@code DATE()} 出来的是
     * <b>mysqld 的日</b>；如果库在 UTC 而人在东八区，这个「天」比人的一天早 8 小时开始，
     * 画出来的趋势图整体偏一格。Service 用
     * {@code 应用时区偏移 − 这个值} 得到要平移的秒数，交给下面的统计。
     */
    long selectServerClockOffsetSeconds();

    /**
     * 区间内按「应用侧的每一天」统计新建任务数，返回 {@code [{day, cnt}, ...]}。
     *
     * <p>{@code shiftSeconds} 是 {@code 应用时区偏移 − mysqld 偏移}（见
     * {@link #selectServerClockOffsetSeconds()}）：把 {@code create_time} 平移那么多秒，
     * 它才和「人看到的时刻」在同一个钟上，{@code DATE()} 出来的天才和日历对得上。
     * 两边本来就在同一时区时它是 0，什么也不影响。
     *
     * <p>没有任务的那几天不会出现在结果里 —— 补齐 0 是 Service 的事（它才知道横轴有多长）。
     */
    List<Map<String, Object>> countTaskCreatedByDay(@Param("from") LocalDateTime from,
                                                    @Param("to") LocalDateTime to,
                                                    @Param("shiftSeconds") int shiftSeconds);

    /**
     * 区间内**结束**的任务的完成情况，一行里带齐分子、分母与样本数。
     *
     * <p>「结束」= {@code actual_end_time} 落在区间内（只有完成与失败会写这一列，
     * 取消的任务从没执行过，自然不参与 —— 这个选择写在 VO 的类注释里）。
     * 返回的键：{@code finished / failed / onTimeBase / onTime / durationBase / avgMinutes}；
     * 一条都没有时整行都是 null，调用方要按 0 处理。
     */
    Map<String, Object> countTaskOutcome(@Param("from") LocalDateTime from,
                                         @Param("to") LocalDateTime to);

    /**
     * 区间内告警按「天 × 级别」计数，返回 {@code [{day, severity, cnt}, ...]}。
     *
     * <p>这里<b>不需要</b>平移：{@code observed_at} 是 Java 按本地时间写进去的，
     * {@code DATE()} 出来就是应用侧的日 —— 与上面那个任务趋势同一天轴。
     */
    List<Map<String, Object>> countAlertByDayAndSeverity(@Param("from") LocalDateTime from,
                                                        @Param("to") LocalDateTime to);

    /**
     * 区间内告警的总量、严重数、已处置数与平均处置时长（MTTR）。
     *
     * <p>区间打在 {@code observed_at} 上，含义是「**这段时间里发生**的告警，处理得怎么样」。
     * MTTR 只算已处置的（未处置的不是「0 分钟」，是「还没结论」）。
     * 另外附一个 {@code openNow}：此刻未处置的总数（快照，不看区间）。
     */
    Map<String, Object> countAlertSummary(@Param("from") LocalDateTime from,
                                          @Param("to") LocalDateTime to);

    // ------------------------------------------------------------------
    // 飞手与设备效率（docs/业务分析方案.md 第二步 / 2.2 / 2.3）
    // ------------------------------------------------------------------

    /**
     * 在册飞手的花名册：{@code [{id, name}]}。
     *
     * <p>榜单要列**全部**飞手，包括这段时间一条活都没有的那几位 ——
     * 「谁忙谁闲」里的「闲」正是靠他们体现的。只列有数据的人，这页就答不了那个问题。
     */
    List<Map<String, Object>> selectPilotRoster();

    /**
     * 区间内**结束**的活按飞手汇总：任务数、完成数、按时率的分子分母，
     * 外加报告里填的飞行时长与架次（一次查回来，免得为同一批任务查两遍）。
     *
     * <p>窗口打在 {@code actual_end_time}（有结论的活）。报告用 {@code is_latest = 1} ——
     * 一条任务可以有多次修订，不加这个条件会把上一版也算进去。
     */
    List<Map<String, Object>> countPilotOutcome(@Param("from") LocalDateTime from,
                                                @Param("to") LocalDateTime to);

    /**
     * 区间内**开过**的活（含还在飞的）按飞手汇总，以及其中「只有声明、没有设备旁证」的条数。
     *
     * <p>窗口打在 {@code actual_start_time} 上 —— 这一条**故意与上面那个不同**：
     * 「他说在飞、可没东西能证明」恰恰要在飞行中暴露，只统计结束的活会正好漏掉它。
     */
    List<Map<String, Object>> countPilotEvidence(@Param("from") LocalDateTime from,
                                                 @Param("to") LocalDateTime to);

    /**
     * 区间内派发与拒绝的次数，按飞手汇总：{@code [{pilotId, offers, refused}]}。
     *
     * <p>{@code offers} 是「**派给他的次数**」，已经把他拒掉的那些算回来了：
     * 拒绝会走 releaseAssignment / clearDispatch，把任务的 {@code pilot_id} 一并清空 ——
     * 只数「现在挂在他名下」的派发，会得出「拒得越多、分母越小、拒绝率越高」这种怪结论。
     * 所以 SQL 里是两半相加：派发按任务当前的指派飞手认人，拒绝按流水的 {@code operator_id} 认人。
     *
     * <p>窗口打在**流水时间**上（派发与拒绝都是事件，发生在那一刻就记在那一刻），
     * 而 {@code task_log.create_time} 是 mysqld 的时钟，所以要平移（见类注释）。
     */
    List<Map<String, Object>> countPilotActions(@Param("from") LocalDateTime from,
                                                @Param("to") LocalDateTime to,
                                                @Param("shiftSeconds") int shiftSeconds);

    /**
     * 设备花名册：{@code [{id, code, name, status, purchaseDate}]}。
     *
     * <p>{@code purchaseDate} 格式化成字符串（{@code yyyy-MM-dd}）：
     * 它是 {@code date} 类型，走 Map 拿回来会是个 {@code java.sql.Date}，
     * 在 Service 里还得再绕一圈 —— 而这里唯一要干的事就是算「在役多少天」。
     * 没有采购日期的设备，在役天数按区间全长算（它至少是在的，总比当 0 天合理）。
     */
    List<Map<String, Object>> selectDeviceRoster();

    /** 区间内各设备的飞行时长与架次（取自任务报告，{@code is_latest = 1}） */
    List<Map<String, Object>> countDeviceReports(@Param("from") LocalDateTime from,
                                                 @Param("to") LocalDateTime to);

    /**
     * 区间内各设备的告警数：{@code [{deviceCode, alerts, criticalAlerts}]}。
     *
     * <p>按 {@code device_code} 关联（告警表里存的是编号，不是设备 id），
     * 并且只看有编号的那些 —— 人工上报/飞手上报的告警可能压根不涉及设备
     * （{@code device_code} 为空），把那些算到设备头上是冤枉它。
     */
    List<Map<String, Object>> countDeviceAlerts(@Param("from") LocalDateTime from,
                                                @Param("to") LocalDateTime to);

    // ------------------------------------------------------------------
    // 预聚合的数据源（docs/业务分析方案.md 第三步）
    // ------------------------------------------------------------------

    /**
     * 区间内**每天每台设备**有多少条证据：{@code [{day, deviceCode, cnt}]}。
     *
     * <p>这条查询有两处调用，而且**必须是同一条**：
     * ① 凌晨任务拿它写预聚合（粒度就是「天 × 设备」，见 {@code StatMetric}）；
     * ② 趋势接口拿它实时补「还没算的那几天」（通常是今天）。
     * 两处若各写一条 SQL，「昨天（预聚合）」与「今天（实时）」在图上就会像两个人算的。
     *
     * <p>只数 {@code valid = 1}：被推翻的证据不该算进趋势里（口径写在 StatMetric 上）。
     */
    List<Map<String, Object>> countEvidenceByDayAndDevice(@Param("from") LocalDateTime from,
                                                          @Param("to") LocalDateTime to);

    // ------------------------------------------------------------------
    // 点位覆盖率（docs/业务分析方案.md 第四步）
    // ------------------------------------------------------------------

    /**
     * 区间内**已结束**的任务，连它的航线快照一起取回来：{@code [{id, taskNo, routeSnapshot}]}。
     *
     * <p>窗口仍按 {@code actual_end_time}（与完成率、工作量同一口径）——
     * 覆盖率回答的是「这个月飞过的那些活，计划点都去了吗」。
     *
     * <p>快照是 JSON、由 Java 解析（{@code RouteService.parseSnapshot}，已有现成的）：
     * 计划点要从 JSON 里取，SQL 里解 JSON 既难写又难维护。
     */
    List<Map<String, Object>> selectFinishedTasksWithSnapshot(@Param("from") LocalDateTime from,
                                                             @Param("to") LocalDateTime to);

    /**
     * 窗口内那些任务的打卡记录：{@code [{taskId, seq, deviationM}]}。
     *
     * <p>「到过」就是按它判断的（偏差 ≤ 阈值才算），见 {@code CoverageVO} 上的说明。
     * 关联条件与上一个查询保持一致（任务的实际结束时间落窗），两个查询才是同一批任务。
     */
    List<Map<String, Object>> selectCheckinsOfFinishedTasks(@Param("from") LocalDateTime from,
                                                            @Param("to") LocalDateTime to);

    /** 按 id 批量取点位（拿名称/编号用）：{@code [{id, code, name}]} */
    List<Map<String, Object>> selectPointsByIds(@Param("ids") List<Long> ids);
}
