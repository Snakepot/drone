package cn.tedu.drone.task.dispatch;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 待派发任务的归因取值 —— 对应 {@code task_dispatch_block.reason} 列。
 *
 * <h2>它回答的是管理层最常问的那句话</h2>
 * 「活攒着没派出去，到底是**没人、没设备，还是没人管**？」
 *
 * <p>这四个取值不是「状态」，而是**那一刻的事实**：定时任务给待派发任务打点，
 * 看当时的资源池得出的结论。所以事后永远算不出来 —— 事后查只能看到现在的资源池
 * （见 {@code docs/业务分析方案.md} 2.7）。
 *
 * <h2>为什么 {@link #IDLE} 是这里面最值钱的一个</h2>
 * 另外三个都指向「要花钱」（招人、买设备、修设备），只有它是
 * 「资源够，但没人点派发」—— 它指向**管理动作没跟上**，
 * 是唯一一条不花钱就能改善的，也是最容易被忽略的（因为表面上看起来
 * 「资源紧张」和「没人管」在列表里长得一模一样：都是待派发）。
 */
public final class DispatchBlockReason {

    private DispatchBlockReason() {
    }

    /** 没可派的飞手（没有飞手角色的人，或者都跟这条任务的时间窗冲突了） */
    public static final String BLOCKED_PILOT = "blocked_pilot";

    /** 没待命的设备（都派出去了 / 在维护 / 跟时间窗冲突） */
    public static final String BLOCKED_DRONE = "blocked_drone";

    /** 两者都没有 —— 资源整体不足（这时候该看「是不是任务排太满」） */
    public static final String BLOCKED_BOTH = "blocked_both";

    /** **资源是够的，但没人点派发** —— 管理动作没跟上 */
    public static final String IDLE = "idle";

    /** 全部取值，按「从坏到好」的顺序（界面上也按这个顺序排） */
    public static final List<String> ALL =
            List.of(BLOCKED_BOTH, BLOCKED_DRONE, BLOCKED_PILOT, IDLE);

    private static final Map<String, String> LABELS = new LinkedHashMap<>();

    static {
        LABELS.put(BLOCKED_BOTH, "缺人又缺设备");
        LABELS.put(BLOCKED_DRONE, "缺设备");
        LABELS.put(BLOCKED_PILOT, "缺飞手");
        LABELS.put(IDLE, "资源够，没人派");
    }

    /** 中文名；取值非法时原样返回（用于报错与界面兜底） */
    public static String label(String reason) {
        return LABELS.getOrDefault(reason, reason);
    }

    /** 按当时的候选数判定归因。见 2.7 的判定树 */
    public static String of(int pilotCandidates, int droneCandidates) {
        if (pilotCandidates <= 0 && droneCandidates <= 0) {
            return BLOCKED_BOTH;
        }
        if (pilotCandidates <= 0) {
            return BLOCKED_PILOT;
        }
        if (droneCandidates <= 0) {
            return BLOCKED_DRONE;
        }
        return IDLE;
    }
}
