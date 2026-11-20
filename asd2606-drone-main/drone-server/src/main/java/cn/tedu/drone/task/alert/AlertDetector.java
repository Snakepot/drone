package cn.tedu.drone.task.alert;

import cn.tedu.drone.device.pojo.DevicePO;
import cn.tedu.drone.task.mqtt.DeviceMessage;
import cn.tedu.drone.task.pojo.AlertCode;
import cn.tedu.drone.task.pojo.AlertPO;
import cn.tedu.drone.task.pojo.AlertSeverity;
import cn.tedu.drone.task.pojo.EvidenceType;
import cn.tedu.drone.task.pojo.TaskPO;
import cn.tedu.drone.zone.pojo.ZoneCheckInput;
import cn.tedu.drone.zone.pojo.ZoneCheckPoint;
import cn.tedu.drone.zone.pojo.ZoneHitVO;
import cn.tedu.drone.zone.service.NoFlyZoneService;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 从一条设备报文里识别出告警 —— <b>纯逻辑，不碰数据库</b>。
 *
 * <h2>它在链路里的位置</h2>
 * 六步处理链路（见 {@code docs/MQTT与设备接入.md} 4.5）做完证据之后，
 * 多出来的第七步：<b>证据归证据，告警归告警，两者都留</b>。
 * 一条「电量低」的遥测，既归档成这次飞行的客观记录，也产生一条待处理的问题。
 *
 * <p>之所以把识别抽成一个只做判断的类，是因为它<b>最容易做错、也最需要反复测</b>
 * （阈值的边界、字段缺失、互斥关系），而它一旦依赖数据库就没法快速单测了。
 * 落库交给 {@code AlertService}。
 *
 * <h2>四类报文，三种处理方式（实施步骤第一步 + 第四步）</h2>
 * <ul>
 *   <li>{@code drone/{编号}/telemetry} —— <b>阈值识别</b>：设备只是报数，
 *       「多少算超」是我们自己算的</li>
 *   <li>{@code drone/{编号}/status} —— <b>连通性识别</b>：只看 {@code flightState}
 *       是不是 {@code offline}</li>
 *   <li>{@code drone/{编号}/event/{类型}} —— <b>翻译</b>：事件类型在 topic 里，
 *       设备说「我返航了」，我们只是把它翻成一条告警</li>
 *   <li>人工上报 —— <b>不走 MQTT</b>，本条链路不参与（走 HTTP，实施步骤第五步）</li>
 * </ul>
 *
 * <h2>三类告警的来源不同，能调松紧的地方也就不同</h2>
 * 遥测类调<b>阈值</b>（配置里改），事件类只能改<b>设备侧</b>（我们只是接收方）。
 * 把这两件事混起来想，会出现「我改了阈值怎么这个告警还在报」这种困惑。
 *
 * <h2>报文里认定的字段名（设备侧约定）</h2>
 * <ul>
 *   <li>{@code battery} 电量百分比、{@code batteryTemp} 电池温度（℃）</li>
 *   <li>{@code gpsLevel} 定位等级（0 表示丢失）、{@code signal} 图传/遥控信号等级</li>
 *   <li>{@code alt} 相对起飞点的高度（米，AGL）、{@code speed} 速度（米/秒）</li>
 *   <li>{@code sdFreeGb} 可用存储（GB）、{@code motorState} 电机状态（{@code normal} 为正常）</li>
 *   <li>{@code flightState} 飞行/在线状态：判断高度类告警、以及判断设备是否离线都要用它</li>
 * </ul>
 * <b>字段缺失一律跳过、不猜</b>：设备没报电量就不判电量告警。
 * 宁可少报，也不要把「没数据」当成「数据是 0」—— 后者会在每个架次里造出一条假告警。
 *
 * <h2>三个刻意的判断</h2>
 * <ol>
 *   <li><b>同一测点的两个阈值只报最严重的那一条。</b>
 *       电量 10% 时 {@code bat.low}（≤30）和 {@code bat.critical}（≤15）都成立，
 *       报两条等于一条报文触发两个告警，人看到会以为出了两件事。
 *       而 critical 本身已经蕴含了 low。</li>
 *   <li><b>高度类告警只在「明确在空中」时才判。</b>
 *       起飞和降落时飞机本来就在地面附近，不加这个条件，
 *       每个架次都会在起降阶段误报「低于安全高度」——
 *       假告警比不报警更糟，因为会让人对整个告警体系失去信任。</li>
 *   <li><b>「设备在正常运转」的通报不产生告警。</b>
 *       {@code device.online}、{@code evt.takeoff}、{@code evt.land}、
 *       {@code flight.state.changed} 这四条是 {@code info} 级，
 *       记的是「设备干了件正常的事」，不是「哪里不对劲」。
 *       它们<b>不落 task_alert</b>，理由见 {@link #NARRATIVE_CODES}。</li>
 * </ol>
 */
@Slf4j
@Component
public class AlertDetector {

    /** Topic 约定：{@code drone/{设备编号}/{分类}/...}，分类固定在第 3 层（下标 2） */
    private static final int TOPIC_CATEGORY_LEVEL = 2;

    /** 事件类型在第 4 层（下标 3）：{@code drone/{编号}/event/{类型}} */
    private static final int TOPIC_EVENT_TYPE_LEVEL = 3;

    private static final String TELEMETRY_CATEGORY = "telemetry";
    private static final String STATUS_CATEGORY = "status";
    private static final String EVENT_CATEGORY = "event";

    /**
     * 判定「在空中」的 flightState 取值。
     *
     * <p>只认这两个明确表示离地的值。设备报一个我们不认识的值时按「不知是否在空中」处理 ——
     * 也就是不判高度告警。<b>这也是这份约定必须写进设备对接文档的原因</b>：
     * 设备侧要是把飞行状态写成别的词，高度告警会静默失效。
     */
    private static final List<String> AIRBORNE_STATES = List.of("flying", "hovering");

    /** 电机状态为这个值时算正常 */
    private static final String MOTOR_NORMAL = "normal";

    /**
     * status 报文里表示「连接断开」的取值 —— 设备正常退出时自己发一条，
     * 异常掉线时由 broker 代发遗嘱（LWT）。两种都覆盖到，才不会漏掉「离线」这个事实。
     */
    private static final String STATE_OFFLINE = "offline";

    /** status 报文里表示「连接建立」的取值 */
    private static final String STATE_ONLINE = "online";

    /**
     * topic 里的事件类型 → 告警编码。
     *
     * <p>为什么要有一张表：事件类型是<b>设备侧的词</b>（{@code battery-low}），
     * 告警编码是<b>我们的词</b>（{@code evt.battery.low}）。
     * 两套词的转换只在这一个地方发生，将来设备换个写法只改这一张表。
     *
     * <p>取值必须与 {@code docs/告警体系设计.md} 2.3 节的表格一致。
     */
    private static final Map<String, String> EVENT_CODES = Map.of(
            "takeoff", AlertCode.EVT_TAKEOFF,
            "land", AlertCode.EVT_LAND,
            "rth", AlertCode.EVT_RTH,
            "battery-low", AlertCode.EVT_BATTERY_LOW,
            "mission-interrupt", AlertCode.EVT_MISSION_INTERRUPT,
            "emergency-stop", AlertCode.EVT_EMERGENCY_STOP);

    /**
     * <b>不落告警</b>的「通报类」编码 —— 这份名单是本类里最需要解释的一处。
     *
     * <p>这四条的严重级别都是 {@code info}，语义是「设备干了件正常的事」：
     * 开机、起飞、降落、飞行状态切换。<b>它们不是「哪里不对劲」</b>，
     * 而告警的定义就是「哪里不对劲、需要人看一眼」。
     *
     * <p><b>为什么不落库（三条理由，第三条是决定性的）</b>：
     * <ol>
     *   <li>原始报文<b>已经归档成证据</b>了（{@code task_evidence}），
     *       飞行时间线一条都不少 —— 不落告警不等于丢失</li>
     *   <li>量太大：每次开机、每个架次都会产生，属于设计文档第六章说的
     *       「每天几十上百条」，正是会让人对通知脱敏的那一类</li>
     *   <li><b>落进去会永久占住一条「待处理」。</b>告警的抑制规则是
     *       「同一设备同一编码还有未处理的就不再产生新行」（4.4 节），
     *       而 info 级在流程矩阵里是「无人处理」，<b>永远不会有人去把它关掉</b>。
     *       两者一叠加：某台设备的第一次「起飞」会永久挂在待处理列表里，
     *       并且永远挡住后面所有的「起飞」—— 既没意义又撤不掉</li>
     * </ol>
     *
     * <p>编码本身<b>保留在词汇表里</b>（{@code AlertCode} 的 27 条不变）：
     * 它们是清单的一部分，将来若要做一个「统一事件流」视图，可以直接用这些编码。
     * 现在只是<b>没有生产者</b>，和 {@code task:cancel} 权限点「已埋未用」是同一回事。
     */
    private static final Set<String> NARRATIVE_CODES = Set.of(
            AlertCode.DEVICE_ONLINE,
            AlertCode.FLIGHT_STATE_CHANGED,
            AlertCode.EVT_TAKEOFF,
            AlertCode.EVT_LAND);

    private final AlertProperties properties;

    /**
     * 禁飞区判定。
     *
     * <p>遥测这条链路上，判据不在报文里、而在我们这边的只有它一个：
     * 设备报「我在哪、多高」，而「这里不能飞」是拿禁飞区数据算出来的。
     * 所以它需要这个 Service，而别的阈值告警只需要配置。
     */
    private final NoFlyZoneService noFlyZoneService;

    public AlertDetector(AlertProperties properties, NoFlyZoneService noFlyZoneService) {
        this.properties = properties;
        this.noFlyZoneService = noFlyZoneService;
    }

    /**
     * 识别一条报文可能触发的告警。
     *
     * @param message    已经过解析的设备消息（信封与原文都在里面）
     * @param device     已经确认登记的设备（第 ③ 步查出来的）
     * @param task       该设备上执行中的任务，<b>可以为 null</b> ——
     *                   告警的归属对象可能是设备而不是任务，没有任务也要照报；
     *                   它还有一个用途：判断 {@code device.offline} 该报 warning 还是 critical
     * @param observedAt 报文里那个时刻，<b>由调用方传进来</b>而不是在这里重新解析：
     *                   调用方（TelemetryIngestServiceImpl）已经解析过一次，
     *                   再解析一遍等于同一件事有两个实现，早晚会不一致
     * @return 识别出的告警，可能为空列表（绝大多数报文都是空的）
     */
    public List<AlertPO> detect(DeviceMessage message, DevicePO device, TaskPO task, LocalDateTime observedAt) {
        if (!properties.isEnabled()) {
            return List.of();
        }

        String category = categoryOf(message.topic());
        JsonNode envelopePayload = (message.envelope() == null) ? null : message.envelope().getPayload();
        boolean hasObjectPayload = envelopePayload != null && envelopePayload.isObject();

        // 事件类先判：**它的关键信息在 topic 里，不在 payload 里**，
        // 所以「payload 不是对象就退出」这一关不能拦在它前面
        // （一个不带 payload 的 emergency-stop 照样是紧急停止）
        if (EVENT_CATEGORY.equals(category)) {
            return detectEvent(message, envelopePayload, device, task, observedAt);
        }

        if (!hasObjectPayload) {
            return List.of();
        }
        if (TELEMETRY_CATEGORY.equals(category)) {
            return detectTelemetry(envelopePayload, device, task, observedAt);
        }
        if (STATUS_CATEGORY.equals(category)) {
            return detectStatus(envelopePayload, device, task, observedAt);
        }
        return List.of();
    }

    // ------------------------------------------------------------------
    // 遥测类：阈值由我们判定
    // ------------------------------------------------------------------

    private List<AlertPO> detectTelemetry(JsonNode payload, DevicePO device, TaskPO task,
                                          LocalDateTime observedAt) {
        List<AlertPO> alerts = new ArrayList<>();

        // ---- 电量：两个阈值互斥，只报最严重的 ----
        Double battery = number(payload, "battery");
        if (battery != null) {
            if (battery <= properties.thresholdOf(AlertCode.BAT_CRITICAL, 15)) {
                alerts.add(build(AlertCode.BAT_CRITICAL, device, task, observedAt,
                        "电量仅 " + fmt(battery) + "%（阈值 ≤" + fmt(properties.thresholdOf(AlertCode.BAT_CRITICAL, 15)) + "%）"));
            } else if (battery <= properties.thresholdOf(AlertCode.BAT_LOW, 30)) {
                alerts.add(build(AlertCode.BAT_LOW, device, task, observedAt,
                        "电量 " + fmt(battery) + "%（阈值 ≤" + fmt(properties.thresholdOf(AlertCode.BAT_LOW, 30)) + "%）"));
            }
        }

        // ---- 电池温度：同样互斥 ----
        Double batteryTemp = number(payload, "batteryTemp");
        if (batteryTemp != null) {
            if (batteryTemp >= properties.thresholdOf(AlertCode.BAT_TEMP_CRITICAL, 65)) {
                alerts.add(build(AlertCode.BAT_TEMP_CRITICAL, device, task, observedAt,
                        "电池温度 " + fmt(batteryTemp) + "℃（阈值 ≥"
                                + fmt(properties.thresholdOf(AlertCode.BAT_TEMP_CRITICAL, 65)) + "℃）"));
            } else if (batteryTemp >= properties.thresholdOf(AlertCode.BAT_TEMP_HIGH, 55)) {
                alerts.add(build(AlertCode.BAT_TEMP_HIGH, device, task, observedAt,
                        "电池温度 " + fmt(batteryTemp) + "℃（阈值 ≥"
                                + fmt(properties.thresholdOf(AlertCode.BAT_TEMP_HIGH, 55)) + "℃）"));
            }
        }

        // ---- 定位：0 算丢失，其余按弱信号阈值 ----
        Double gpsLevel = number(payload, "gpsLevel");
        if (gpsLevel != null) {
            if (gpsLevel <= 0) {
                alerts.add(build(AlertCode.GPS_LOST, device, task, observedAt, "定位丢失（gpsLevel=0）"));
            } else if (gpsLevel <= properties.thresholdOf(AlertCode.GPS_WEAK, 2)) {
                alerts.add(build(AlertCode.GPS_WEAK, device, task, observedAt,
                        "定位信号弱（gpsLevel=" + fmt(gpsLevel) + "，阈值 ≤"
                                + fmt(properties.thresholdOf(AlertCode.GPS_WEAK, 2)) + "）"));
            }
        }

        // ---- 图传/遥控信号：同上 ----
        Double signal = number(payload, "signal");
        if (signal != null) {
            if (signal <= 0) {
                alerts.add(build(AlertCode.RC_SIGNAL_LOST, device, task, observedAt, "图传/遥控信号丢失（signal=0）"));
            } else if (signal <= properties.thresholdOf(AlertCode.RC_SIGNAL_WEAK, 2)) {
                alerts.add(build(AlertCode.RC_SIGNAL_WEAK, device, task, observedAt,
                        "图传/遥控信号弱（signal=" + fmt(signal) + "，阈值 ≤"
                                + fmt(properties.thresholdOf(AlertCode.RC_SIGNAL_WEAK, 2)) + "）"));
            }
        }

        // ---- 高度：必须先确认在空中，否则起降阶段必然误报 ----
        boolean airborne = isAirborne(payload);
        Double alt = number(payload, "alt");
        if (airborne && alt != null) {
            if (alt > properties.thresholdOf(AlertCode.ALT_OVER, 120)) {
                alerts.add(build(AlertCode.ALT_OVER, device, task, observedAt,
                        "高度 " + fmt(alt) + "m 超出限高（阈值 >"
                                + fmt(properties.thresholdOf(AlertCode.ALT_OVER, 120)) + "m）"));
            } else if (alt < properties.thresholdOf(AlertCode.ALT_LOW, 5)) {
                alerts.add(build(AlertCode.ALT_LOW, device, task, observedAt,
                        "高度 " + fmt(alt) + "m 低于安全高度（阈值 <"
                                + fmt(properties.thresholdOf(AlertCode.ALT_LOW, 5)) + "m）"));
            }
        }

        // ---- 速度 ----
        Double speed = number(payload, "speed");
        if (speed != null && speed > properties.thresholdOf(AlertCode.SPEED_OVER, 15)) {
            alerts.add(build(AlertCode.SPEED_OVER, device, task, observedAt,
                    "速度 " + fmt(speed) + "m/s 超速（阈值 >"
                            + fmt(properties.thresholdOf(AlertCode.SPEED_OVER, 15)) + "m/s）"));
        }

        // ---- 存储 ----
        Double sdFreeGb = number(payload, "sdFreeGb");
        if (sdFreeGb != null && sdFreeGb < properties.thresholdOf(AlertCode.STORAGE_LOW, 1)) {
            alerts.add(build(AlertCode.STORAGE_LOW, device, task, observedAt,
                    "可用存储 " + fmt(sdFreeGb) + "GB 不足（阈值 <"
                            + fmt(properties.thresholdOf(AlertCode.STORAGE_LOW, 1)) + "GB）"));
        }

        // ---- 电机：只要报了、且不是 normal 就算异常（不认识的状态也报，因为「看不懂」本身值得人看一眼）----
        String motorState = text(payload, "motorState");
        if (motorState != null && !MOTOR_NORMAL.equalsIgnoreCase(motorState)) {
            alerts.add(build(AlertCode.MOTOR_FAULT, device, task, observedAt,
                    "电机状态异常：motorState=" + motorState));
        }

        // ---- 空间类：禁飞区 ----
        // 判据不在报文里，而在**我们这边**：设备只报「我在哪、多高」，
        // 「这里不能飞」是拿禁飞区数据算出来的（见 docs/地图接入方案.md 6.3.3）。
        // 所以它和上面那些「阈值」不同：那几条的判据是设备报的数值，
        // 这一条的判据是一张会被管理员随时改的表。
        alerts.addAll(detectZone(payload, device, task, observedAt));

        return alerts;
    }

    /**
     * 当前位置有没有落在禁飞区里。
     *
     * <h2>它复用的正是第三步那套判定</h2>
     * 几何（射线法 / 圆）、生效期筛选、外接矩形预筛，全都是
     * {@code NoFlyZoneService.check} 里那一份 —— 所以「创建任务时说会拦」和
     * 「飞行时说越界」用的是同一套规则，不会出现「建的时候不拦、飞的时候报」
     * 这种自相矛盾的情况（设计文档 6.3.3 特意强调过这一点）。
     *
     * <h2>坐标：报文里报的就是 WGS84，不用转换</h2>
     * 设备报的是 GPS 原始坐标，而库里（禁飞区几何）也是 WGS84 ——
     * 两边同系，直接判。⚠️ 别顺手把地图那套 GCJ-02 的转换加进来：
     * 那会让判定偏 500 米，而且看起来「差不多对」。
     *
     * <h2>不加「必须在空中」这个条件（与高度类告警刻意不同）</h2>
     * 高度类必须确认在空中，否则起降阶段必然误报（飞机本来就贴地）。
     * 而「我在禁飞区里」是一句<b>与高度无关</b>的事实 —— 停在禁飞区里通电待命
     * 同样值得提醒一次。靠现成的抑制规则（同设备同编码还有未处理的就不再产生），
     * 它最多报一条，不会刷屏。
     *
     * @return 每个命中的区域一条：完全禁飞的区 → {@code zone.breach}（critical）；
     *         限高区里超高 → {@code zone.alt.over}（warning）。都不命中就是空列表
     */
    private List<AlertPO> detectZone(JsonNode payload, DevicePO device, TaskPO task,
                                     LocalDateTime observedAt) {
        Double lng = number(payload, "lng");
        Double lat = number(payload, "lat");
        if (lng == null || lat == null) {
            // 没报位置就没得判。**不猜、不用上一次的位置** ——
            // 猜错会报一个「它根本没去过的地方」的告警，比不报糟糕得多
            return List.of();
        }
        Double alt = number(payload, "alt");

        List<ZoneHitVO> hits = noFlyZoneService.check(new ZoneCheckInput(
                List.of(new ZoneCheckPoint("设备当前位置", lng, lat, alt)),
                // 单点判定，不判「线穿区」：一条遥测描述的是「此刻在哪」，
                // 没有「从哪到哪」这一段（飞行轨迹是很多条遥测连起来看的）
                List.of()));
        if (hits.isEmpty()) {
            return List.of();
        }

        List<AlertPO> alerts = new ArrayList<>(hits.size());
        for (ZoneHitVO hit : hits) {
            boolean overAlt = Boolean.TRUE.equals(hit.getOverAlt());
            String code = overAlt ? AlertCode.ZONE_ALT_OVER : AlertCode.ZONE_BREACH;
            // 位置用**报文里的原始数字**，不转 GCJ-02：
            // 这条告警事后要拿去和 task_evidence 里的报文原文对账，
            // 两边数字得一模一样，否则「数对不上」会变成一次无谓的排查
            alerts.add(build(code, device, task, observedAt,
                    hit.getMessage() + "（设备报的位置 " + fmt(lng) + ", " + fmt(lat) + "）"));
        }
        return alerts;
    }

    // ------------------------------------------------------------------
    // 状态类：设备在不在
    // ------------------------------------------------------------------

    /**
     * 从 status 报文里识别告警 —— 目前只认「离线」。
     *
     * <h2>为什么「在线」不产生告警</h2>
     * 它是 info 级通报，见 {@link #NARRATIVE_CODES}。
     *
     * <h2>为什么「飞行状态变化」也不做</h2>
     * 「<b>变化</b>」意味着要和<b>上一次</b>的状态比 —— 那需要记住每台设备上次报了什么
     * （存库、或者放内存里），属于设计文档说的「要看上下文」那一类，
     * 而且它是 info 级（悬停→返航这种事，真正重要的是返航这个事件，
     * 已经由 {@code evt.rth} 覆盖了）。所以这里明确不做，而不是忘了做。
     */
    private List<AlertPO> detectStatus(JsonNode payload, DevicePO device, TaskPO task,
                                       LocalDateTime observedAt) {
        String flightState = text(payload, "flightState");
        if (flightState == null) {
            return List.of();
        }
        if (!STATE_OFFLINE.equalsIgnoreCase(flightState)) {
            // online、flying、hovering…… 都不产生告警（理由见方法注释与 NARRATIVE_CODES）
            return List.of();
        }

        // 这是全清单里唯一一条「级别随上下文变」的告警：
        // 设备闲着的时候掉线，可能只是关机了（warning）；
        // 设备掉线时它身上有「执行中」的任务，那是飞机失联了（critical）。
        // 判据就是调用方查出来的 task —— 这也是落库时要顺手记 task_id 的原因之一
        boolean hasExecutingTask = (task != null);
        String severity = hasExecutingTask ? AlertSeverity.CRITICAL : AlertSeverity.WARNING;
        String why = hasExecutingTask
                ? "（设备上有执行中的任务 " + task.getTaskNo() + "，视为失联）"
                : "（设备当前空闲，可能只是关机）";

        return List.of(build(AlertCode.DEVICE_OFFLINE, severity, device, task, observedAt,
                "设备离线" + why));
    }

    // ------------------------------------------------------------------
    // 事件类：设备主动说「我干了什么」
    // ------------------------------------------------------------------

    /**
     * 把一条事件报文翻成告警。
     *
     * <p><b>事件类型在 topic 的第 4 层</b>（{@code drone/{编号}/event/{类型}}），
     * 不在 payload 里 —— 所以本条不依赖 payload，设备只发一个空对象、甚至不发，
     * 我们也照样能识别出是哪种事件。
     *
     * <p>认不出的事件类型只记一条 debug，<b>不落库、不报错</b>：
     * 设备固件可能比我们新，会发我们还不知道的事件。
     * 把它当成错误会让「设备升级」变成一次线上故障；而原始报文已经归档成证据，
     * 真要排查时查得到。
     */
    private List<AlertPO> detectEvent(DeviceMessage message, JsonNode payload, DevicePO device,
                                      TaskPO task, LocalDateTime observedAt) {
        String eventType = eventTypeOf(message.topic());
        String code = (eventType == null) ? null : EVENT_CODES.get(eventType.toLowerCase());

        if (code == null) {
            log.debug("【告警识别】收到未登记的事件类型，仅归档证据、不产生告警，device={}，topic={}，事件类型={}",
                    device.getCode(), message.topic(), eventType);
            return List.of();
        }

        if (NARRATIVE_CODES.contains(code)) {
            // 起飞、降落属于「正常运转的通报」，不落告警（理由见 NARRATIVE_CODES）。
            // 打一条 debug 是为了让读日志的人能确认「确实收到了、确实故意没报」——
            // 否则「没告警」和「没收着」在日志上长得一样
            log.debug("【告警识别】{} 属于通报类（info），不产生告警，device={}，topic={}",
                    AlertCode.label(code), device.getCode(), message.topic());
            return List.of();
        }

        // 事件类里唯独电量低这件事，报文的 payload 带着具体数值，拼进文案里更好读；
        // 其余事件（返航、任务中断、紧急停止）payload 里没有更值得看的东西
        String detail = "";
        if (AlertCode.EVT_BATTERY_LOW.equals(code)) {
            Double battery = number(payload, "battery");
            if (battery != null) {
                detail = "，电量 " + fmt(battery) + "%";
            }
        }

        return List.of(build(code, AlertCode.defaultSeverity(code), device, task, observedAt,
                "设备上报事件：" + AlertCode.label(code) + detail));
    }

    // ------------------------------------------------------------------
    // 内部方法
    // ------------------------------------------------------------------

    /**
     * 拼一条待落库的告警，级别取清单里的默认级别。
     *
     * <p>「需不需要飞手动手」来自配置 —— 这两样都不是识别逻辑该发明的东西，
     * 识别只负责回答「哪条告警被触发了」。
     */
    private AlertPO build(String code, DevicePO device, TaskPO task,
                          LocalDateTime observedAt, String messageText) {
        return build(code, AlertCode.defaultSeverity(code), device, task, observedAt, messageText);
    }

    /**
     * 同上，但级别由调用方指定 —— 只有 {@code device.offline} 用得上
     * （它的级别取决于「设备身上有没有执行中的任务」，算不出来就没法用默认值）。
     */
    private AlertPO build(String code, String severity, DevicePO device, TaskPO task,
                          LocalDateTime observedAt, String messageText) {
        String pilotAction = properties.pilotActionOf(code);
        return new AlertPO()
                .setTaskId(task == null ? null : task.getId())
                .setDeviceCode(device.getCode())
                .setAlertCode(code)
                .setSeverity(severity)
                .setMessage(truncate(messageText))
                .setPayload(null)   // 设备类告警的原始证据在 task_evidence 里，不在这里重复存
                // 来源的三个取值（device / app / operator）是证据表与告警表共用的同一套词汇 ——
                // 两边 DDL 的注释一字不差，所以直接复用 EvidenceType 的常量，不另起一套
                // （两套常量迟早会漂移，那比「这个类名看着不太贴切」严重）
                .setSource(EvidenceType.SOURCE_DEVICE)
                .setObservedAt(observedAt)
                .setReportedAt(LocalDateTime.now())
                .setStatus("pending")
                .setPilotActionRequired(pilotAction == null ? 0 : 1)
                .setActionHint(pilotAction);
    }

    /** message 列是 varchar(500)，超长会在 INSERT IGNORE 下被静默截断 —— 先在应用层截 */
    private static String truncate(String text) {
        return text.length() <= 500 ? text : text.substring(0, 497) + "...";
    }

    /**
     * Topic 里第 3 层那一截（分类）。层数不够返回 null。
     *
     * <p>用「分割后取下标」而不是正则：<b>解析规则只写一处</b>，
     * 正则和下标两种写法混用，改约定时必定漏掉一个。
     */
    private static String levelOf(String topic, int index) {
        if (topic == null) {
            return null;
        }
        String[] levels = topic.split("/");
        return (levels.length > index) ? levels[index] : null;
    }

    private static String categoryOf(String topic) {
        return levelOf(topic, TOPIC_CATEGORY_LEVEL);
    }

    private static String eventTypeOf(String topic) {
        return levelOf(topic, TOPIC_EVENT_TYPE_LEVEL);
    }

    /** payload 里明确说自己在空中，才返回 true。认不出来一律当「不知道」 */
    private boolean isAirborne(JsonNode payload) {
        String flightState = text(payload, "flightState");
        return flightState != null && AIRBORNE_STATES.contains(flightState.toLowerCase());
    }

    /**
     * 取数字字段；缺失或不是数字都返回 null（不返回 0，见类注释）。
     *
     * <p>{@code payload} 本身可能是 null（事件类允许不带 payload），
     * 所以这里是「能给 null 的入参」—— 用 {@link JsonNode#path} 而不是 {@code get}
     * 也是同一个考虑：{@code path} 永远不返回 null，少一个判空分支就少一个漏判的机会。
     */
    private static Double number(JsonNode payload, String field) {
        if (payload == null) {
            return null;
        }
        JsonNode node = payload.path(field);
        return node.isNumber() ? node.asDouble() : null;
    }

    /** 取字符串字段；缺失或不是字符串都返回 null */
    private static String text(JsonNode payload, String field) {
        if (payload == null) {
            return null;
        }
        JsonNode node = payload.path(field);
        return node.isTextual() ? node.asText() : null;
    }

    /** 数字显示成「12」而不是「12.0」——日志和告警文案是给人看的 */
    private static String fmt(double value) {
        return value == Math.floor(value) && !Double.isInfinite(value)
                ? String.valueOf((long) value)
                : String.valueOf(value);
    }
}
