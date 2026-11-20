package cn.tedu.drone.task.pojo;

import java.util.List;
import java.util.Map;

/**
 * 告警编码的<b>词汇表</b> —— 对应 {@code task_alert.alert_code} 列。
 *
 * <h2>29 条，分四类</h2>
 * 清单的权威定义在 {@code docs/告警体系设计.md} 第二章（含每条的触发条件与级别）。
 * 这里只是把编码固化下来，方便代码引用与校验 —— <b>改清单必须先改文档，再改这里</b>。
 *
 * <ul>
 *   <li>遥测类 16 条 —— 来自 {@code telemetry} 报文，<b>判据是我们自己算的</b>
 *       （含两条「空间违规」：飞进禁飞区、在限高区内超高 —— 2026-09-28 补）</li>
 *   <li>状态类 3 条 —— 来自 {@code status} 报文</li>
 *   <li>事件类 6 条 —— 来自 {@code event/{类型}} 报文，<b>是设备判定的</b></li>
 *   <li>人工上报类 4 条 —— 走 HTTP 接口（飞手端提交）</li>
 * </ul>
 *
 * <h2>「阈值识别」和「事件」的本质区别（这一条最值得记住）</h2>
 * 遥测类与事件类看着都是「设备出了问题」，但来源完全不同：
 * <ul>
 *   <li><b>遥测类</b>：设备只是每秒报一串数（电量 87、高度 120……），
 *       「低于 15% 要报警」这个判断<b>是我们做的</b> —— 阈值改了，判定就变了</li>
 *   <li><b>事件类</b>：设备主动说「我要返航了」，我们只是接收方</li>
 * </ul>
 * 所以要调告警的松紧，遥测类调阈值即可，事件类<b>只能改设备侧</b>。
 *
 * <h2>级别：为什么这里存的是「默认级别」</h2>
 * 29 条里有 28 条的级别是固定的，写在本类里；只有 {@link #DEVICE_OFFLINE} 例外 ——
 * 它平时是 {@code warning}（可能只是关机了），但<b>设备身上有「执行中」的任务时
 * 要升成 {@code critical}</b>（飞机失联了）。这种「随上下文变」的级别
 * 只能是识别时算出来的，所以本类给出的 {@link #defaultSeverity(String)}
 * 是它的<b>基准级别</b>。
 */
public final class AlertCode {

    private AlertCode() {
    }

    // ------------------------------------------------------------------
    // 遥测类：判据由我们算（16 条）
    // ------------------------------------------------------------------

    public static final String BAT_LOW = "bat.low";
    public static final String BAT_CRITICAL = "bat.critical";
    public static final String BAT_TEMP_HIGH = "bat.temp.high";
    public static final String BAT_TEMP_CRITICAL = "bat.temp.critical";
    public static final String GPS_WEAK = "gps.weak";
    public static final String GPS_LOST = "gps.lost";
    public static final String RC_SIGNAL_WEAK = "rc.signal.weak";
    public static final String RC_SIGNAL_LOST = "rc.signal.lost";
    public static final String ALT_OVER = "alt.over";
    public static final String ALT_LOW = "alt.low";
    public static final String SPEED_OVER = "speed.over";
    public static final String ROUTE_DEVIATION = "route.deviation";
    public static final String STORAGE_LOW = "storage.low";
    public static final String MOTOR_FAULT = "motor.fault";

    // ------------------------------------------------------------------
    // 空间类：从遥测里的位置算出来的（2 条，2026-09-28 补）
    // ------------------------------------------------------------------

    /**
     * 飞进了禁飞区 —— <b>安全底线类</b>，critical。
     *
     * <h2>为什么它和 {@link #ALT_OVER} 是一类，而不是新开一类</h2>
     * 两者都是「**从遥测里的位置信息算出来的空间违规**」：
     * 设备只是报「我在哪、多高」，而「这里不能飞」是**我们拿着禁飞区数据判的**。
     * 这和遥测类里「电量低于 15% 算告警」是同一种分工 ——
     * 判据在我们这边，不在设备那边。所以归入遥测类，不另起分类。
     *
     * <h2>它和「事前拦下」是两件事</h2>
     * 创建/派发时的静默校验只能拦住「计划就飞错地方」；
     * 这条管的是**已经起飞之后真的飞进去了**。差别见
     * {@code docs/地图接入方案.md} 6.3.3 —— 那里写了完整的运行时检测方案。
     *
     * <h2>暂时没有生产者</h2>
     * 禁飞区那两步（建表、画区、运行时检测）还没做，所以这条编码**现在不会有告警产生**
     * —— 和 {@code device.online} 那批「通报类」一样，是**先把词汇表定下来**。
     * 这么做是为了不让「文档写了 29 条、代码里只有 27 条」这种漂移发生
     * （词汇表测试会盯着这个数字）。
     */
    public static final String ZONE_BREACH = "zone.breach";

    /**
     * 在限高区内超高（比如禁飞区标了限高 120 米，飞机在区内飞到了 300 米）。
     *
     * <p>比 {@link #ZONE_BREACH} 低一档：它**通常在区内**，只是高度超了；
     * 而 breach 是「根本不该在这儿」。
     */
    public static final String ZONE_ALT_OVER = "zone.alt.over";

    // ------------------------------------------------------------------
    // 状态类（3 条）
    // ------------------------------------------------------------------

    public static final String DEVICE_ONLINE = "device.online";
    public static final String DEVICE_OFFLINE = "device.offline";
    public static final String FLIGHT_STATE_CHANGED = "flight.state.changed";

    // ------------------------------------------------------------------
    // 事件类：设备自己判定的（6 条）
    // ------------------------------------------------------------------

    public static final String EVT_TAKEOFF = "evt.takeoff";
    public static final String EVT_LAND = "evt.land";
    public static final String EVT_RTH = "evt.rth";
    public static final String EVT_BATTERY_LOW = "evt.battery.low";
    public static final String EVT_MISSION_INTERRUPT = "evt.mission.interrupt";
    public static final String EVT_EMERGENCY_STOP = "evt.emergency.stop";

    // ------------------------------------------------------------------
    // 人工上报类（4 条）
    // ------------------------------------------------------------------

    public static final String MAN_ONSITE_ISSUE = "man.onsite.issue";
    public static final String MAN_DEVICE_DAMAGE = "man.device.damage";
    public static final String MAN_WEATHER = "man.weather";
    public static final String MAN_CUSTOMER_ISSUE = "man.customer.issue";

    /** 全部编码，顺序与文档第二章的四张表一致 */
    public static final List<String> ALL = List.of(
            BAT_LOW, BAT_CRITICAL, BAT_TEMP_HIGH, BAT_TEMP_CRITICAL,
            GPS_WEAK, GPS_LOST, RC_SIGNAL_WEAK, RC_SIGNAL_LOST,
            ALT_OVER, ALT_LOW, SPEED_OVER, ROUTE_DEVIATION,
            STORAGE_LOW, MOTOR_FAULT, ZONE_BREACH, ZONE_ALT_OVER,
            DEVICE_ONLINE, DEVICE_OFFLINE, FLIGHT_STATE_CHANGED,
            EVT_TAKEOFF, EVT_LAND, EVT_RTH, EVT_BATTERY_LOW,
            EVT_MISSION_INTERRUPT, EVT_EMERGENCY_STOP,
            MAN_ONSITE_ISSUE, MAN_DEVICE_DAMAGE, MAN_WEATHER, MAN_CUSTOMER_ISSUE);

    /**
     * 遥测类编码（16 条）。
     *
     * <p>单独列出来是因为<b>识别逻辑只对这类编码做阈值判定</b>，
     * 而它们又都从同一种报文（{@code telemetry}）里算出来。
     * 一个测试会拿它和 {@link #CATEGORY} 对照，保证两张表不漏不错。
     */
    public static final List<String> TELEMETRY_CODES = List.of(
            BAT_LOW, BAT_CRITICAL, BAT_TEMP_HIGH, BAT_TEMP_CRITICAL,
            GPS_WEAK, GPS_LOST, RC_SIGNAL_WEAK, RC_SIGNAL_LOST,
            ALT_OVER, ALT_LOW, SPEED_OVER, ROUTE_DEVIATION,
            STORAGE_LOW, MOTOR_FAULT, ZONE_BREACH, ZONE_ALT_OVER);

    /**
     * 人工上报类编码（4 条）—— 飞手在现场看到、传感器测不到的问题。
     *
     * <h2>它为什么必须单独列出来</h2>
     * 人工上报走的是 HTTP 接口（飞手端提交），而入参里的 {@code alertCode} 是
     * <b>客户端传上来的</b>。如果那个接口不校验编码的类别，飞手就能提交一条
     * {@code bat.critical} —— 那看起来和「设备实测到电量 10%」一模一样，
     * 但它其实没有任何数据支撑。<b>设备测得的告警只能由设备数据产生</b>，
     * 这是一条不能破的边界：破了之后，告警表里就再也分不清
     * 「设备说了什么」和「人说了什么」。
     *
     * <p>所以人工上报接口只收这四条，别的编码一律拒。
     */
    public static final List<String> MANUAL_CODES = List.of(
            MAN_ONSITE_ISSUE, MAN_DEVICE_DAMAGE, MAN_WEATHER, MAN_CUSTOMER_ISSUE);

    /** 分类：遥测 / 状态 / 事件 / 人工 */
    public static final String CATEGORY_TELEMETRY = "telemetry";
    public static final String CATEGORY_STATUS = "status";
    public static final String CATEGORY_EVENT = "event";
    public static final String CATEGORY_MANUAL = "manual";

    private static final Map<String, String> LABELS = Map.ofEntries(
            Map.entry(BAT_LOW, "电量低"),
            Map.entry(BAT_CRITICAL, "电量严重不足"),
            Map.entry(BAT_TEMP_HIGH, "电池温度偏高"),
            Map.entry(BAT_TEMP_CRITICAL, "电池温度过高"),
            Map.entry(GPS_WEAK, "定位信号弱"),
            Map.entry(GPS_LOST, "定位丢失"),
            Map.entry(RC_SIGNAL_WEAK, "图传/遥控信号弱"),
            Map.entry(RC_SIGNAL_LOST, "图传/遥控信号丢失"),
            Map.entry(ALT_OVER, "超出限高"),
            Map.entry(ALT_LOW, "低于安全高度"),
            Map.entry(SPEED_OVER, "超速"),
            Map.entry(ROUTE_DEVIATION, "航线偏离"),
            Map.entry(STORAGE_LOW, "存储空间不足"),
            Map.entry(MOTOR_FAULT, "电机异常"),
            Map.entry(ZONE_BREACH, "进入禁飞区"),
            Map.entry(ZONE_ALT_OVER, "禁飞区限高内超高"),
            Map.entry(DEVICE_ONLINE, "设备上线"),
            Map.entry(DEVICE_OFFLINE, "设备离线"),
            Map.entry(FLIGHT_STATE_CHANGED, "飞行状态变化"),
            Map.entry(EVT_TAKEOFF, "起飞"),
            Map.entry(EVT_LAND, "降落"),
            Map.entry(EVT_RTH, "返航"),
            Map.entry(EVT_BATTERY_LOW, "电量低（设备上报）"),
            Map.entry(EVT_MISSION_INTERRUPT, "任务中断"),
            Map.entry(EVT_EMERGENCY_STOP, "紧急停止"),
            Map.entry(MAN_ONSITE_ISSUE, "现场异常"),
            Map.entry(MAN_DEVICE_DAMAGE, "设备损伤"),
            Map.entry(MAN_WEATHER, "天气突变"),
            Map.entry(MAN_CUSTOMER_ISSUE, "客户问题"));

    private static final Map<String, String> CATEGORIES = Map.ofEntries(
            Map.entry(BAT_LOW, CATEGORY_TELEMETRY),
            Map.entry(BAT_CRITICAL, CATEGORY_TELEMETRY),
            Map.entry(BAT_TEMP_HIGH, CATEGORY_TELEMETRY),
            Map.entry(BAT_TEMP_CRITICAL, CATEGORY_TELEMETRY),
            Map.entry(GPS_WEAK, CATEGORY_TELEMETRY),
            Map.entry(GPS_LOST, CATEGORY_TELEMETRY),
            Map.entry(RC_SIGNAL_WEAK, CATEGORY_TELEMETRY),
            Map.entry(RC_SIGNAL_LOST, CATEGORY_TELEMETRY),
            Map.entry(ALT_OVER, CATEGORY_TELEMETRY),
            Map.entry(ALT_LOW, CATEGORY_TELEMETRY),
            Map.entry(SPEED_OVER, CATEGORY_TELEMETRY),
            Map.entry(ROUTE_DEVIATION, CATEGORY_TELEMETRY),
            Map.entry(STORAGE_LOW, CATEGORY_TELEMETRY),
            Map.entry(MOTOR_FAULT, CATEGORY_TELEMETRY),
            Map.entry(ZONE_BREACH, CATEGORY_TELEMETRY),
            Map.entry(ZONE_ALT_OVER, CATEGORY_TELEMETRY),
            Map.entry(DEVICE_ONLINE, CATEGORY_STATUS),
            Map.entry(DEVICE_OFFLINE, CATEGORY_STATUS),
            Map.entry(FLIGHT_STATE_CHANGED, CATEGORY_STATUS),
            Map.entry(EVT_TAKEOFF, CATEGORY_EVENT),
            Map.entry(EVT_LAND, CATEGORY_EVENT),
            Map.entry(EVT_RTH, CATEGORY_EVENT),
            Map.entry(EVT_BATTERY_LOW, CATEGORY_EVENT),
            Map.entry(EVT_MISSION_INTERRUPT, CATEGORY_EVENT),
            Map.entry(EVT_EMERGENCY_STOP, CATEGORY_EVENT),
            Map.entry(MAN_ONSITE_ISSUE, CATEGORY_MANUAL),
            Map.entry(MAN_DEVICE_DAMAGE, CATEGORY_MANUAL),
            Map.entry(MAN_WEATHER, CATEGORY_MANUAL),
            Map.entry(MAN_CUSTOMER_ISSUE, CATEGORY_MANUAL));

    private static final Map<String, String> DEFAULT_SEVERITIES = Map.ofEntries(
            Map.entry(BAT_LOW, AlertSeverity.WARNING),
            Map.entry(BAT_CRITICAL, AlertSeverity.CRITICAL),
            Map.entry(BAT_TEMP_HIGH, AlertSeverity.WARNING),
            Map.entry(BAT_TEMP_CRITICAL, AlertSeverity.CRITICAL),
            Map.entry(GPS_WEAK, AlertSeverity.WARNING),
            Map.entry(GPS_LOST, AlertSeverity.CRITICAL),
            Map.entry(RC_SIGNAL_WEAK, AlertSeverity.WARNING),
            Map.entry(RC_SIGNAL_LOST, AlertSeverity.CRITICAL),
            Map.entry(ALT_OVER, AlertSeverity.WARNING),
            Map.entry(ALT_LOW, AlertSeverity.WARNING),
            Map.entry(SPEED_OVER, AlertSeverity.WARNING),
            Map.entry(ROUTE_DEVIATION, AlertSeverity.WARNING),
            Map.entry(STORAGE_LOW, AlertSeverity.WARNING),
            Map.entry(MOTOR_FAULT, AlertSeverity.CRITICAL),
            Map.entry(ZONE_BREACH, AlertSeverity.CRITICAL),
            Map.entry(ZONE_ALT_OVER, AlertSeverity.WARNING),
            Map.entry(DEVICE_ONLINE, AlertSeverity.INFO),
            // 基准是 warning（可能只是关机了）；设备上有执行中任务时由识别逻辑升成 critical
            Map.entry(DEVICE_OFFLINE, AlertSeverity.WARNING),
            Map.entry(FLIGHT_STATE_CHANGED, AlertSeverity.INFO),
            Map.entry(EVT_TAKEOFF, AlertSeverity.INFO),
            Map.entry(EVT_LAND, AlertSeverity.INFO),
            Map.entry(EVT_RTH, AlertSeverity.WARNING),
            Map.entry(EVT_BATTERY_LOW, AlertSeverity.WARNING),
            Map.entry(EVT_MISSION_INTERRUPT, AlertSeverity.CRITICAL),
            Map.entry(EVT_EMERGENCY_STOP, AlertSeverity.CRITICAL),
            Map.entry(MAN_ONSITE_ISSUE, AlertSeverity.WARNING),
            Map.entry(MAN_DEVICE_DAMAGE, AlertSeverity.CRITICAL),
            Map.entry(MAN_WEATHER, AlertSeverity.WARNING),
            Map.entry(MAN_CUSTOMER_ISSUE, AlertSeverity.INFO));

    /** 中文名；未登记的编码原样返回（不返回 null，免得日志里到处判空） */
    public static String label(String code) {
        return code == null ? null : LABELS.getOrDefault(code, code);
    }

    /** 分类；未登记的编码返回 null */
    public static String category(String code) {
        return code == null ? null : CATEGORIES.get(code);
    }

    /**
     * 基准级别；未登记的编码返回 {@link AlertSeverity#WARNING}。
     *
     * <p>为什么不返回 null：识别逻辑拿到 null 就得判空，判漏了就会往库里写 null
     * 而 {@code severity} 是 NOT NULL —— 一条 INSERT 失败就丢了一条告警。
     * 给一个「中间档」的兜底值，最差也只是级别不准，不会丢数据。
     */
    public static String defaultSeverity(String code) {
        return DEFAULT_SEVERITIES.getOrDefault(code, AlertSeverity.WARNING);
    }

    /** 是不是清单里登记过的编码 */
    public static boolean isKnown(String code) {
        return code != null && LABELS.containsKey(code);
    }

    /**
     * 是不是人工上报类的编码。
     *
     * <p>人工上报接口用它把关，理由见 {@link #MANUAL_CODES}。
     */
    public static boolean isManual(String code) {
        return code != null && MANUAL_CODES.contains(code);
    }
}
