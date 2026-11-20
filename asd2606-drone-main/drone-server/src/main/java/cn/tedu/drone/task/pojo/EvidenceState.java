package cn.tedu.drone.task.pojo;

import java.util.List;

/**
 * 「执行依据」的取值 —— 对应 {@code task.evidence_state} 列。
 *
 * <h2>它和 task.status 是<b>两个互不相干的维度</b></h2>
 * 这是整个证据设计里最需要先想清楚的一点，也是 {@code docs/任务业务设计.md}
 * 第十章花了一整章解释的事：
 * <ul>
 *   <li>{@code status} 回答「业务走到哪一步了」—— 谁点的按钮，系统记的账</li>
 *   <li>{@code evidence_state} 回答「这一步有没有客观依据」—— 数据说了算</li>
 * </ul>
 * 组合起来才是完整的实情。「执行中 + 仅飞手声明」是一个<b>合法且有意义的组合</b>：
 * 它准确表达了「飞手说他开始飞了，但我们还没有任何设备数据能佐证」。
 * 正因为是两个维度，加证据这件事<b>没动状态机一个箭头</b> ——
 * 不用为「有证据的执行中」新增一个状态，也不用去改那些流转校验。
 *
 * <h2>为什么用常量类而不是 enum</h2>
 * 与 {@link TaskStatusFlow} 保持一致：状态值在数据库里是 varchar，
 * MyBatis 直接按字符串往返，中间转一层 enum 需要额外的 TypeHandler，
 * 收益不抵成本。词汇表的纪律靠「集中在一个类里写清楚」来维持。
 */
public final class EvidenceState {

    private EvidenceState() {
    }

    /** 无依据：任务还没开工，谈不上依据 */
    public static final String NONE = "none";

    /**
     * 仅有声明：飞手点了「开始执行」，系统记下了，但<b>没有任何外部数据佐证</b>。
     *
     * <p>这不是「假」，只是「暂时无法验证」。声明制的意义在于
     * 飞手主动承担了「我说我在飞」这句话的责任，事后对不上账是要被问的。
     */
    public static final String DECLARED = "declared";

    /** 已证实：收到了该设备的客观数据（例如带时间戳的遥测），它佐证了这次执行 */
    public static final String CONFIRMED = "confirmed";

    /**
     * 声明已过期：声明了要执行，但过了阈值仍拿不到任何旁证。
     *
     * <p><b>它只描述依据，不描述事实</b> —— 标 stale 之后任务状态依然可以是
     * {@code executing}，我们没有替飞手判断「你到底飞没飞」。
     * 系统只负责把「这里对不上」摆出来给人看，判定的权力在人手里。
     */
    public static final String STALE = "stale";

    /** 全部取值，供校验与文档使用 */
    public static final List<String> ALL = List.of(NONE, DECLARED, CONFIRMED, STALE);

    /** 中文名，用于日志与将来的展示 */
    public static String label(String state) {
        if (state == null) {
            return "无依据";
        }
        return switch (state) {
            case DECLARED -> "仅声明";
            case CONFIRMED -> "已证实";
            case STALE -> "声明已过期";
            default -> "无依据";
        };
    }
}
