package cn.tedu.drone.task.service;

import cn.tedu.drone.common.PageResult;
import cn.tedu.drone.device.pojo.DevicePO;
import cn.tedu.drone.task.mqtt.DeviceMessage;
import cn.tedu.drone.task.pojo.AlertHandleParam;
import cn.tedu.drone.task.pojo.AlertPO;
import cn.tedu.drone.task.pojo.AlertQueryParam;
import cn.tedu.drone.task.pojo.AlertVO;
import cn.tedu.drone.task.pojo.TaskAlertCount;
import cn.tedu.drone.task.pojo.TaskPO;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 告警业务接口。
 *
 * <p>设计见 {@code docs/告警体系设计.md}。已实现实施步骤的第一步
 * （识别与落库）与第二步（查询与处理闭环）。
 */
public interface AlertService {

    /**
     * 从一条设备报文里识别告警并落库。
     *
     * <p><b>必须在证据落库之后调用</b>：告警识别失败不该影响证据，
     * 而「先存证据、再算告警」这个顺序天然保证了这一点
     * （证据已经在库里了，告警这步出什么事都丢不掉证据）。
     *
     * <p><b>不抛异常</b>：调用方是消费线程，一条报文的告警算不出来
     * 不能让整条线程受影响。内部吞掉异常并记 error。
     *
     * <p>方法名不叫「raiseForTelemetry」：遥测、状态、事件三种 topic 都会走到这里
     * （见 {@code AlertDetector} 的类注释），只有人工上报不走它 ——
     * 名字要是只说得出遥测，读代码的人会以为另外两种没接上。
     *
     * @param message    设备消息
     * @param device     已确认登记的设备
     * @param task       该设备上执行中的任务，可为 null（告警可以不属于任何任务）
     * @param observedAt 报文里的时刻（调用方已解析好）
     * @return 本次<b>新增</b>的告警条数；重复上报的、以及识别不出告警的都算 0
     */
    int raiseForDeviceMessage(DeviceMessage message, DevicePO device, TaskPO task, LocalDateTime observedAt);

    /**
     * 查询<b>某条任务</b>上的告警（飞手端「我这条任务上的告警」用它）。
     *
     * <p><b>本方法不做鉴权</b>：调用方（{@code TaskService}）已经用
     * {@code requireMyTask} 确认过「这条任务是不是派给当前登录人的」。
     * 鉴权只有那一处实现 —— 在数据访问这一层再抄一遍「本人判断」，
     * 两处早晚会不一致，而不一致的那一次就是越权。
     *
     * @param taskId 任务 id
     * @param param  分页与筛选（任务条件由本方法强制写入，不接受调用方传别的任务）
     */
    PageResult<AlertVO> pageAlertsOfTask(Long taskId, AlertQueryParam param);

    /**
     * 飞手<b>现场异常上报</b> —— 人工上报类 4 条的唯一入口。
     *
     * <h2>三条与设备告警不同的地方</h2>
     * <ol>
     *   <li>只接受 {@link cn.tedu.drone.task.pojo.AlertCode#MANUAL_CODES} 里的编码。
     *       编码是客户端传上来的，不校验的话飞手能提交一条长得像 {@code bat.critical}
     *       的告警 —— 那会让「设备测到的」和「人说的」在库里再也分不开</li>
     *   <li>{@code device_code} 存 <b>NULL</b>：这条不是设备说的。
     *       顺带得到一个想要的性质 —— 抑制规则（同一设备同一编码还有未处理的就不再产生）
     *       因为 {@code NULL = NULL} 不成立而失效，于是<b>同一条任务上飞手报两次
     *       「现场有干扰」是两条记录</b>，不会被合成一条。这是设计文档 4.4 节明确要的行为</li>
     *   <li>可以带附件（现场照片）。附件要先通过上传接口拿到 id，随上报一起提交</li>
     * </ol>
     *
     * @param task          归属任务（调用方已校验归属）
     * @param alertCode     人工上报类编码
     * @param message       飞手填的说明
     * @param attachmentIds 附件 id（可为空）；必须都属于 {@code task}
     * @return 新产生的告警（含 id）
     * @throws cn.tedu.drone.common.BusinessException 编码不是人工上报类、说明为空、
     *         或有附件不属于这条任务（后者会让整笔上报回滚）
     */
    AlertPO raiseManualReport(TaskPO task, String alertCode, String message, List<Long> attachmentIds);

    /**
     * 统计一批任务各自还有多少条<b>未处理</b>告警（飞手端任务卡片角标用）。
     *
     * <p>一条 {@code GROUP BY} 查完一页任务，不在循环里逐个查。
     * 放在告警模块里而不是让任务模块直接查 {@code task_alert}，
     * 是为了让「告警表长什么样」只被一个模块知道。
     *
     * @param taskIds 任务 id 集合
     * @return taskId → 计数。**结果里只会出现「有未处理告警」的任务** ——
     *         查不到就是 0 条，调用方按这个约定处理
     */
    Map<Long, TaskAlertCount> countOpenAlertsByTaskIds(Collection<Long> taskIds);

    /**
     * 某条任务上一共有几条告警（<b>不分状态</b>）。
     *
     * <p>口径与飞手端角标刻意不同：角标只数未处理的（回答「现在有什么要管的」），
     * 报告里要的是「这次任务期间共发生了几条」（回答「当时发生了什么」）。
     * 两者混用会让报告上的数字随处置进度变来变去 —— 那不是报告该有的性质。
     */
    int countAlertsOfTask(Long taskId);

    /**
     * 查「本次任务期间」的告警 —— 任务详情页那个区块用它。
     *
     * <p>与 {@link #pageAlertsOfTask} 的区别是**查询口径**：
     * 那个只按 {@code task_id} 查（飞手看「挂在我这条任务上的」），
     * 这个按 {@code task_id} <b>或</b>「设备 + 任务时间窗」查 ——
     * 后者能把「上报时还没有执行中任务、所以 task_id 为空」的告警捞回来。
     * 时间窗取计划与实际的并集（理由见实现里的注释）。
     *
     * <p>排序按发生时刻升序：详情页里它是时间线的一部分，
     * 与告警中心「未处理的排前面」刻意不同。
     */
    List<AlertVO> listAlertsInTaskWindow(TaskPO task);

    /**
     * 分页查询告警（管理端告警页）。
     *
     * <p>排序在 SQL 里定好：**未处理的在前、级别高的在前、时间新的在前**。
     * 见 {@code AlertMapper.xml} 里对排序的说明 —— 页面第一屏该回答
     * 「现在有什么要管的」，而不是「最近发生了什么」。
     */
    PageResult<AlertVO> pageAlerts(AlertQueryParam param);

    /**
     * 查一条告警（处理前的确认、或详情弹窗）。
     *
     * @throws cn.tedu.drone.common.BusinessException 告警不存在
     */
    AlertVO getAlert(Long id);

    /**
     * 处理一条告警：认领 / 标记已处理 / 标记误报。
     *
     * <p>状态流转的合法性由 {@code AlertStatusFlow} 校验；
     * 「收尾」两个动作<b>必须写处置说明</b> —— 没有说明的「已处理」
     * 和不处理没有区别（理由见 {@code AlertHandleParam} 的注释）。
     *
     * <p>处理人从登录态取，不接受前端传 —— 否则谁都能把一条告警记成
     * 「张三处理的」。
     *
     * @return 处理后的告警
     * @throws cn.tedu.drone.common.BusinessException 告警不存在、动作非法、
     *         当前状态不允许该动作、收尾时没写说明、状态刚被别处改过
     */
    AlertVO handleAlert(AlertHandleParam param);
}
