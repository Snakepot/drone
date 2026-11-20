package cn.tedu.drone.task.pojo;

import cn.tedu.drone.common.BusinessException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 任务状态机 —— 状态的取值、中文名、以及「哪些流转是合法的」，全部集中在这里。
 *
 * <h2>为什么单独抽一个类</h2>
 * 状态流转的校验最容易写坏的方式是散落一堆 {@code if}：
 * <pre>
 * if (status.equals("pending")) { ... }
 * else if (status.equals("dispatched")) { ... }
 * </pre>
 * 这样每加一个动作就要在好几处补判断，漏一处就出现「待派发能被直接标记完成」这类漏洞。
 *
 * <p>这里把合法流转写成一张 <b>「动作 → 允许的起始状态」的表</b>，
 * 校验时查表判断。好处有三个：
 * <ul>
 *   <li>合法路径一眼看得全，就是下面那几行，不用去代码里翻</li>
 *   <li>加新动作只改这张表，校验逻辑一行都不动</li>
 *   <li>非法流转的报错文案统一，不会一处说「状态不对」另一处说「操作不允许」</li>
 * </ul>
 *
 * <p>与 {@code docs/任务业务设计.md} 里的状态机图严格对应，改这里也要改文档。
 *
 * <pre>
 *   pending --> dispatched   派发
 *   dispatched --> pending   撤回派发（人撤的）
 *   dispatched --> pending   超时释放（系统干的，计划时间过了还没开工）
 *   dispatched --> pending   拒绝执行（飞手不接这活，管理员可重新指派）
 *   dispatched --> executing 开始执行
 *   executing --> finished   完成
 *   executing --> failed     失败
 *   其余 --> cancelled       取消
 * </pre>
 *
 * <p>注意有三条箭头都是 {@code dispatched → pending}，但它们是<b>三个不同的动作</b>：
 * 撤回派发（管理员反悔）、超时释放（系统兜底）、拒绝执行（飞手不接）。
 * 结果一样、原因完全不同，流水里必须区分 —— 否则看时间轴根本不知道是谁、为什么把活退了回来。
 */
public final class TaskStatusFlow {

    // ------------------------------------------------------------------
    // 状态取值（与 task.status 字段、前端字典三者必须一致）
    // ------------------------------------------------------------------

    /** 待派发：任务已创建，还没安排人 */
    public static final String PENDING = "pending";

    /** 已派发：已指派飞手与设备，等飞手开始 */
    public static final String DISPATCHED = "dispatched";

    /** 执行中：飞手已开始 */
    public static final String EXECUTING = "executing";

    /** 已完成：执行结束且报告已提交（终态） */
    public static final String FINISHED = "finished";

    /** 已失败：执行过程中出问题，未能完成（终态） */
    public static final String FAILED = "failed";

    /** 已取消：人为终止（终态） */
    public static final String CANCELLED = "cancelled";

    /** 全部状态，按流转顺序排列 */
    public static final List<String> ALL =
            List.of(PENDING, DISPATCHED, EXECUTING, FINISHED, FAILED, CANCELLED);

    // ------------------------------------------------------------------
    // 动作
    // ------------------------------------------------------------------

    public static final String ACTION_CREATE = "create";
    public static final String ACTION_DISPATCH = "dispatch";
    public static final String ACTION_WITHDRAW = "withdraw";
    /** 超时释放：派发出去后计划时间过了还没开工，系统自动把资源收回来 */
    public static final String ACTION_EXPIRE = "expire";
    /** 拒绝执行：飞手不接这活。效果与撤回一样（回待派发），但原因不同，必须分开记 */
    public static final String ACTION_REFUSE = "refuse";
    public static final String ACTION_START = "start";
    public static final String ACTION_FINISH = "finish";
    public static final String ACTION_FAIL = "fail";
    public static final String ACTION_CANCEL = "cancel";

    /**
     * 状态的中文名。
     *
     * <p>放在服务端是为了报错文案能说人话 ——
     * 「只有「待派发」的任务才能派发」比「当前状态 pending 不允许 dispatch」有用得多。
     * 用 LinkedHashMap 保证顺序与 {@link #ALL} 一致，调试时打印出来的顺序是稳定的。
     */
    private static final Map<String, String> STATUS_LABELS = new LinkedHashMap<>();

    /** 动作的中文名，同样为报错文案服务 */
    private static final Map<String, String> ACTION_LABELS = new LinkedHashMap<>();

    /**
     * 合法流转表：动作 → 允许的<b>起始</b>状态。
     *
     * <p>只登记一步流转 —— 目标状态由动作本身决定，不需要单独维护，
     * 少一个字段就少一处能写歪的地方。
     *
     * <p>计划中但还没实现的动作（start / finish / fail / cancel）也一并登记，
     * 这样下一步做执行与取消时不用回头改这里；如果传了没登记的动作，
     * 会明确报「未知的流转动作」，而不是悄悄放过去。
     */
    private static final Map<String, String> ALLOWED_FROM = new LinkedHashMap<>();

    static {
        STATUS_LABELS.put(PENDING, "待派发");
        STATUS_LABELS.put(DISPATCHED, "已派发");
        STATUS_LABELS.put(EXECUTING, "执行中");
        STATUS_LABELS.put(FINISHED, "已完成");
        STATUS_LABELS.put(FAILED, "已失败");
        STATUS_LABELS.put(CANCELLED, "已取消");

        ACTION_LABELS.put(ACTION_CREATE, "创建");
        ACTION_LABELS.put(ACTION_DISPATCH, "派发");
        ACTION_LABELS.put(ACTION_WITHDRAW, "撤回派发");
        ACTION_LABELS.put(ACTION_EXPIRE, "超时释放");
        ACTION_LABELS.put(ACTION_REFUSE, "拒绝执行");
        ACTION_LABELS.put(ACTION_START, "开始执行");
        ACTION_LABELS.put(ACTION_FINISH, "完成");
        ACTION_LABELS.put(ACTION_FAIL, "标记失败");
        ACTION_LABELS.put(ACTION_CANCEL, "取消");

        ALLOWED_FROM.put(ACTION_DISPATCH, PENDING);
        ALLOWED_FROM.put(ACTION_WITHDRAW, DISPATCHED);
        ALLOWED_FROM.put(ACTION_EXPIRE, DISPATCHED);
        ALLOWED_FROM.put(ACTION_REFUSE, DISPATCHED);
        ALLOWED_FROM.put(ACTION_START, DISPATCHED);
        ALLOWED_FROM.put(ACTION_FINISH, EXECUTING);
        ALLOWED_FROM.put(ACTION_FAIL, EXECUTING);
        // 取消比较特殊：三种非终态都能取消，所以起始状态不是一个值
        // （见 canTransfer 里对 ACTION_CANCEL 的单独处理）
    }

    /** 允许被取消的状态：还没到终态的都行 */
    private static final List<String> CANCELLABLE = List.of(PENDING, DISPATCHED, EXECUTING);

    private TaskStatusFlow() {
    }

    /** 状态的中文名；遇到没登记的值原样返回，免得报错时报出一个空白 */
    public static String label(String status) {
        return STATUS_LABELS.getOrDefault(status, status);
    }

    /** 动作的中文名 */
    public static String actionLabel(String action) {
        return ACTION_LABELS.getOrDefault(action, action);
    }

    /**
     * 判断某个动作能不能从当前状态出发。
     *
     * <p>先查表再判断，而不是把规则写成 {@code if} 链 —— 见类注释。
     */
    public static boolean canTransfer(String action, String currentStatus) {
        if (ACTION_CANCEL.equals(action)) {
            return CANCELLABLE.contains(currentStatus);
        }
        String allowedFrom = ALLOWED_FROM.get(action);
        return allowedFrom != null && allowedFrom.equals(currentStatus);
    }

    /**
     * 校验流转是否合法，不合法就抛出可直接展示给用户的提示。
     *
     * <p>这是所有流转接口的第一道关：只要有一处忘了调它，
     * 就会出现「已完成的任务又被派发一次」这种情况，所以每个流转方法开头都要过一遍。
     *
     * @param action        动作，见本类的 ACTION_* 常量
     * @param currentStatus 任务当前状态
     * @throws BusinessException 动作没登记，或当前状态不允许该动作
     */
    public static void assertCanTransfer(String action, String currentStatus) {
        if (!ACTION_LABELS.containsKey(action)) {
            throw new BusinessException("未知的流转动作：" + action);
        }
        if (canTransfer(action, currentStatus)) {
            return;
        }

        String allowedFrom = ALLOWED_FROM.get(action);
        if (allowedFrom != null) {
            throw new BusinessException("只有「" + label(allowedFrom) + "」的任务才能"
                    + actionLabel(action) + "，当前状态：" + label(currentStatus));
        }

        // 走到这里只剩「取消」一种可能：它的合法起始状态有多个，
        // 所以能拒绝的一定是终态任务，直接说清楚
        throw new BusinessException("「" + label(currentStatus) + "」的任务不能"
                + actionLabel(action) + "，任务已经结束了");
    }
}
