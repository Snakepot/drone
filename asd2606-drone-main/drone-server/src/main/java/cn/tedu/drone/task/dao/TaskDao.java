package cn.tedu.drone.task.dao;

import cn.tedu.drone.task.pojo.TaskDispatchOptionVO;
import cn.tedu.drone.task.pojo.TaskPO;
import cn.tedu.drone.task.pojo.TaskQueryParam;
import org.apache.ibatis.annotations.Param;

import java.util.Map;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 任务数据访问层
 */
public interface TaskDao {

    /**
     * 按条件分页查询任务
     */
    List<TaskPO> selectByCondition(TaskQueryParam param);

    /**
     * 按条件统计总数，分页组件靠它算总页数。
     * <p>条件必须与 {@link #selectByCondition} 完全一致，否则会出现
     * 「总数说有 10 条、翻页却发现只有 8 条」这种对不上的情况。
     */
    long countByCondition(TaskQueryParam param);

    /**
     * 按主键查询任务
     */
    TaskPO selectById(@Param("id") Long id);

    // ------------------------------------------------------------------
    // 数字大屏用的聚合查询（见 docs/数字大屏方案.md 3.2）
    //   共同点：**只回一个数字或一张「值 → 条数」的小表**，绝不把明细拉回来在 Java 里数
    // ------------------------------------------------------------------

    /**
     * 按状态分组计数。
     *
     * <p>{@code pilotId} 非空时只算派给他的 —— 这是「任务总览」与「我的任务」**唯一的区别**，
     * 所以两者共用这一条 SQL。返回 {@code [{status, cnt}, ...]}，只含有数据的状态。
     */
    List<Map<String, Object>> countGroupByStatus(@Param("pilotId") Long pilotId);

    /**
     * 某个时刻之后新建的任务数（「今日新增」）。
     *
     * <p>时间点由 Java 传进来，不用 SQL 的 {@code CURDATE()}：mysqld 的时区是 UTC，
     * 我们存的是本地时间 —— 用数据库的「今天」会把时区差整整算错一天。
     */
    long countCreatedSince(@Param("from") LocalDateTime from, @Param("pilotId") Long pilotId);

    /** 超期：执行中且已过计划结束时间（与监控看板同一口径） */
    long countOverdue(@Param("now") LocalDateTime now, @Param("pilotId") Long pilotId);

    /**
     * 当前所有「执行中」的任务 —— 监控看板要的就是这一批。
     *
     * <p>为什么不复用 {@code selectByCondition} 传 status=executing：
     * 那个是列表查询，返回的是「某一页」（有 offset/limit、也有它自己的排序规则），
     * 而看板要的是「现在全部在飞的」。两者语义不同，
     * 将来一个要改排序、一个要改分页，就会互相牵动。
     *
     * <p>排序按实际开始时间：先起飞的排在前面。
     */
    List<TaskPO> selectExecuting();

    /**
     * 按任务编号查询
     */
    TaskPO selectByTaskNo(@Param("taskNo") String taskNo);

    /**
     * 查询某天已生成的最大任务编号，用于生成「TK-日期-序号」里的序号。
     *
     * <p>注意：拿到之后再 +1 并插入，在并发下有撞号的窗口 ——
     * 这也是为什么 {@code uk_task_no} 上有唯一索引：宁可让数据库挡住，
     * 也不能靠应用层「查过就一定没人插」的假设。
     *
     * @param prefix 形如 {@code TK-20260918-}
     * @return 当天最大的任务编号，当天还没建过任务时返回 null
     */
    String selectMaxTaskNo(@Param("prefix") String prefix);

    /**
     * 新增任务
     */
    int insert(TaskPO task);

    // ------------------------------------------------------------------
    // 派发 / 撤回
    // ------------------------------------------------------------------

    /**
     * 写入派发结果：飞手、设备、状态一起改。
     *
     * <p>三件事必须一次 UPDATE 完成 —— 分成三条 SQL 的话，中间任何一条失败
     * 都会留下「状态是已派发、但没有飞手」的脏数据。
     */
    int updateDispatch(@Param("taskId") Long taskId,
                       @Param("pilotId") Long pilotId,
                       @Param("pilotName") String pilotName,
                       @Param("droneId") Long droneId,
                       @Param("droneCode") String droneCode,
                       @Param("status") String status);

    /**
     * 撤回派发 / 超时释放：把指派的人和设备清空，状态退回待派发。
     *
     * <p>这里必须显式写 {@code = NULL}，不能复用「按非空字段更新」的写法 ——
     * 那种写法遇到 null 会跳过该字段，结果就是想清空却清不掉。
     *
     * <p><b>{@code AND status = #{expectStatus}} 是这条 SQL 的另一半重点</b>：
     * 它让「判断状态」和「改状态」在同一条语句里完成（比较并交换），
     * 于是这个方法有了两个用处：
     * <ul>
     *   <li>如果影响 1 行，说明确实是「已派发」的状态，释放生效</li>
     *   <li>如果影响 0 行，说明状态已经变了（别人刚撤回、或刚被别的流程改过），
     *       调用方<b>必须跳过后续的资源释放</b>，否则会把别人新派的资源又释放一遍</li>
     * </ul>
     * 定时释放任务和管理员手工撤回可能同时发生，这个条件就是防撞车的那道门。
     *
     * @return 影响行数：1 表示释放成功，0 表示状态已变，什么都没做
     */
    int clearDispatch(@Param("taskId") Long taskId,
                      @Param("expectStatus") String expectStatus,
                      @Param("newStatus") String newStatus);

    /**
     * 飞手开始执行：状态改成「执行中」并落下实际开始时间。
     *
     * <p>只改这两列，指派关系（飞手、设备）保持不动 —— 人家正在飞，
     * 这时候把设备信息清掉就荒唐了（这也正是它和 {@link #clearDispatch} 的区别）。
     *
     * <p>{@code AND status = 'dispatched'} 同样是比较并交换：
     * 万一定时释放或管理员撤回抢先一步，这里会返回 0 行，
     * 调用方（Service）已经用状态机校验挡在前面了，这里只是兜住极小概率的并发。
     *
     * @return 影响行数：1 表示成功，0 表示状态已变（没改成）
     */
    int markStarted(@Param("taskId") Long taskId, @Param("actualStartTime") LocalDateTime actualStartTime);

    /**
     * 完成任务：状态 → 已完成，并落下实际结束时间。
     *
     * <p>照 {@link #markStarted} 的模板写：{@code AND status = 'executing'} 是
     * <b>比较并交换</b>，让「判断状态」和「改状态」在同一条语句里完成。
     * 返回 0 行说明状态被人抢先改过，调用方要放弃后续的资源释放。
     *
     * <p><b>它不清空指派关系</b>（不像 {@code clearDispatch}）——
     * 报告要写清「是谁飞的、用哪架飞的」。
     *
     * @return 1 成功；0 状态已变
     */
    int markFinished(@Param("taskId") Long taskId, @Param("actualEndTime") LocalDateTime actualEndTime);

    /**
     * 标记执行失败：状态 → 已失败，并落下实际结束时间。
     *
     * <p>与 {@link #markFinished} 完全同形，只差落地状态 ——
     * 拆成两个方法而不是传一个状态参数进来，是为了让调用点一眼看出走的是哪条路，
     * 也避免有人传别的状态值。
     *
     * @return 1 成功；0 状态已变
     */
    int markFailed(@Param("taskId") Long taskId, @Param("actualEndTime") LocalDateTime actualEndTime);

    /**
     * 查出「派发出去但计划时间早就过了」的任务，供定时任务释放占用。
     *
     * <p>只扫 {@code dispatched}：待派发的还没占资源；
     * {@code executing} 的任务<u>刻意不扫</u> —— 那种情况设备是真的在飞，
     * 自动释放等于谎报，交给监控告警处理（见 docs/任务业务设计.md 里「边界：执行中超时不自动释放」一节）。
     *
     * @param deadline 超时判定线，由调用方用应用本地时间算好传进来
     * @param limit    单批上限，避免一次捞出太多
     */
    List<TaskPO> selectExpiredDispatched(@Param("deadline") LocalDateTime deadline,
                                         @Param("limit") int limit);

    /**
     * 查出该飞手在指定时间窗内已承接的任务，用于派发前的冲突检查。
     *
     * <p>返回冲突的那条任务（而不是返回一个数量）是为了报错时能指名道姓：
     * 「张伟在 12-06 09:00~11:00 已有任务 TK-xxx」比「已有 1 个任务」有用得多 ——
     * 管理员能顺着编号去看那条任务、决定是改期还是换人。
     *
     * <p>只算「已派发 / 执行中」的任务：待派发的还没占资源，
     * 已完成、已失败、已取消的早就把资源释放了。
     *
     * <p><b>为什么按时间窗判断而不是「有没有未结束的任务」</b>：
     * 一个飞手同时背着下周的两个任务完全正常，那叫排班；
     * 真正要挡的是<b>同一时间段</b>被派了两件事。
     * 区间重叠的判定条件是 {@code 已有任务开始 < 新任务结束 && 已有任务结束 > 新任务开始}，
     * 首尾相接（10:00 结束、10:00 开始）不算冲突。
     *
     * @return 冲突的任务，没有冲突时返回 null
     */
    TaskPO selectPilotConflict(@Param("pilotId") Long pilotId,
                               @Param("planStartTime") LocalDateTime planStartTime,
                               @Param("planEndTime") LocalDateTime planEndTime);

    /**
     * 查出该设备在指定时间窗内已承接的任务，判定规则同
     * {@link #selectPilotConflict}
     *
     * @return 冲突的任务，没有冲突时返回 null
     */
    TaskPO selectDroneConflict(@Param("droneId") Long droneId,
                               @Param("planStartTime") LocalDateTime planStartTime,
                               @Param("planEndTime") LocalDateTime planEndTime);

    /**
     * 统计该飞手手上还有几个没结束的任务（已派发 / 执行中）。
     *
     * <p>与 {@link #selectPilotConflict} 的区别：那个按<b>任务时间段</b>判断，
     * 用于「能不能派给他」；这个不看时间段，只看<b>还有没有活</b>，
     * 用于撤回派发时判断「要不要把飞手的在线状态改回空闲」。
     *
     * <p>飞手可以同时背几个不同时段的任务（排班），所以撤回其中一个之后，
     * 只有确认他手上再没有别的活，才该把「作业中」改回「空闲」。
     */
    long countActiveByPilot(@Param("pilotId") Long pilotId);

    /**
     * 查出所有拥有某个权限的用户 id，用于「把通知推给该管这事的人」。
     *
     * <p>典型用法：飞手拒绝执行时要通知管理员。谁是「该管这事的人」？
     * 答案是**拥有 {@code task:dispatch} 权限的人** —— 因为能被通知的人
     * 正好就是能重新指派的人，语义完全对齐。这也顺带把「只推给某个写死的
     * 管理员账号」这种脆弱做法避开了（换人、加人都不用改代码）。
     *
     * <p>只返回<b>启用状态</b>的账号、且角色也是启用的 ——
     * 离职或被停用的账号没必要收通知。
     *
     * <p>这条 SQL 跨 user / user_role / role / role_permission / permission 五张表。
     * 放在任务模块里，是因为「谁能重新指派任务」是任务模块的用例，
     * 不是用户模块的通用查询。
     */
    List<Long> selectUserIdsByPermission(@Param("permissionCode") String permissionCode);

    /**
     * 派发下拉用的飞手候选：账号启用，且拥有飞手角色。
     *
     * <p>这条 SQL 跨了 user / user_role / role 三张表，写在任务模块里是因为
     * <b>「谁可以被派发」是派发的业务规则，不是员工管理的查询</b>。
     * 若改成复用 {@code /user/list?roleCode=pilot}，就等于要求
     * 有派发权限的账号必须先拿到 {@code staff:list}，权限模型会被悄悄放大。
     *
     * <p>返回的 label 在 SQL 里拼好：姓名 + 手机号，重名时也能区分。
     */
    /**
     * 时间窗内已经被占住的飞手数（去重）。
     *
     * <p>用途是「待派发归因」：可派飞手 = 全部可派飞手 − 这里数出来的。
     * ⚠️ **重叠条件必须和 {@link #selectPilotConflict} 一模一样**
     * （`开始 &lt; 我结束 且 结束 &gt; 我开始`）——
     * 两处不一致就会出现「派发时说能派、归因却说缺人」这种自相矛盾。
     */
    long countConflictingPilots(@Param("planStartTime") LocalDateTime planStartTime,
                                @Param("planEndTime") LocalDateTime planEndTime);

    /** 时间窗内已经被占住的设备数（去重）。约束同上 */
    long countConflictingDrones(@Param("planStartTime") LocalDateTime planStartTime,
                                @Param("planEndTime") LocalDateTime planEndTime);

    List<TaskDispatchOptionVO> selectDispatchablePilots();

    /**
     * 派发下拉用的设备候选：处于「待命」状态的设备。
     *
     * <p>只取待命的 —— 飞行中、充电中、维护中、离线的设备不该出现在候选里。
     * 真要派一台维护中的设备，得先去设备管理把状态改回来，这是有意的摩擦。
     *
     * <p>注意这里不查设备是否已被别的任务占用：那件事交给
     * {@link #selectDroneConflict} 按时间窗判断，
     * 因为「这周被占」和「这个时段被占」是两回事。
     */
    List<TaskDispatchOptionVO> selectDispatchableDrones();

    // ------------------------------------------------------------------
    // 执行证据
    // ------------------------------------------------------------------
    // 这一组方法服务的场景是「设备发来一条遥测，它能不能佐证某条任务正在执行」。
    // 全部刻意不改 status —— 证据是<b>另一个维度</b>，没动状态机一个箭头，
    // 理由见 EvidenceState 的类注释。

    /**
     * 查出某台设备<b>当前正在执行</b>的任务，用于把遥测挂到任务上。
     *
     * <p>按「实际开始时间倒序」取最新的一条：理论上同一台设备不该同时有两条
     * {@code executing} 的任务（派发时的冲突检查会挡住时间窗重叠的情况），
     * 但「上一单 11:00 结束、下一单 14:00 开始」这种不重叠的排班是允许的，
     * 于是确实可能出现两条都处于 executing 的极短窗口。
     * 真遇上了，把它算给最近开工的那条，并且不报错 ——
     * 这条遥测本来就无法精确归属，硬要报错只会让好数据也存不下来。
     *
     * @return 正在执行的任务，没有时返回 null
     */
    TaskPO selectExecutingByDrone(@Param("droneId") Long droneId);

    /**
     * 把任务的证据状态抬到「已证实」。
     *
     * <p>{@code AND evidence_state <> 'confirmed'} 是这条 SQL 的重点，
     * 它让这个方法天然幂等：
     * <ul>
     *   <li>返回 1 —— 是<b>第一次</b>证实，调用方可以据此决定要不要记一条日志/推一条通知</li>
     *   <li>返回 0 —— 之前已经证实过了（QoS 1 的重复投递、或者一秒内的第 N 条遥测），
     *       什么都不用做</li>
     * </ul>
     * 有了它，就算同一批遥测被消费线程并发处理，也只有一次会「生效」。
     *
     * <p>它同时也是 {@code stale → confirmed} 的通道：标了过期之后
     * 只要真数据来了，照样能翻回来 —— 过期的判断本来就只是「暂时没等到」。
     *
     * @param evidenceAt 证据时刻，传的应是<b>我们收到的时刻</b>（reported_at），
     *                   不是设备报的时刻：设备时钟不一定准，而「什么时候拿到了旁证」
     *                   问的是我们这边的事实。设备报的时刻原样存在 task_evidence.observed_at 里，
     *                   需要看的时候去那张表看，两边职责不混。
     * @return 1 首次证实；0 已经证实过
     */
    int markEvidenceConfirmed(@Param("taskId") Long taskId,
                              @Param("evidenceType") String evidenceType,
                              @Param("evidenceAt") LocalDateTime evidenceAt);

    /**
     * 扫描「声明了要执行、但迟迟没有旁证」的任务，供定时任务标记为过期。
     *
     * <p>判定线是 {@code actual_start_time}——它正是飞手点「开始执行」的那一刻，
     * 也就是「声明」发生的时间。从那一刻起算多久没数据才算过期，由调用方算好传进来。
     *
     * <p>只扫 {@code executing} + {@code declared} 的组合：
     * 其它状态要么没有声明（还没开工），要么已经有依据（confirmed）。
     * 已经标成 stale 的不再重复扫，避免每轮都产生同样的日志。
     */
    List<TaskPO> selectStaleEvidenceTasks(@Param("deadline") LocalDateTime deadline,
                                          @Param("limit") int limit);

    /**
     * 把任务的证据状态标成「声明已过期」。
     *
     * <p>{@code AND evidence_state = 'declared'} 保证两件事：
     * 一是重复执行无副作用；二是<b>绝不会覆盖掉已经拿到的证据</b> ——
     * 如果这期间遥测到了、状态已是 confirmed，这里会返回 0 行。
     * 换句话说，定时任务是「只降级没依据的」，不是「无条件覆盖」。
     *
     * @return 1 标记成功；0 状态已变（多半是刚被证实）
     */
    int markEvidenceStale(@Param("taskId") Long taskId);
}
