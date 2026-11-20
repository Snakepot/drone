package cn.tedu.drone.task.dao;

import cn.tedu.drone.task.pojo.AlertPO;
import cn.tedu.drone.task.pojo.AlertQueryParam;
import cn.tedu.drone.task.pojo.TaskAlertCount;
import org.apache.ibatis.annotations.Param;

import java.util.Map;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * 告警数据访问层。
 */
public interface AlertDao {

    /**
     * 插入一条告警 —— <b>除非「同一设备 + 同一编码」还有未处理的告警</b>。
     *
     * <h2>为什么不是单纯的「撞唯一键就跳过」</h2>
     * 阈值类告警是<b>服务端每条遥测算一次</b>算出来的（设备根本不会报「我电量低了」），
     * 而遥测是每秒一条。只靠唯一键去重的话，一个持续 5 分钟的低电量会留下约 300 行，
     * 全部指向同一个问题 —— 这不是推测，是用模拟器实跑出来的
     * （10 秒的低电量产生了 11 行）。
     *
     * <p>规则是：<b>只要这个问题还没被处理掉，就不再重复产生。</b>
     * 这是「告警是状态判定、不是采样」这个定位的必然推论 —— 状态没变，就还是同一条问题。
     * 而一旦被 {@code resolved} / {@code ignored}，问题复发时会<b>重新产生一条</b>：
     * 那是一次新的事件，不是同一条的延续。
     *
     * <h2>两道防线各管一件事</h2>
     * <ul>
     *   <li>唯一索引 {@code uk_alert_dedup(device_code, alert_code, observed_at)}：
     *       挡「完全相同的这一条被重复投递」（QoS 1 重投、设备重传）</li>
     *   <li>{@code WHERE NOT EXISTS ...}：挡「同一问题还在挂着」</li>
     * </ul>
     * 判断与写入在同一条语句里完成，没有并发窗口 —— 这一点很要紧：
     * 消费线程默认就有 2 个，「先查再插」必然有洞。
     *
     * <h2>device_code 为 NULL 时不会被抑制</h2>
     * SQL 里 {@code NULL = NULL} 不成立，所以人工上报（没有设备编号）之间不会互相抑制。
     * 这恰好是想要的：飞手一分钟内报两次「现场有干扰」是两次上报，不该合成一条。
     *
     * @return 1 新增；0 没新增（同一问题已有未处理的，或这一条之前就报过）
     */
    int insertUnlessOpen(AlertPO alert);

    /**
     * 插入一条告警，并<b>把自增主键回填到入参对象上</b>（人工上报用）。
     *
     * <h2>为什么人工上报不复用 {@link #insertUnlessOpen}</h2>
     * 两条理由，各自都足够：
     * <ol>
     *   <li><b>抑制规则对它本来就不生效</b>：那条 {@code WHERE NOT EXISTS} 是
     *       「同一设备 + 同一编码还有未处理的就不再产生」，而人工上报的
     *       {@code device_code} 是 NULL —— SQL 里 {@code NULL = NULL} 不成立，
     *       所以判断永远为真、每次都插。也就是说走 {@code insertUnlessOpen}
     *       只是绕一圈回到「普通插入」</li>
     *   <li><b>需要拿到新主键</b>：上报时要顺手把附件挂到这条新告警上
     *       （{@code task_attachment.alert_id}），没有 id 就绑不了。
     *       {@code INSERT ... SELECT} 拿生成键在各驱动上行为不一致，
     *       而这里的 VALUES 插入是稳的</li>
     * </ol>
     *
     * @return 影响行数
     */
    int insert(AlertPO alert);

    // ------------------------------------------------------------------
    // 查询与处理（实施步骤第二步：管理端告警页 + 处理闭环）
    // ------------------------------------------------------------------

    /** 按条件分页查询告警 */
    List<AlertPO> selectByCondition(AlertQueryParam param);

    /**
     * 按条件统计总数，分页组件靠它算总页数。
     * <p>条件必须与 {@link #selectByCondition} 完全一致，否则会出现
     * 「总数说有 10 条、翻页却发现只有 8 条」这种对不上的情况。
     */
    long countByCondition(AlertQueryParam param);

    /**
     * 未处理告警（pending + handling）按级别分组计数 —— 大屏的告警分布图用它。
     *
     * <p>{@code pilotId} 非空时只算**派给他的任务上**的：飞手不能看到别人的告警，
     * 而「告警属于谁」要 join task 才知道（{@code task_alert} 上没有 pilot_id）。
     */
    List<Map<String, Object>> countOpenGroupBySeverity(@Param("pilotId") Long pilotId);

    /** 某个时刻之后产生的告警数（含已处理的）—— 「今日新增」用它 */
    long countObservedSince(@Param("from") LocalDateTime from);

    /** 按主键查询（处理前要先拿到它，才能校验状态机） */
    AlertPO selectById(@Param("id") Long id);

    /**
     * 查「本次任务期间」的告警 —— 按 {@code task_id} <b>或</b>「设备 + 时间窗」两边查。
     *
     * <h2>为什么要两边都查</h2>
     * 设备上报那一刻如果<b>没有执行中的任务</b>，落库时 {@code task_id} 是空的
     * （试飞、机库自检、提前开机）。但管理员事后看这条任务时，
     * 那些「当时没主」的告警其实和它有关 —— 比如设备提前自检报的电池高温。
     * 只按 {@code task_id} 查会漏掉它们。
     *
     * <p>而只按设备查又会把这台设备**历史上所有**的告警拉进来，
     * 所以必须带时间窗。窗口取「计划」与「实际」的<b>并集</b> ——
     * 要捞的那类告警恰恰多发生在实际开始之前（飞机先起飞、飞手后点「开始执行」），
     * 只用实际起止会把它们漏掉。理由写在
     * {@code AlertServiceImpl.listAlertsInTaskWindow} 的注释里。
     *
     * @param taskId     任务 id
     * @param deviceCode 任务用的设备编号；为空、或时间窗给不出（两个端点都是 null）时，
     *                   查询退化成只按 {@code task_id} —— 宁可少给，不要给错
     * @param from       时间窗起
     * @param to         时间窗止
     */
    List<AlertPO> selectInTaskWindow(@Param("taskId") Long taskId,
                                     @Param("deviceCode") String deviceCode,
                                     @Param("from") LocalDateTime from,
                                     @Param("to") LocalDateTime to);

    /**
     * 统计一批任务各自还有多少条<b>未处理</b>告警（飞手端任务卡片角标用）。
     *
     * <p>一条 {@code GROUP BY} 语句查完一页任务，不在循环里逐个查 ——
     * 一页 10 条任务就是 10 次往返，典型的 N+1。
     *
     * <p>只数 {@code pending} / {@code handling}：角标回答「有什么要管的」，
     * 口径与告警页的排序一致。
     *
     * @param taskIds 任务 id 集合，<b>调用方保证非空</b>（空的 IN 会拼出语法错误）
     * @return 只有「有未处理告警」的任务才会出现在结果里；没有的不返回，
     *         由调用方按「查不到就是 0」处理
     */
    List<TaskAlertCount> countOpenByTaskIds(@Param("taskIds") Collection<Long> taskIds);

    /**
     * 认领：把状态从「待处理」推到「处理中」。
     *
     * <p>刻意<b>不写 handled_by</b>：那三列的含义是「谁处理的、什么时候处理完的、
     * 怎么处置的」，认领只是「我来负责」，还没处理完。
     * 把认领人塞进 handled_by 会让「处理人」这个字段在两种含义之间摇摆 ——
     * 后面看数据的人就分不清「这条是张三处理的」还是「张三看过但李四处理的」。
     *
     * <p>{@code AND status = 'pending'} 是比较并交换：两个人同时点「认领」，
     * 只有一个会拿到 1 行，另一个拿到 0 行并看到「状态刚刚变了，请刷新」。
     * 这正是认领这一步存在的意义 —— 防止两个人重复处理同一条告警。
     *
     * @return 1 认领成功；0 状态已变（别人抢先了）
     */
    int claim(@Param("id") Long id);

    /**
     * 收尾：把状态推到「已处理」或「误报」，并记下处理人、时刻与说明。
     *
     * <p>{@code AND status IN (...)} 同样是比较并交换，由调用方传允许的起始状态
     * （两个动作的允许集合不同：已处理只能从处理中来，误报可以从待处理或处理中来）。
     * {@code expectStatuses} 用集合而不是单个值，是为了让状态机里那张
     * 「动作 → 允许的来源状态」表成为唯一的判据来源，不在这里再写死一次。
     *
     * @return 1 成功；0 状态已变
     */
    int close(@Param("id") Long id,
              @Param("expectStatuses") List<String> expectStatuses,
              @Param("newStatus") String newStatus,
              @Param("handledBy") Long handledBy,
              @Param("handledByName") String handledByName,
              @Param("handledAt") LocalDateTime handledAt,
              @Param("note") String note);
}
