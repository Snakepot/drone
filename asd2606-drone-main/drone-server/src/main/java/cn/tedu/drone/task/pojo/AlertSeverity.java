package cn.tedu.drone.task.pojo;

/**
 * 告警的严重级别 —— 对应 {@code task_alert.severity} 列。
 *
 * <h2>只有三档，而且刻意不复用任务的 P0~P3</h2>
 * <ul>
 *   <li>任务的 {@code P0~P3} 回答「<b>这个活有多重要</b>」—— 是<b>计划</b>属性，
 *       创建时定好、一般不改</li>
 *   <li>告警的级别回答「<b>这件事有多急</b>」—— 是<b>实时</b>属性，随现场情况变</li>
 * </ul>
 * 两者会打架：一个 P3 的低价值任务，飞机在天上电机故障了，
 * 那个告警是最高级别的，跟任务的 P3 没有任何关系。用同一套枚举，
 * 早晚会出现「这条 P3 的告警到底急不急」这种没人答得上来的问题。
 *
 * <h2>为什么只留三档</h2>
 * 档位越多，判定分歧越多。五档制里「warning 和 major 的界限」会变成
 * 每次评审都要吵一遍的问题。三档足够驱动<b>行为差异</b>
 * （不推 / 推 / 推且高亮），而驱动不出行为差异的分档就是装饰。
 *
 * <p>与 {@link EvidenceState}、{@link TaskStatusFlow} 一样，
 * 用常量类而不是 enum：值在数据库里是 varchar，MyBatis 直接按字符串往返，
 * 中间转一层 enum 要额外的 TypeHandler，收益不抵成本。词汇表的纪律靠
 * 「集中在一个类里写清楚」来维持。
 */
public final class AlertSeverity {

    private AlertSeverity() {
    }

    /** 只是记录：无人需要行动，**不推通知** */
    public static final String INFO = "info";

    /** 需要有人看一眼：建议处理，不强制 */
    public static final String WARNING = "warning";

    /** 需要有人马上处理：可能要中止任务 */
    public static final String CRITICAL = "critical";

    /** 中文名，用于日志与前端展示 */
    public static String label(String severity) {
        if (severity == null) {
            return "未知";
        }
        return switch (severity) {
            case INFO -> "提示";
            case WARNING -> "警告";
            case CRITICAL -> "严重";
            default -> severity;
        };
    }

    /**
     * 判断某个字符串是不是合法的级别。
     *
     * <p>用途：写库前兜一道。级别是 varchar，写进去一个拼错的词不会报错，
     * 但那条告警从此在「按级别筛选」里查不到 —— 属于最难发现的那类脏数据。
     */
    public static boolean isValid(String severity) {
        return INFO.equals(severity) || WARNING.equals(severity) || CRITICAL.equals(severity);
    }

    /**
     * 该不该推通知。
     *
     * <p><b>`info` 一定不推</b>：{@code device.online}、{@code evt.takeoff}
     * 这些每天几十上百条，推了只会让人对通知脱敏 —— 等到真的 {@code critical} 来时，
     * 人已经习惯性忽略了。<b>「不推」是为了让「推」还有意义</b>，
     * 这是通知设计里最重要的一条纪律。
     */
    public static boolean shouldNotify(String severity) {
        return WARNING.equals(severity) || CRITICAL.equals(severity);
    }
}
