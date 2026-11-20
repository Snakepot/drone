package cn.tedu.drone.task.pojo;

/**
 * 「依据的种类」的取值 —— 对应 {@code task_evidence.type} 列。
 *
 * <h2>为什么要区分种类，而不是都叫「证据」</h2>
 * 因为不同种类的证据<b>可信度不一样</b>，混在一起就分不出轻重了：
 * <ul>
 *   <li>设备遥测（{@link #TELEMETRY}）：飞控/云侧直出，最客观，也最难伪造</li>
 *   <li>飞行日志（{@link #FLIGHT_LOG}）：事后上传的文件，客观但可以被挑选（只传好看的那份）</li>
 *   <li>第二人确认（{@link #WITNESS}）：有人看着，可信度取决于那个人</li>
 *   <li>自行声明（{@link #MANUAL}）：就是飞手自己说的，等于没有外部依据</li>
 * </ul>
 * 所以 {@code task.evidence_type} 存的是<b>目前最强的那一条</b>。
 * 这轮只实现设备遥测这一种；其余三种是预留的位置 ——
 * 枚举值先定义好，是为了让「加一种证据」变成填一个值，而不是重新设计表。
 *
 * <h2>和 evidence_state 的关系</h2>
 * {@code state} 说「有没有」，{@code type} 说「凭的是什么」。
 * 比如「已证实 + telemetry」＝有设备数据撑腰；
 * 「已证实 + manual」在业务上等于自说自话，把 state 抬到 confirmed 是抬错了 ——
 * 这正是 {@link #MANUAL} 要单列出来的原因。
 */
public final class EvidenceType {

    private EvidenceType() {
    }

    /** 设备实时遥测（本轮实现的唯一一种） */
    public static final String TELEMETRY = "telemetry";

    /** 事后上传的飞行日志文件，指向 task_attachment */
    public static final String FLIGHT_LOG = "flight_log";

    /** 第二人（比如现场配合人员）确认 */
    public static final String WITNESS = "witness";

    /** 飞手自行声明 —— 等于「仅声明」，不能用来把证据抬到 confirmed */
    public static final String MANUAL = "manual";

    /**
     * 证据来源。与 {@code task_evidence.source} 对应，
     * 用来回答「这条证据是谁送来的」——事后追责时这是第一个要看的问题。
     */
    public static final String SOURCE_DEVICE = "device";
    public static final String SOURCE_APP = "app";
    public static final String SOURCE_OPERATOR = "operator";
}
