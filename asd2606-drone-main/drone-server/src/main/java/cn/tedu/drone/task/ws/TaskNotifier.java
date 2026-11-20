package cn.tedu.drone.task.ws;

import cn.tedu.drone.task.dao.TaskDao;
import cn.tedu.drone.task.pojo.TaskPO;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 任务通知的发送入口 —— 业务代码只需要调它，不用关心底层走的是 WebSocket 还是别的。
 *
 * <h2>消息里放什么、不放什么</h2>
 * <b>只放「够用来提示」的最小信息</b>：编号、名称、优先级、状态、计划时间、设备编号。
 * 刻意不放任务内容、补充说明、其他飞手的信息 ——
 * 理由见 {@code docs/任务通知.md} 的「顺序、去重与安全」一节：
 * 推送通道是「送信」用的，不是「查数据」用的。前端收到之后仍然会去调接口拿完整数据
 * （那次调用要过登录、过权限校验），这样推送内容就算被人截获也不值钱。
 *
 * <h2>为什么不直接把 TaskVO 整个发出去</h2>
 * 两个原因：一是上面说的最小化；二是 <b>VO 是会变的</b> ——
 * 哪天给 TaskVO 加了内部字段，推送里就莫名其妙多出去了。
 * 推送的报文格式单独定义、单独维护，改它要经过脑子。
 *
 * <h2>推送失败不影响业务</h2>
 * 所有发送都返回条数、不抛异常：管理员派发任务必须成功，
 * 某个飞手收不到推送只是「他晚几秒知道」，不该让派发失败。
 */
@Slf4j
@Component
public class TaskNotifier {

    /** 消息类型：任务已派发给你 */
    public static final String TYPE_TASK_DISPATCHED = "taskDispatched";

    /** 消息类型：飞手开始执行了 */
    public static final String TYPE_TASK_STARTED = "taskStarted";

    /**
     * 消息类型：飞手拒绝执行。
     *
     * <p>前端要靠 {@code type} 把它的样式和别的通知区分开 ——
     * 拒绝是需要管理员马上处理的，不能和「开始执行」长得一样。
     */
    public static final String TYPE_TASK_REFUSED = "taskRefused";

    /**
     * 消息类型：任务收尾（飞手点了「完成任务」或「标记失败」）。
     *
     * <p>两种收尾共用一种消息类型，靠报文里的 {@code status} 与 {@code result} 区分 ——
     * 前端要判断的是「这条任务结束了」，而不是「他怎么结束的」。
     */
    public static final String TYPE_TASK_SETTLED = "taskSettled";

    /** 能重新指派任务的人 = 拥有这个权限的人，通知就推给他们 */
    private static final String PERMISSION_TASK_DISPATCH = "task:dispatch";

    /**
     * 时间格式与 REST 接口保持一致（VO 上也是这个模式）。
     *
     * <p>这里手动格式化成字符串，而不是把 {@code LocalDateTime} 直接塞进 map 交给 Jackson：
     * Jackson 对 LocalDateTime 的默认行为受各种配置影响（可能输出 ISO 串、也可能输出数组），
     * 手动格式化是确定的，前端也不用为推送再写一套解析。
     */
    private static final DateTimeFormatter TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final TaskWsSessionRegistry registry;
    private final ObjectMapper objectMapper;
    private final TaskDao taskDao;

    public TaskNotifier(TaskWsSessionRegistry registry, ObjectMapper objectMapper, TaskDao taskDao) {
        this.registry = registry;
        this.objectMapper = objectMapper;
        this.taskDao = taskDao;
    }

    /**
     * 通知飞手「有任务派给你了」。
     *
     * @param task 派发后的任务（必须已经写入了 pilot_id）
     * @return 实际送达的连接数；0 表示人不在线（不是错误）
     */
    public int notifyTaskDispatched(TaskPO task) {
        if (task == null || task.getPilotId() == null) {
            log.debug("任务没有指派飞手，无需推送，taskId={}", task == null ? null : task.getId());
            return 0;
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("taskId", task.getId());
        data.put("taskNo", task.getTaskNo());
        data.put("taskName", task.getTaskName());
        data.put("priority", task.getPriority());
        data.put("status", task.getStatus());
        data.put("planStartTime", format(task.getPlanStartTime()));
        data.put("planEndTime", format(task.getPlanEndTime()));
        data.put("droneCode", task.getDroneCode());

        String payload = buildPayload(TYPE_TASK_DISPATCHED, data);
        if (payload == null) {
            return 0;
        }

        int sent = registry.sendToUser(task.getPilotId(), payload);
        log.info("派发通知已推送，taskNo={}，飞手 id={}，送达连接数={}",
                task.getTaskNo(), task.getPilotId(), sent);
        return sent;
    }

    /**
     * 通知「能重新指派任务的人」：飞手开始执行了。
     *
     * @return 送达的连接总数（跨多个管理员累加）
     */
    public int notifyTaskStarted(TaskPO task) {
        Map<String, Object> data = baseTaskData(task);
        return notifyDispatchers(TYPE_TASK_STARTED, data,
                "开始执行通知已推送，taskNo={}，送达连接数={}", task);
    }

    /**
     * 通知「能重新指派任务的人」：飞手拒绝了这条任务，需要重新安排。
     *
     * <p>这是所有通知里<b>唯一需要管理员动手</b>的一类，所以报文里额外带上
     * 拒绝原因和飞手姓名 —— 管理员不用点进去就知道下一步该干嘛。
     *
     * <p>{@code task.getStatus()} 这时已经是退回后的 {@code pending}
     * （拒绝的释放逻辑与撤回一致），前端据此可以直接提示「已回到待派发，可重新指派」。
     *
     * @param task      拒绝并释放<b>之后</b>的任务（pilot_name 已被清空）
     * @param pilotName 拒绝的飞手姓名 —— 必须由调用方在释放<b>之前</b>取好传进来，
     *                  因为释放会把任务的 pilot_name 清掉，而通知里恰恰要显示「是谁拒的」
     * @param reason    拒绝原因，可为 null
     */
    public int notifyTaskRefused(TaskPO task, String pilotName, String reason) {
        Map<String, Object> data = baseTaskData(task);
        data.put("pilotName", pilotName);
        data.put("reason", reason);

        return notifyDispatchers(TYPE_TASK_REFUSED, data,
                "拒绝执行通知已推送，taskNo={}，送达连接数={}", task);
    }

    /**
     * 通知「能重新指派任务的人」：任务收尾了（完成或失败）。
     *
     * <p>管理员要知道的是「这条任务结束了，结果是什么」—— 所以报文里带上
     * {@code status}（finished / failed）、{@code result}（success / partial / failed）
     * 和 {@code actualEndTime}。
     *
     * <p><b>不发报告正文</b>：报告可能很长，而推送只负责「快」；
     * 前端收到后自己去调报告接口取完整内容（那次调用要过登录与权限校验）。
     * 这是项目里所有通知共用的原则（见本类注释与 {@code docs/任务通知.md}）。
     *
     * @param task   收尾后的任务（状态已经是 finished / failed）
     * @param result 报告的结论；没写报告时为 null
     */
    public int notifyTaskSettled(TaskPO task, String result) {
        Map<String, Object> data = baseTaskData(task);
        data.put("result", result);
        data.put("actualEndTime", format(task.getActualEndTime()));

        // 状态直接拼进模板里，而不是再加一个 {} ——
        // notifyDispatchers 只往日志里传「任务编号 + 送达连接数」两个参数，
        // 多写一个占位符会让日志里留一个没人替换的 {}（这类错之前踩过）
        return notifyDispatchers(TYPE_TASK_SETTLED, data,
                "任务收尾通知已推送，taskNo={}，状态=" + task.getStatus() + "，送达连接数={}",
                task);
    }

    /**
     * 把通知推给所有「有能力处理这件事的人」—— 即拥有 {@code task:dispatch} 权限的人。
     *
     * <h2>为什么按权限查，而不是写死某个管理员</h2>
     * 因为能被通知的人，正好就是能把任务重新指派出去的人，语义完全对齐。
     * 写死账号的话，人员一变动就要改代码；按权限查则天然跟着权限配置走。
     *
     * <p>注意这是一次「数据库查询 + N 个连接推送」，和飞手那条（直接按 id 推）不一样。
     */
    private int notifyDispatchers(String type, Map<String, Object> data,
                                  String logTemplate, TaskPO task) {
        String payload = buildPayload(type, data);
        if (payload == null) {
            return 0;
        }

        List<Long> receiverIds = taskDao.selectUserIdsByPermission(PERMISSION_TASK_DISPATCH);
        int sent = 0;
        for (Long receiverId : receiverIds) {
            sent += registry.sendToUser(receiverId, payload);
        }

        log.info(logTemplate, task.getTaskNo(), sent);
        return sent;
    }

    /** 各类任务通知都会带上的基础字段，避免每个方法抄一遍 */
    private Map<String, Object> baseTaskData(TaskPO task) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("taskId", task.getId());
        data.put("taskNo", task.getTaskNo());
        data.put("taskName", task.getTaskName());
        data.put("priority", task.getPriority());
        data.put("status", task.getStatus());
        data.put("pilotName", task.getPilotName());
        data.put("droneCode", task.getDroneCode());
        data.put("planStartTime", format(task.getPlanStartTime()));
        data.put("planEndTime", format(task.getPlanEndTime()));
        return data;
    }

    /**
     * 拼报文：{@code {"type":"...", "data":{...}, "timestamp":"..."}}
     *
     * <p>{@code type} 是给前端做分支用的（将来还会有别的通知类型，比如撤回、超时释放），
     * 前端按 type 决定怎么处理，不用去猜内容。
     *
     * <p>序列化失败返回 null —— 只可能是代码 bug（比如塞了无法序列化的对象），
     * 记 error 但不往外抛，避免影响业务。
     */
    private String buildPayload(String type, Map<String, Object> data) {
        try {
            Map<String, Object> message = new LinkedHashMap<>();
            message.put("type", type);
            message.put("data", data);
            message.put("timestamp", LocalDateTime.now().toString());
            return objectMapper.writeValueAsString(message);
        } catch (Exception e) {
            log.error("构造 WebSocket 推送报文失败，type={}", type, e);
            return null;
        }
    }

    /** 时间统一按「yyyy-MM-dd HH:mm:ss」发，和 REST 接口的格式保持一致，前端不用两套解析 */
    private String format(LocalDateTime time) {
        return time == null ? null : time.format(TIME_FORMATTER);
    }
}
