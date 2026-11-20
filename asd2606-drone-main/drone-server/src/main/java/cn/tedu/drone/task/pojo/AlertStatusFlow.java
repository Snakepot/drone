package cn.tedu.drone.task.pojo;

import cn.tedu.drone.common.BusinessException;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 告警处理的状态机 —— 对应 {@code task_alert.status} 列。
 *
 * <h2>四个状态、三条边</h2>
 * <pre>
 * pending 待处理 ──认领──> handling 处理中 ──处理/误报──> resolved 已处理 / ignored 误报
 *       └──────────────判定为误报────────────────────────> ignored 误报
 * </pre>
 *
 * <h2>为什么「已处理」必须从「处理中」来</h2>
 * 认领的含义是「**我来负责闭环这件事**」。跳过认领直接关掉，
 * 等于没人对这条告警负过责 —— 而且两人同时看一条告警时，
 * 没有认领这一步就分不清谁在处理（见设计文档第七章）。
 * 多一次点击换来「谁认的、谁关的」清清楚楚，这笔账划算。
 *
 * <h2>为什么「误报」要单独成一个终态</h2>
 * 不给误报一个正经的归宿，人就会用「已处理」来关掉它 —— 于是数据里
 * 再也分不清「真处理了」和「其实没事」。而这两者对**调阈值**的价值完全不同：
 * 误报多说明阈值定得太紧，那是要改配置的信号。
 * 所以 {@link #ACTION_IGNORE} **必须写说明**，和「已处理」一样。
 *
 * <h2>为什么不复用 {@link TaskStatusFlow} 的写法（Map&lt;String, String&gt;）</h2>
 * 任务是「一个动作只有一个来源状态」，用 {@code Map<动作, 来源>} 刚好。
 * 告警的「误报」有<b>两个</b>来源（待处理、处理中），
 * 塞进单值 Map 就得像任务里的「取消」那样再开一个特殊列表 —— 不如直接
 * 用 {@code Map<动作, Set<来源>>} 表达得更直白。
 */
public final class AlertStatusFlow {

    private AlertStatusFlow() {
    }

    // ------------------------------------------------------------------
    // 状态
    // ------------------------------------------------------------------

    /** 待处理：刚产生，还没人认领 */
    public static final String PENDING = "pending";

    /** 处理中：有人认领了 */
    public static final String HANDLING = "handling";

    /** 已处理：处理完了，且写了处置说明 */
    public static final String RESOLVED = "resolved";

    /** 误报：判定为误报，也写了原因 */
    public static final String IGNORED = "ignored";

    /** 全部状态 */
    public static final List<String> ALL = List.of(PENDING, HANDLING, RESOLVED, IGNORED);

    /** 终态：到了这里就没有下一步了 */
    public static final List<String> TERMINAL = List.of(RESOLVED, IGNORED);

    // ------------------------------------------------------------------
    // 动作
    // ------------------------------------------------------------------

    /** 认领：我来处理 */
    public static final String ACTION_CLAIM = "claim";

    /** 处理完成 */
    public static final String ACTION_RESOLVE = "resolve";

    /** 判定为误报 */
    public static final String ACTION_IGNORE = "ignore";

    /** 全部动作 */
    public static final List<String> ALL_ACTIONS = List.of(ACTION_CLAIM, ACTION_RESOLVE, ACTION_IGNORE);

    /**
     * 每个动作允许从哪些状态出发。
     *
     * <p>这张表就是「什么能做、什么不能做」的唯一定义处 ——
     * 校验时查表，而不是在各处写 {@code if}。散落的 if 早晚会漏判，
     * 比如「已经误报的告警又被处理了一次」。
     */
    private static final Map<String, Set<String>> ALLOWED_FROM = Map.of(
            ACTION_CLAIM, Set.of(PENDING),
            ACTION_RESOLVE, Set.of(HANDLING),
            ACTION_IGNORE, Set.of(PENDING, HANDLING));

    /** 动作完成后落到哪个状态 */
    private static final Map<String, String> TARGET_STATUS = Map.of(
            ACTION_CLAIM, HANDLING,
            ACTION_RESOLVE, RESOLVED,
            ACTION_IGNORE, IGNORED);

    private static final Map<String, String> STATUS_LABELS = Map.of(
            PENDING, "待处理",
            HANDLING, "处理中",
            RESOLVED, "已处理",
            IGNORED, "误报");

    private static final Map<String, String> ACTION_LABELS = Map.of(
            ACTION_CLAIM, "认领",
            ACTION_RESOLVE, "标记为已处理",
            ACTION_IGNORE, "标记为误报");

    // ------------------------------------------------------------------

    public static String label(String status) {
        return status == null ? null : STATUS_LABELS.getOrDefault(status, status);
    }

    public static String actionLabel(String action) {
        return action == null ? null : ACTION_LABELS.getOrDefault(action, action);
    }

    /** 这个动作做完之后，状态应该变成什么 */
    public static String targetStatus(String action) {
        String target = TARGET_STATUS.get(action);
        if (target == null) {
            throw new BusinessException("未知的处理动作：" + action);
        }
        return target;
    }

    /** 这个动作能不能在指定状态下做 */
    public static boolean canTransfer(String action, String currentStatus) {
        Set<String> allowed = ALLOWED_FROM.get(action);
        return allowed != null && allowed.contains(currentStatus);
    }

    /**
     * 校验动作合法性，不合法就抛错。
     *
     * <p>报错文案要把「当前状态」和「这个动作要求的状态」都说出来 ——
     * 只报「不允许」的话，用户看不出为什么不允许，也不知道该等到什么状态。
     */
    public static void assertCanTransfer(String action, String currentStatus) {
        if (!ALL_ACTIONS.contains(action)) {
            throw new BusinessException("未知的处理动作：" + action);
        }
        if (canTransfer(action, currentStatus)) {
            return;
        }
        Set<String> allowed = ALLOWED_FROM.get(action);
        StringBuilder expected = new StringBuilder();
        for (String status : ALL) {
            if (allowed.contains(status)) {
                if (expected.length() > 0) {
                    expected.append(" 或 ");
                }
                expected.append(label(status));
            }
        }
        throw new BusinessException("只有「" + expected + "」的告警才能"
                + actionLabel(action) + "，当前状态：" + label(currentStatus));
    }
}
