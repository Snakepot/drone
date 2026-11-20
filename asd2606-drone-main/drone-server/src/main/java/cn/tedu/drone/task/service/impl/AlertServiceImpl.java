package cn.tedu.drone.task.service.impl;

import cn.tedu.drone.common.BusinessException;
import cn.tedu.drone.common.LoginUser;
import cn.tedu.drone.common.PageResult;
import cn.tedu.drone.common.UserContext;
import cn.tedu.drone.device.pojo.DevicePO;
import cn.tedu.drone.task.alert.AlertDetector;
import cn.tedu.drone.task.alert.AlertProperties;
import cn.tedu.drone.task.dao.AlertDao;
import cn.tedu.drone.task.mqtt.DeviceMessage;
import cn.tedu.drone.task.pojo.AlertCode;
import cn.tedu.drone.task.pojo.AlertHandleParam;
import cn.tedu.drone.task.pojo.AlertPO;
import cn.tedu.drone.task.pojo.AlertQueryParam;
import cn.tedu.drone.task.pojo.AlertSeverity;
import cn.tedu.drone.task.pojo.AlertStatusFlow;
import cn.tedu.drone.task.pojo.AlertVO;
import cn.tedu.drone.task.pojo.EvidenceType;
import cn.tedu.drone.task.pojo.TaskAlertCount;
import cn.tedu.drone.task.pojo.TaskAttachmentVO;
import cn.tedu.drone.task.pojo.TaskPO;
import cn.tedu.drone.task.service.AlertService;
import cn.tedu.drone.task.service.AttachmentService;
import cn.tedu.drone.task.ws.AlertNotifier;
import cn.tedu.drone.user.dao.UserDao;
import cn.tedu.drone.user.pojo.UserPO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 告警落库。
 *
 * <h2>为什么识别和落库分成两个类</h2>
 * {@link AlertDetector} 是纯判断（阈值、边界、互斥关系），最容易写错、也最需要反复测；
 * 本类只负责「把判断结果写进库 + 记日志」。分开以后，
 * 判断逻辑可以用很轻的方式单测，不用起 Spring、不用连数据库。
 *
 * <h2>为什么不加事务</h2>
 * 一次调用要插的告警通常是 0~2 条（绝大多数遥测一条都不触发）。
 * 为这种数量级包事务，代价是消费线程多占一个数据库连接与锁，
 * 而收益几乎为零 —— 真出现「插了一条、第二条失败」，丢掉的是第二条告警，
 * 下一条遥测（一秒后）还会再判一次。和 {@code TelemetryIngestServiceImpl}
 * 的取舍一致：<b>能自愈的最终一致，好过吞吐上的长期损失</b>。
 *
 * <h2>为什么异常要在这里吞掉</h2>
 * 调用它的时候证据已经落库了（见 {@code TelemetryIngestServiceImpl} 的 ⑦ 步），
 * 所以告警这步失败**不会丢证据**。但它也不该把异常抛给消费线程 ——
 * 抛上去会被记成「处理设备消息失败」，看起来像整条消息都没处理成，
 * 而实际上只有告警这一小块没成。<b>把失败的范围说准，排查时少绕一大圈。</b>
 */
@Slf4j
@Service
public class AlertServiceImpl implements AlertService {

    private final AlertDetector detector;
    private final AlertProperties properties;
    private final AlertDao alertDao;
    private final UserDao userDao;
    private final AlertNotifier alertNotifier;
    private final AttachmentService attachmentService;

    public AlertServiceImpl(AlertDetector detector, AlertProperties properties, AlertDao alertDao,
                            UserDao userDao, AlertNotifier alertNotifier,
                            AttachmentService attachmentService) {
        this.detector = detector;
        this.properties = properties;
        this.alertDao = alertDao;
        this.userDao = userDao;
        this.alertNotifier = alertNotifier;
        this.attachmentService = attachmentService;
    }

    @Override
    public int raiseForDeviceMessage(DeviceMessage message, DevicePO device, TaskPO task, LocalDateTime observedAt) {
        try {
            List<AlertPO> alerts = detector.detect(message, device, task, observedAt);
            if (alerts.isEmpty()) {
                return 0;
            }

            int inserted = 0;
            for (AlertPO alert : alerts) {
                if (alertDao.insertUnlessOpen(alert) > 0) {
                    inserted++;
                    logNewAlert(alert);
                    // 只在「真的新产生了一条」时才推 —— 限频就靠这一层：
                    // 同一个问题还有未处理的告警时 insertUnlessOpen 返回 0，
                    // 根本走不到这里，不需要额外的定时器或时间窗去算「多久没推了」
                    //
                    // 这里没有 afterCommit：遥测处理这条链路本来就不带事务
                    // （见 TelemetryIngestServiceImpl 的注释），插入即提交，
                    // 所以推的时候数据一定已经落库了
                    alertNotifier.notifyAlertRaised(alert);
                } else {
                    // 返回 0 有两种情况，都是设计内的：
                    //   ① 同一设备同一编码还有未处理的告警（阈值类最常见的重复来源 ——
                    //      遥测每秒一条，不能每来一条就报一次「电量还是低」）
                    //   ② 完全相同的这一条之前已经报过（QoS 1 重投、设备侧重发）
                    // 所以这里降到 debug：持续存在的问题每秒打一条 info 会把日志淹掉，
                    // 反而让「新出现的告警」看不见
                    log.debug("【MQTT 处理 ⑦ 告警识别】未新增（同一问题已有未处理的告警，"
                                    + "或这条已报过）：device={}，code={}",
                            alert.getDeviceCode(), alert.getAlertCode());
                }
            }
            return inserted;
        } catch (Exception e) {
            // 见类注释：范围说准 + 不抛给消费线程
            log.error("【MQTT 处理 ⑦ 告警识别】告警识别/落库失败，本条遥测的告警已跳过"
                            + "（证据已在第 ⑤ 步落库，不受影响），device={}",
                    device == null ? null : device.getCode(), e);
            return 0;
        }
    }

    /**
     * 新告警记一条 info。
     *
     * <p>打 info 而不是 debug，是因为这是**需要有人知道**的事件 ——
     * 而且现在还没有通知（那是实施步骤第三步的事），日志是唯一的即时出口。
     * 等通知做出来之后，这条日志仍然要留：通知会丢（人不在线），日志不会。
     */
    private void logNewAlert(AlertPO alert) {
        log.info("【MQTT 处理 ⑦ 告警识别】新增告警：{}（{}）｜级别={}｜设备={}｜任务={}｜需飞手={}｜{}",
                AlertCode.label(alert.getAlertCode()), alert.getAlertCode(),
                AlertSeverity.label(alert.getSeverity()),
                alert.getDeviceCode(),
                alert.getTaskId() == null ? "无" : alert.getTaskId(),
                alert.getActionHint() == null ? "否" : alert.getActionHint(),
                alert.getMessage());
    }

    // ------------------------------------------------------------------
    // 查询与处理（实施步骤第二步）
    // ------------------------------------------------------------------

    @Override
    public PageResult<AlertVO> pageAlerts(AlertQueryParam param) {
        return page((param == null) ? new AlertQueryParam() : param);
    }

    @Override
    public PageResult<AlertVO> pageAlertsOfTask(Long taskId, AlertQueryParam param) {
        AlertQueryParam query = (param == null) ? new AlertQueryParam() : param;
        // 任务条件是**强制写入**的，不接受调用方从参数里传别的任务 id ——
        // 那样越权就变成了「改一个查询参数」
        query.setTaskId(taskId);
        return page(query);
    }

    /**
     * 分页查询的公共部分：列表与详情页共用同一套「查总数 → 查列表 → 批量补附件」。
     *
     * <p>抽出来是为了让「按条件查」和「按任务查」不可能出现行为差异 ——
     * 它们唯一的区别就是条件对象里多了个 taskId。
     */
    private PageResult<AlertVO> page(AlertQueryParam query) {
        long total = alertDao.countByCondition(query);
        // 总数为 0 就不必再查一次列表
        List<AlertVO> list = (total == 0) ? List.of() : toVOList(alertDao.selectByCondition(query));

        return PageResult.of(list, total,
                query.getPage() == null ? 1 : query.getPage(),
                query.getLimit());
    }

    @Override
    public AlertVO getAlert(Long id) {
        AlertPO alert = alertDao.selectById(id);
        if (alert == null) {
            throw new BusinessException("告警不存在");
        }
        return toVOList(List.of(alert)).get(0);
    }

    // ------------------------------------------------------------------
    // 人工上报（实施步骤第五步：飞手端现场上报）
    // ------------------------------------------------------------------

    /**
     * 人工上报。
     *
     * <p>这里<b>加事务</b>，和 {@link #raiseForDeviceMessage} 刻意不加事务正好相反 ——
     * 两者面对的问题不同：
     * <ul>
     *   <li>设备报文那条路是**高频**的，加事务的代价（消费线程多占连接与锁）
     *       会被放大，而失败能靠下一条遥测自愈</li>
     *   <li>人工上报是**低频**的，且一次要做两件事（插告警 + 绑定附件）。
     *       这两件事必须一起成或一起败：只插了告警没绑上照片，
     *       飞手看到的是「上报成功了但照片没了」，而他没有任何办法补救</li>
     * </ul>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public AlertPO raiseManualReport(TaskPO task, String alertCode, String message, List<Long> attachmentIds) {
        if (task == null) {
            throw new BusinessException("请选择要上报的任务");
        }
        // 只收人工上报类的编码。这一关是**防伪造**：不校验的话，飞手可以提交一条
        // bat.critical，它看起来与「设备实测到电量 10%」一模一样，但没有任何数据支撑
        if (!AlertCode.isManual(alertCode)) {
            throw new BusinessException("不支持上报该类型的告警：" + alertCode
                    + "。现场上报只能选：" + String.join("、",
                    AlertCode.MANUAL_CODES.stream().map(AlertCode::label).toList()));
        }
        String text = StringUtils.hasText(message) ? message.trim() : null;
        if (text == null) {
            throw new BusinessException("请填写说明，管理员要据此判断怎么处理");
        }

        String pilotAction = properties.pilotActionOf(alertCode);
        LoginUser current = UserContext.get();

        AlertPO alert = new AlertPO()
                .setTaskId(task.getId())
                // device_code 刻意留空：这条不是设备说的。
                // 附带的后果是抑制规则对它不生效（NULL = NULL 不成立），
                // 所以同一任务上可以连着报两次「现场有干扰」—— 那本来也是两件事
                .setDeviceCode(null)
                .setAlertCode(alertCode)
                .setSeverity(AlertCode.defaultSeverity(alertCode))
                .setMessage(truncate(text))
                .setPayload(null)
                // 来源是 app（飞手端），与设备的 device、后台的 operator 区分开。
                // 这一列存在的意义就是事后能回答「这条是谁送来的」
                .setSource(EvidenceType.SOURCE_APP)
                .setObservedAt(LocalDateTime.now())
                .setReportedAt(LocalDateTime.now())
                .setStatus("pending")
                .setPilotActionRequired(pilotAction == null ? 0 : 1)
                .setActionHint(pilotAction);

        alertDao.insert(alert);

        // 附件必须在**这条告警有了 id 之后**才能绑。
        // bindToAlert 里会校验附件确实属于这条任务（越权保护），
        // 数量对不上就抛异常，把这次上报整个回滚掉 ——
        // 宁可让飞手重提交一次，也不能悄悄存下一条「没有照片的取证」
        attachmentService.bindToAlert(alert.getId(), task.getId(), attachmentIds);

        logNewAlert(alert);
        log.info("飞手现场上报已落库：taskId={}，编号={}，飞手={}，编码={}，附件 {} 个",
                task.getId(), task.getTaskNo(),
                current == null ? null : current.getUsername(),
                alertCode, attachmentIds == null ? 0 : attachmentIds.size());

        // 通知等提交后再推：现在还在事务里，万一提交失败，
        // 推出去的消息就成了「通知别人去处理一条并不存在的告警」
        afterCommit(() -> alertNotifier.notifyAlertRaised(alert));
        return alert;
    }

    @Override
    public Map<Long, TaskAlertCount> countOpenAlertsByTaskIds(Collection<Long> taskIds) {
        if (taskIds == null || taskIds.isEmpty()) {
            // 空的 IN 会拼出 `IN ()` 这种语法错误，所以在这里挡住而不是让 SQL 报错
            return Map.of();
        }
        Map<Long, TaskAlertCount> counts = new LinkedHashMap<>();
        for (TaskAlertCount row : alertDao.countOpenByTaskIds(taskIds)) {
            counts.put(row.getTaskId(), row);
        }
        return counts;
    }

    @Override
    public int countAlertsOfTask(Long taskId) {
        if (taskId == null) {
            return 0;
        }
        return (int) alertDao.countByCondition(new AlertQueryParam().setTaskId(taskId));
    }

    @Override
    public List<AlertVO> listAlertsInTaskWindow(TaskPO task) {
        if (task == null || task.getId() == null) {
            return List.of();
        }
        // 时间窗取「计划」与「实际」的**并集**：起取较早的那个、止取较晚的那个。
        //
        // 为什么不只用实际起止：要捞的那类告警（上报时还没有执行中的任务、
        // 所以 task_id 为空）往往就发生在**实际开始之前** —— 飞机先起飞、
        // 飞手后点「开始执行」，或者设备按计划时间提前开机自检。
        // 只用实际起止的话，这批恰恰是第一个被漏掉的。
        //
        // 为什么不只用计划时间：任务延后开工、或换天飞的时候，
        // 计划窗口根本框不住真实发生的事。
        //
        // 并集偏宽，但这是个**回顾**视图（不是待办队列）——
        // 宁可多给几条让人自己判断，也不要静默漏掉相关的。
        LocalDateTime from = earliest(task.getPlanStartTime(), task.getActualStartTime());
        LocalDateTime to = latest(task.getPlanEndTime(), task.getActualEndTime());

        List<AlertPO> alerts = alertDao.selectInTaskWindow(
                task.getId(), task.getDroneCode(), from, to);
        return toVOList(alerts);
    }

    /** 两个时间点里较早的那个（跳过 null）；都是 null 就返回 null */
    private static LocalDateTime earliest(LocalDateTime a, LocalDateTime b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.isBefore(b) ? a : b;
    }

    /** 两个时间点里较晚的那个（跳过 null）；都是 null 就返回 null */
    private static LocalDateTime latest(LocalDateTime a, LocalDateTime b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.isAfter(b) ? a : b;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AlertVO handleAlert(AlertHandleParam param) {
        if (param == null || param.getId() == null) {
            throw new BusinessException("请选择要处理的告警");
        }
        if (!AlertStatusFlow.ALL_ACTIONS.contains(param.getAction())) {
            throw new BusinessException("未知的处理动作：" + param.getAction());
        }

        AlertPO alert = alertDao.selectById(param.getId());
        if (alert == null) {
            throw new BusinessException("告警不存在");
        }

        // 状态机校验放在最前面：免得先做了别的动作（比如拼说明）再报错
        AlertStatusFlow.assertCanTransfer(param.getAction(), alert.getStatus());

        boolean closing = !AlertStatusFlow.ACTION_CLAIM.equals(param.getAction());
        String note = param.getNote() == null ? null : param.getNote().trim();
        if (closing && !StringUtils.hasText(note)) {
            // 收尾必须写说明。理由见 AlertHandleParam 的注释：
            // 一个没有说明的「已处理」和不处理没有区别，事后复盘时仍是黑盒
            throw new BusinessException("请填写处置说明");
        }

        LoginUser current = UserContext.get();
        Long operatorId = current == null ? null : current.getId();
        String operatorName = resolveOperatorName(current);

        int updated;
        if (AlertStatusFlow.ACTION_CLAIM.equals(param.getAction())) {
            updated = alertDao.claim(alert.getId());
        } else {
            updated = alertDao.close(alert.getId(),
                    // 允许的来源状态从状态机里取，不在这里写死 ——
                    // 否则「误报能从哪来」这件事就有两处定义，早晚对不上
                    allowedFrom(param.getAction()),
                    AlertStatusFlow.targetStatus(param.getAction()),
                    operatorId, operatorName, LocalDateTime.now(), note);
        }

        if (updated == 0) {
            // 走到这里说明状态在这几毫秒里被改过（比如另一个人抢先认领了）。
            // 这种情况必须报错让用户刷新，不能"当作成功"继续 ——
            // 否则两个人会以为自己都在处理同一条告警
            throw new BusinessException("告警状态刚刚发生了变化，请刷新后重试");
        }

        log.info("告警已{}，id={}，编码={}，设备={}，原状态={}",
                AlertStatusFlow.actionLabel(param.getAction()),
                alert.getId(), alert.getAlertCode(), alert.getDeviceCode(), alert.getStatus());

        return toVOList(List.of(alertDao.selectById(alert.getId()))).get(0);
    }

    /**
     * 这个动作允许从哪些状态出发 —— 从状态机里问，而不是在这里写死。
     *
     * <p>{@code AlertStatusFlow} 里那张表是私有的，所以走一个
     * 「用 ALL 过滤一遍 canTransfer」的方式取：这样**判据只有一处**，
     * 这个方法只是把判据换个形状交给 SQL。
     */
    private static List<String> allowedFrom(String action) {
        return AlertStatusFlow.ALL.stream()
                .filter((status) -> AlertStatusFlow.canTransfer(action, status))
                .toList();
    }

    private String resolveOperatorName(LoginUser current) {
        // 登录态里只有 username（手机号）没有姓名，要显示「谁处理的」得查一次库 ——
        // 和任务模块的 writeLog 同一个做法（那里也是 resolveUserName 查 userDao）
        if (current == null) {
            return null;
        }
        if (current.getId() != null) {
            UserPO user = userDao.selectById(current.getId());
            if (user != null && StringUtils.hasText(user.getName())) {
                return user.getName();
            }
        }
        return current.getUsername();
    }

    /**
     * 一批 PO → VO：<b>附件在这里一次性补齐</b>，不在循环里逐个查。
     *
     * <p>为什么必须批量：告警列表一页 15 条，逐条去查附件就是 15 次往返（N+1）。
     * 这就是 {@code AttachmentService.mapByAlertIds} 存在的理由。
     */
    private List<AlertVO> toVOList(List<AlertPO> alerts) {
        if (alerts.isEmpty()) {
            return List.of();
        }
        Map<Long, List<TaskAttachmentVO>> attachments = attachmentService.mapByAlertIds(
                alerts.stream().map(AlertPO::getId).toList());
        return alerts.stream()
                .map(alert -> toVO(alert, attachments.getOrDefault(alert.getId(), List.of())))
                .toList();
    }

    /** PO → VO：顺手把三个「编码 → 中文名」补上，前端只负责展示 */
    private AlertVO toVO(AlertPO alert, List<TaskAttachmentVO> attachments) {
        AlertVO vo = new AlertVO();
        BeanUtils.copyProperties(alert, vo);
        vo.setAlertCodeLabel(AlertCode.label(alert.getAlertCode()));
        vo.setSeverityLabel(AlertSeverity.label(alert.getSeverity()));
        vo.setStatusLabel(AlertStatusFlow.label(alert.getStatus()));
        // 表里是 tinyint，PO 里是 Integer，VO 里用 Boolean 更适合前端判断
        vo.setPilotActionRequired(alert.getPilotActionRequired() != null
                && alert.getPilotActionRequired() == 1);
        vo.setAttachments(attachments);
        return vo;
    }

    /**
     * 事务提交成功后再推通知 —— 抄的是 {@code TaskServiceImpl.afterCommit} 的做法。
     *
     * <p>为什么非要等提交后：现在还在事务里，万一提交失败（或测试里回滚），
     * 消息却已经推出去了，那就成了「通知别人去处理一条并不存在的告警」。
     * 没有事务时（直接调 Service 的单测）就立刻执行。
     */
    private void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
            return;
        }
        action.run();
    }

    /** message 列是 varchar(500)，超长会插入失败 —— 先在应用层截断 */
    private static String truncate(String text) {
        return text.length() <= 500 ? text : text.substring(0, 497) + "...";
    }
}
