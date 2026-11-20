package cn.tedu.drone.task.ws;

import cn.tedu.drone.task.dao.TaskDao;
import cn.tedu.drone.task.pojo.AlertCode;
import cn.tedu.drone.task.pojo.AlertPO;
import cn.tedu.drone.task.pojo.AlertSeverity;
import cn.tedu.drone.task.pojo.TaskPO;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 告警推送的发送入口。
 *
 * <h2>它要解决的唯一问题</h2>
 * 设计文档里那句话：**通知的目的只是要尽快告诉能处理告警的人**。
 * 在它之前，设备电量掉到 10%、库里躺了一条 `critical` 待处理告警，
 * 但没有任何人会被叫醒 —— 得有人主动打开页面才看得到。
 *
 * <h2>为什么新建一个类，而不是往 {@link TaskNotifier} 里加方法</h2>
 * 两者的**收件人、字段、触发时机完全不同**：
 * <ul>
 *   <li>{@code TaskNotifier} 推的是任务字段（taskNo / pilotName / droneCode），
 *       收件人是「拥有 task:dispatch 的人」</li>
 *   <li>本类推的是告警字段（alertCode / severity / message），
 *       收件人是「拥有 alert:handle 的人」</li>
 * </ul>
 * 混在一起那个类会同时长两套字段逻辑。**真正该复用的是底层通道** ——
 * {@link TaskWsSessionRegistry}（会话登记）与 {@code ObjectMapper}（拼报文），
 * 这两个本类都是直接注入来用的。
 *
 * <h2>三条推送规则（都在设计文档第八章里定过）</h2>
 * <ol>
 *   <li><b>`info` 一定不推。</b>设备上线、起飞这些每天几十上百条，
 *       推了只会让人对通知脱敏 —— 等到真的 {@code critical} 来时，
 *       人已经习惯性忽略了。<b>「不推」是为了让「推」还有意义。</b></li>
 *   <li><b>推给拥有 {@code alert:handle} 权限的人</b>，而不是写死某个账号。
 *       能被通知的人正好就是能处理的人，语义对齐；换人、加人都不用改代码。</li>
 *   <li><b>只在「新产生了一条告警」时推。</b>限频不是靠定时器算的，
 *       而是靠落库时那条 `WHERE NOT EXISTS` —— 同一个问题还有未处理的告警时
 *       根本不会产生新行，调用方也就不会调到这里。见设计文档 4.4 节。</li>
 * </ol>
 *
 * <h2>推送失败不影响业务</h2>
 * 所有发送都返回条数、**不抛异常**：告警必须能落库，
 * 某个管理员不在线只是「他晚几秒知道」，不该让落库失败（和 TaskNotifier 同一条规矩）。
 */
@Slf4j
@Component
public class AlertNotifier {

    /** 消息类型：新产生了一条告警 */
    public static final String TYPE_ALERT_RAISED = "alertRaised";

    /** 能处理告警的人 = 拥有这个权限的人，通知就推给他们 */
    private static final String PERMISSION_ALERT_HANDLE = "alert:handle";

    /**
     * 时间格式与 REST 接口保持一致（VO 上也是这个模式）。
     * 手动格式化而不是交给 Jackson：后者对 LocalDateTime 的默认行为受配置影响，
     * 手动格式化是确定的，前端也不用为推送再写一套解析。
     */
    private static final DateTimeFormatter TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final TaskWsSessionRegistry registry;
    private final ObjectMapper objectMapper;
    private final TaskDao taskDao;

    public AlertNotifier(TaskWsSessionRegistry registry, ObjectMapper objectMapper, TaskDao taskDao) {
        this.registry = registry;
        this.objectMapper = objectMapper;
        this.taskDao = taskDao;
    }

    /**
     * 推送一条新告警。
     *
     * @param alert 刚落库的告警（必须已经有 id）
     * @return 实际送达的连接数；0 表示没人需要知道（info 级）或没人在线（都不是错误）
     */
    public int notifyAlertRaised(AlertPO alert) {
        if (alert == null) {
            return 0;
        }
        if (!AlertSeverity.shouldNotify(alert.getSeverity())) {
            // info 只进库、只进日志，不推。见类注释第 1 条
            log.debug("告警级别为 {} 不需要通知，只记录：device={}，code={}",
                    AlertSeverity.label(alert.getSeverity()), alert.getDeviceCode(), alert.getAlertCode());
            return 0;
        }

        // 只放「够用来提示」的最小信息（和任务通知同一个原则）：
        // 前端收到后可以跳去告警页拿完整数据，那次调用要过登录与权限校验。
        // 所以推送内容就算被截获也不值钱
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("alertId", alert.getId());
        data.put("alertCode", alert.getAlertCode());
        data.put("alertCodeLabel", AlertCode.label(alert.getAlertCode()));
        data.put("severity", alert.getSeverity());
        data.put("severityLabel", AlertSeverity.label(alert.getSeverity()));
        data.put("message", alert.getMessage());
        data.put("deviceCode", alert.getDeviceCode());
        data.put("taskId", alert.getTaskId());
        data.put("actionHint", alert.getActionHint());
        data.put("observedAt", format(alert.getObservedAt()));

        String payload = buildPayload(data);
        if (payload == null) {
            return 0;
        }

        // 按权限找人：能被通知的正好就是能处理的。
        // 这个查询（跨 user/role/permission 五张表）目前住在 TaskDao 里 ——
        // 它是通用的「按权限码找用户」，不是任务专有逻辑，所以这里直接复用，
        // 不在告警模块里再抄一份（两份一样的 SQL 迟早漂移）。
        // 更合适的家是 UserDao；搬家属于重构，等下次动那块代码时一起做。
        //
        // 用 Set 而不是 List：下面还要往里加飞手，而「飞手恰好也有 alert:handle」
        // 是可能的（一个人既派活又管告警）。用 List 会给他推两遍同样的消息
        Set<Long> receivers = new LinkedHashSet<>(taskDao.selectUserIdsByPermission(PERMISSION_ALERT_HANDLE));
        boolean pilotIncluded = includeTaskPilot(receivers, alert);

        int sent = 0;
        for (Long receiverId : receivers) {
            sent += registry.sendToUser(receiverId, payload);
        }

        log.info("告警通知已推送：{}（{}）｜级别={}｜设备={}｜收件人 {} 人（{}）｜送达连接数={}",
                AlertCode.label(alert.getAlertCode()), alert.getAlertCode(),
                AlertSeverity.label(alert.getSeverity()),
                alert.getDeviceCode(), receivers.size(),
                pilotIncluded ? "含该任务飞手" : "仅管理侧", sent);
        return sent;
    }

    /**
     * 把「该任务的飞手」加进收件人 —— <b>但只在 {@code critical} 且告警挂着任务时</b>。
     *
     * <h2>为什么飞手也要收到</h2>
     * 飞手就在现场，是唯一能立刻采取行动的人（返航、降落、中止任务）。
     * 只推给坐在办公室的管理员，等于把最该知道的人漏掉了 ——
     * 这是设计文档第六章矩阵里明确写的一条。
     *
     * <h2>为什么只推 critical</h2>
     * {@code warning} 是「建议处理」，管理侧知道就够了；每条都推给飞手，
     * 只会让他对通知脱敏 —— 等到真的 {@code critical} 来时他已经习惯性忽略。
     * 「不推」是为了让「推」还有意义（同一章里的纪律）。
     *
     * <h2>为什么告警必须挂着任务</h2>
     * 不挂任务的告警（设备自检报的电池高温）不知道该推给哪个飞手 ——
     * 它跟任何一次作业都没关系。找不到就不推，不是错误。
     *
     * @return 是否真的加进去了（用于日志里说清「这次为什么收件人是这些人」）
     */
    private boolean includeTaskPilot(Set<Long> receivers, AlertPO alert) {
        if (!AlertSeverity.CRITICAL.equals(alert.getSeverity()) || alert.getTaskId() == null) {
            return false;
        }
        TaskPO task = taskDao.selectById(alert.getTaskId());
        if (task == null || task.getPilotId() == null) {
            // 任务被删了、或者告警还挂着但任务已经没人 —— 都按「没有飞手可推」处理
            log.debug("告警挂着任务但找不到该任务的飞手，只推管理侧：alertId={}，taskId={}",
                    alert.getId(), alert.getTaskId());
            return false;
        }
        return receivers.add(task.getPilotId());
    }

    /**
     * 拼报文：{@code {"type":"alertRaised","data":{...},"timestamp":"..."}}
     *
     * <p>序列化失败返回 null —— 只可能是代码 bug（比如塞了无法序列化的对象），
     * 记 error 但不往外抛，避免影响告警落库。
     */
    private String buildPayload(Map<String, Object> data) {
        try {
            Map<String, Object> message = new LinkedHashMap<>();
            message.put("type", TYPE_ALERT_RAISED);
            message.put("data", data);
            message.put("timestamp", LocalDateTime.now().toString());
            return objectMapper.writeValueAsString(message);
        } catch (Exception e) {
            log.error("构造告警推送报文失败", e);
            return null;
        }
    }

    private String format(LocalDateTime time) {
        return time == null ? null : time.format(TIME_FORMATTER);
    }
}
