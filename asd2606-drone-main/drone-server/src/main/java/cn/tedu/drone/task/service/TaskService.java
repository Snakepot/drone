package cn.tedu.drone.task.service;

import cn.tedu.drone.common.PageResult;
import cn.tedu.drone.task.pojo.AlertQueryParam;
import cn.tedu.drone.task.pojo.AlertVO;
import cn.tedu.drone.task.pojo.TaskAlertReportParam;
import cn.tedu.drone.task.pojo.TaskAttachmentVO;
import cn.tedu.drone.task.pojo.TaskCreateParam;
import cn.tedu.drone.task.pojo.TaskDetailVO;
import cn.tedu.drone.task.pojo.TaskDispatchOptionsVO;
import cn.tedu.drone.task.pojo.TaskCheckinParam;
import cn.tedu.drone.task.pojo.TaskCheckinVO;
import cn.tedu.drone.task.pojo.TaskDispatchParam;
import cn.tedu.drone.task.pojo.TaskMapVO;
import cn.tedu.drone.task.pojo.TaskMonitorVO;
import cn.tedu.drone.task.pojo.TaskFinishParam;
import cn.tedu.drone.task.pojo.TaskQueryParam;
import cn.tedu.drone.task.pojo.TaskRefuseParam;
import cn.tedu.drone.task.pojo.TaskReportReviseParam;
import cn.tedu.drone.task.pojo.TaskReportVO;
import cn.tedu.drone.task.pojo.TaskVO;
import cn.tedu.drone.task.pojo.TaskWithdrawParam;
import cn.tedu.drone.zone.pojo.ZoneHitVO;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 任务业务接口（MVP 第二阶段：创建、派发、查询）
 *
 * <p>返回值一律是 VO，不是 PO：PO 属于「表和 DAO 之间」的东西，
 * 出了 Service 就该换成对前端友好的视图对象（原因见 {@link TaskVO} 的类注释）。
 */
public interface TaskService {

    /**
     * 创建任务。
     *
     * <p>创建出来的任务状态固定为 {@code pending}（待派发），
     * 创建人从登录态取，并顺手写一条流转流水。
     *
     * @param param 任务内容（名称、优先级、计划时间、描述）
     * @return 创建好的任务（含 id 与任务编号）
     * @throws cn.tedu.drone.common.BusinessException 必填项为空、优先级非法、时间区间颠倒
     */
    TaskVO createTask(TaskCreateParam param);

    /**
     * 分页查询任务（监控页）
     *
     * @param param 查询条件（关键字 / 状态 / 优先级 / 飞手 + 分页）
     * @return 分页结果
     */
    PageResult<TaskVO> pageTasks(TaskQueryParam param);

    /**
     * 查询任务详情，含状态流转流水。
     *
     * @param id 任务 ID
     * @return 任务本体 + 流水
     */
    TaskDetailVO getTaskDetail(Long id);

    /**
     * 查询「派给我的任务」（飞手端）。
     *
     * <p>与 {@link #pageTasks} 的关键差别：<b>查询条件里的飞手 ID 由服务端从登录态取，
     * 前端传什么都不作数</b>。这不是「顺手覆盖一下」，而是这个接口存在的理由 ——
     * 否则飞手只要把 {@code pilotId} 改成别人的，就能看别人的任务。
     *
     * <p>关键词、状态、分页这些条件照常生效，飞手可以用它们筛自己的任务。
     *
     * @param param 前端传来的筛选与分页条件（其中的 pilotId 会被忽略）
     * @return 分页结果
     * @throws cn.tedu.drone.common.BusinessException 取不到登录态（正常有拦截器保证，这里是兜底）
     */
    PageResult<TaskVO> pageMyTasks(TaskQueryParam param);

    /**
     * 派发任务：指定飞手与设备。
     *
     * <p>派发是这个系统里<b>第一处真正占用资源</b>的操作 ——
     * 创建时只写了「要做什么」，到这里才决定「谁去做、用哪台设备」。
     * 所以校验也集中在这里：人得是能飞的、设备得是能用的、两边在任务时间段内都得是空的。
     *
     * @param param 任务 ID + 飞手 ID + 设备 ID（+ 选填附言）
     * @return 派发后的任务
     * @throws cn.tedu.drone.common.BusinessException 任务不存在、状态不允许派发、
     *         飞手不存在/已禁用/没有飞手角色、设备不存在/不是待命状态、时间窗冲突
     */
    TaskVO dispatchTask(TaskDispatchParam param);

    /**
     * 创建任务前的禁飞区预检：<b>这条航线 + 这个起降点会不会被拦</b>。
     *
     * <h2>为什么要有这个「只查不写」的接口</h2>
     * 命中禁飞区是「软拦」：确认已知晓风险就能继续（见 {@code TaskCreateParam}）。
     * 前端得先知道<b>撞的是哪几块区域</b>才能把那句话摆在人面前 ——
     * 而这个信息在创建之前只有服务端算得出来（航线在库里，前端手上只有 routeId）。
     *
     * <p>注意它<b>不代替</b>创建时的校验：创建接口自己也会再查一遍。
     * 预检只是为了让提示有内容，真正的底线在写操作那一步 ——
     * 否则绕过前端直接调创建接口就能免检。
     *
     * @param routeId     引用的航线模板 id，可空（不引用航线就没得判）
     * @param takeoffLng  起降点经度，<b>GCJ-02</b>（与创建接口收的是同一种），可空
     * @param takeoffLat  起降点纬度，GCJ-02，可空
     * @return 命中记录；没命中时是空列表
     */
    List<ZoneHitVO> checkPlanZones(Long routeId, Double takeoffLng, Double takeoffLat);

    /**
     * 派发任务前的禁飞区预检：<b>这条任务的计划会不会被拦</b>。
     *
     * @return 命中记录；没命中时是空列表
     * @throws cn.tedu.drone.common.BusinessException 任务不存在
     */
    List<ZoneHitVO> checkTaskZones(Long taskId);

    /**
     * 监控看板：当前所有<b>执行中</b>任务的位置与状态。
     *
     * <p>为什么不是「分页的任务列表 + 前端自己拼位置」：看板上一行的四个信息
     * （谁在飞、现在在哪、刚才怎么飞、打算怎么飞）必须**一起刷新**，
     * 拆开就会出现「这一行的位置是 3 秒前、航线是 10 秒前」。
     *
     * <p>执行中的任务数量天然有限（一次派发一条、飞完就结束），所以不做分页。
     */
    List<TaskMonitorVO> monitorTasks();

    /**
     * 到点打卡（飞手操作）：把「我到了」变成一条有坐标的记录。
     *
     * <p><b>它只算飞手自述这一档弱依据</b>，不能替代设备数据 ——
     * 界面上必须与设备坐标区别显示（见设计文档 7.2）。
     *
     * <p>偏差由服务端算：客户端传的坐标只用来算这个数，不信客户端传的偏差
     * （否则谁来打卡都能自己填一个 0 米）。目标点也由服务端从任务的航线快照里取。
     *
     * @throws cn.tedu.drone.common.BusinessException 任务不存在/不是派给你的、
     *         这条任务上没有这个航点、坐标非法、缺打卡时刻
     */
    TaskCheckinVO checkIn(TaskCheckinParam param);

    /**
     * 取某条任务的打卡记录，按航点序号升序。
     *
     * <p><b>本方法不做鉴权</b>：管理端走 {@code getTaskDetail}（那里已经校验过
     * {@code task:list}），飞手端走 {@link #getMyTaskMap}（那里 {@code requireMyTask} 过）。
     * 鉴权只保留在入的那一处 —— 在数据读取这一层再抄一遍「本人判断」，
     * 两处早晚会不一致，而不一致的那一次就是越权。
     */
    List<TaskCheckinVO> listCheckins(Long taskId);

    /**
     * 飞手端「这一条任务的地图」：计划航线 + 起降点 + 自己飞出来的轨迹 + 打卡记录。
     *
     * <p>合成一个接口，是因为飞手在野外点开一次就要看到全部 ——
     * 弱网下多一次请求就多一次转圈；而且这些数据都要一起刷新（打完卡进度要变）。
     */
    TaskMapVO getMyTaskMap(Long taskId);

    /**
     * 撤回派发：清空指派的飞手与设备，任务退回「待派发」。
     *
     * <p>撤回后任务本身的内容（名称、计划时间、任务内容）都保留，
     * 只是没人没设备了，可以重新派给别人。
     *
     * @param param 任务 ID（+ 选填撤回原因）
     * @return 撤回后的任务
     * @throws cn.tedu.drone.common.BusinessException 任务不存在、状态不是「已派发」
     */
    TaskVO withdrawDispatch(TaskWithdrawParam param);

    /**
     * 开始执行（飞手操作）。
     *
     * <p>状态从「已派发」变「执行中」，同时落下实际开始时间、并把设备置为「飞行中」——
     * 「已派出」表示派好了还没起飞，「飞行中」才是真的在天上，两者不能混为一谈。
     *
     * <p><b>只能操作派给自己的任务</b>：飞手 id 从登录态取，
     * 与任务上的 pilot_id 比对，不匹配就拒绝 —— 否则任何飞手都能去动别人的任务。
     *
     * @param taskId 任务 ID
     * @return 开始后的任务
     * @throws cn.tedu.drone.common.BusinessException 任务不存在、不是派给你的、状态不允许开始
     */
    TaskVO startTask(Long taskId);

    /**
     * 拒绝执行（飞手操作）。
     *
     * <p>效果与「撤回派发」一样：任务退回「待派发」、设备与飞手释放，
     * 于是管理员可以直接把这条任务重新指派给别人。
     * 但流水里记的是 {@code refuse} 而不是 {@code withdraw} ——
     * 「管理员反悔」和「飞手不接」是两回事，事后要分得清。
     *
     * <p>同时会通知所有能重新指派的人（拥有 {@code task:dispatch} 权限的账号）。
     *
     * @param param 任务 ID + 选填原因
     * @return 拒绝后的任务（状态已是「待派发」）
     * @throws cn.tedu.drone.common.BusinessException 任务不存在、不是派给你的、状态不允许拒绝
     */
    TaskVO refuseTask(TaskRefuseParam param);

    /**
     * 派发弹窗要用的下拉数据（可派的飞手 + 可用的设备）。
     *
     * <p>单独一个接口而不是让前端去调员工、设备接口，是为了守住权限边界：
     * 有 {@code task:dispatch} 就该能完成派发，不必再额外拿到
     * {@code staff:list} 与 {@code device:list}。
     *
     * @return 两组下拉选项
     */
    TaskDispatchOptionsVO getDispatchOptions();

    /**
     * 释放「派发出去但计划时间已过」的任务占用的资源。
     *
     * <p>这是兜底：派发会把飞手置「作业中」、设备置「已派出」，
     * 如果飞手一直没执行、管理员也没点撤回，这两个资源就被永久占住了。
     * 定时任务调它把过期未开工的任务收回来（见 docs/任务业务设计.md 的「资源占用与释放」一章）。
     *
     * <p>动作与「撤回派发」完全一致，只是流水里的 {@code action} 是
     * {@code expire}、操作人记为「系统」—— 时间轴上要能分清是人撤的还是系统释放的。
     *
     * <p>幂等：条件更新保证了并发或重复执行不会重复释放。
     *
     * @return 本次实际释放的任务条数
     */
    int releaseExpiredDispatched();

    /**
     * 把「声明了要执行、但迟迟拿不到任何客观依据」的任务标记为证据过期。
     *
     * <p>这是<b>证据维度</b>的定时维护动作，与 {@link #releaseExpiredDispatched()}
     * 是两件必须分开的事：
     * <ul>
     *   <li>那个改 {@code status}，把被占住的飞手与设备收回来</li>
     *   <li>这个只改 {@code evidence_state}，一个字都不碰 {@code status}</li>
     * </ul>
     * 为什么不能合并：「派发了没开工」和「开工了但设备数据没上来（图传断了、
     * 机场信号差）」是两种完全不同的情况，处理方式也完全不同 ——
     * 前者要把资源收回来，后者把资源收回来就是在冤枉一个可能真的在天上飞的人。
     * 系统没有立场替他判断「到底飞没飞」，只该把「这里对不上」摆出来给人看。
     *
     * @param staleSeconds 从「开始执行」那一刻起算，多久没有设备数据算过期。
     *                     由调用方传入而不是在这里读配置，是为了让阈值成为一个<b>显式参数</b>：
     *                     测试可以直接传 0 来构造过期场景，不必去动配置文件
     * @return 本次实际标记的条数
     */
    int markStaleEvidence(int staleSeconds);

    // ------------------------------------------------------------------
    // 报告线：任务收尾（完成 / 失败）与报告
    // ------------------------------------------------------------------

    /**
     * 完成任务并提交报告 —— <b>一个接口收三条线</b>（状态 / 资源 / 交付物）。
     *
     * <p>为什么不做成「先提交报告、再点完成」两步：那会造出一个
     * 「任务还在执行中、报告却已提交」的中间态，而这个状态下
     * <b>没有任何人需要做任何事</b> —— 没有决策者的中间态不该存在。
     * 见 {@code docs/任务收尾与执行报告.md} 2.2 节。
     *
     * <p>做的事，顺序不能变：
     * <ol>
     *   <li>{@code markFinished}（CAS：只有还是执行中才能改）</li>
     *   <li>写报告（多版本：翻旧版本 → 插新版本）</li>
     *   <li>绑附件（校验每个附件都属于这条任务）</li>
     *   <li><b>再</b>统计飞手手上还有没有活，为 0 才把飞手改回空闲</li>
     * </ol>
     * 第 4 步必须在第 1 步之后：{@code countActiveByPilot} 统计的是
     * 「已派发 + 执行中」，先统计的话这条任务自己会被算进去，飞手永远回不到空闲。
     *
     * <p>**刻意不清空指派关系**（不像撤回/拒绝）：报告要写清是谁飞的、用哪架飞的。
     *
     * <p>两件**不校验**的事（2026-09-24 拍板）：不要求
     * {@code evidence_state = confirmed}（强制会把「设备数据链路不通」
     * 变成「飞手无法收工」），也不要求必须有照片。
     * 前者会在报告上标出来（{@code onlyDeclared}），让人看得见。
     *
     * @param param 状态 + 报告内容 + 附件 id
     * @return 收尾后的任务
     * @throws cn.tedu.drone.common.BusinessException 任务不是派给你的、状态不允许收尾、
     *         结论非法、时长超范围、附件不属于这条任务、状态刚被别处改过
     */
    TaskVO finishTask(TaskFinishParam param);

    /**
     * 标记执行失败 —— 与完成任务同一套，只多两点：
     * {@code problemLog} 必填，状态落 {@code failed}（{@code result} 由服务端写死）。
     *
     * <p>为什么要有它：没有这条出口，飞手遇到「飞机飞一半故障了」只能<b>谎报完成</b>，
     * 数据里就再也分不出「真完成」和「失败了但走了完成」——
     * 而这两种事后要分开看（前者结算、后者复盘）。
     */
    TaskVO failTask(TaskFinishParam param);

    /**
     * 查某条任务的报告详情（手填部分 + 系统自动汇总）。
     *
     * <p>权限：管理侧（{@code task:list}）能看任意任务的报告，
     * 飞手只能看自己那条（{@code task:execute} + 归属校验）——
     * 与附件下载同一条规则，实现方式也一样（放行到服务层判，
     * 因为 {@code @RequirePermission} 的多个值是 AND，表达不了「或」）。
     *
     * @throws cn.tedu.drone.common.BusinessException 任务不存在、无权查看、
     *         或这条任务还没有报告
     */
    TaskReportVO getTaskReport(Long taskId);

    /**
     * 修订报告 —— <b>只动报告，不动任务状态</b>。
     *
     * <p>为什么要有它：报告是交付物，飞手事后发现自己写错了一个数字、
     * 管理员复核后要求补充、客户提异议，都会导致要改。
     * 而「改过必须看得出来」是当初拍板多版本的唯一理由 ——
     * 所以每改一次就多一个版本，旧的留着。
     *
     * <p><b>它为什么不复用 {@code finishTask}</b>：收尾要「任务还在执行中」，
     * 而修订发生在事后（任务多半已经完成）。修订也不该动设备与飞手资源 ——
     * 那些在收尾时已经释放过了。
     *
     * <p>权限与「看报告」同一条规则：管理侧能改任意任务的，
     * 否则必须是本人的任务（用同一处判断，避免出现「能改但不能看」这种怪事）。
     *
     * @param param 任务 id + 报告内容（字段与收尾时一样）
     * @return 修订后的报告（新版本）
     * @throws cn.tedu.drone.common.BusinessException 任务不存在、无权修订、
     *         结论非法、时长超范围、附件不属于这条任务
     */
    TaskReportVO reviseReport(TaskReportReviseParam param);

    // ------------------------------------------------------------------
    // 飞手端：告警（看到 + 上报）与附件（实施步骤第五步）
    // ------------------------------------------------------------------

    /**
     * 查询「我这条任务上的告警」（飞手端任务展开后的清单）。
     *
     * <p><b>归属校验在这里做</b>（{@code requireMyTask}），不在告警模块里做：
     * 「这条任务是不是派给我的」这条规则只有一处实现，抄第二份迟早会不一致。
     * 所以接口里也<b>没有 pilotId 参数</b>，飞手身份从登录态取。
     *
     * <p>返回的告警是<b>只读</b>的：飞手对告警没有任何写操作（不认领、不关闭）。
     * 理由见 {@code docs/告警体系设计.md} 第十二章 ——
     * 让飞手既能报又能关，等于自己给自己销账。
     *
     * @param taskId 任务 id
     * @param param  分页条件（任务条件是服务端强制的，不接受前端传别的任务）
     * @throws cn.tedu.drone.common.BusinessException 任务不存在、或不是派给当前登录人的
     */
    PageResult<AlertVO> pageMyTaskAlerts(Long taskId, AlertQueryParam param);

    /**
     * 现场异常上报（人工上报类 4 条）。
     *
     * <p>不限任务状态：只校验「是不是派给我的」。飞机落地后才发现机身损伤也算现场问题，
     * 而那时候任务可能已经完成了。
     *
     * <p>可以带照片。附件要先调 {@link #uploadTaskAttachment} 拿到 id，
     * 再随本接口一起提交 —— 有附件不属于这条任务时，整笔上报会回滚。
     *
     * @param param 任务 id + 告警编码 + 说明 + 附件 id
     * @return 新产生的告警（前端据此提示「已上报」）
     * @throws cn.tedu.drone.common.BusinessException 任务不是派给你的、编码不是人工上报类、
     *         说明为空、附件不属于这条任务
     */
    AlertVO reportOnsiteIssue(TaskAlertReportParam param);

    /**
     * 上传一个附件（现场照片、飞行日志）到某条任务。
     *
     * <p>校验三件事，顺序是「扩展名白名单 → 分类型大小上限 → 落盘」，
     * 前两步不过就一个字节都不写盘（见 {@code AttachmentServiceImpl}）。
     *
     * <p>上传与「把附件挂到某条告警上」是分开的两步：上传时告警还不存在。
     *
     * @param taskId      归属任务（必须是派给当前登录人的）
     * @param file        文件
     * @param description 说明，选填
     * @return 附件信息（含 id，前端拿着它去提交上报）
     * @throws cn.tedu.drone.common.BusinessException 任务不是派给你的、文件为空、
     *         扩展名不在白名单、超过该类型的大小上限
     */
    TaskAttachmentVO uploadTaskAttachment(Long taskId, MultipartFile file, String description);

    /**
     * 下载一个附件。
     *
     * <h2>为什么这个方法不用 {@code @RequirePermission}</h2>
     * 这里的规则是「<b>有 {@code task:list} 就能下任意任务，否则必须是这条任务的飞手</b>」——
     * 一个「或」。而 {@code @RequirePermission} 的多个值是「<b>同时具备</b>」（AND），
     * 表达不了这种情况。所以放行到服务层判，判据就是
     * {@code UserContext.get().hasPermission(...)}（拦截器用的是同一个方法）。
     *
     * <p>要文件的人分两种：管理侧（看飞手报的证据）与飞手本人（传完想确认一下）。
     * 两种都不该被挡住，但也不能让任何一个飞手下到别人的照片。
     *
     * @param attachmentId 附件 id
     * @return 元信息 + 可流式读的内容
     * @throws cn.tedu.drone.common.BusinessException 附件不存在、无权下载、文件已不在
     */
    AttachmentService.LoadedAttachment loadTaskAttachment(Long attachmentId);
}
