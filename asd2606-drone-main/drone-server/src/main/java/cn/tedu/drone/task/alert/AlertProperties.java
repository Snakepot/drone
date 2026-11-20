package cn.tedu.drone.task.alert;

import cn.tedu.drone.task.pojo.AlertCode;
import cn.tedu.drone.task.pojo.AlertSeverity;
import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 告警的阈值与「需要飞手动手」的提示，绑定 {@code drone.alert.*}。
 *
 * <h2>为什么阈值不写死在代码里</h2>
 * 同一个机型在平原和在高原的合理电池温度不一样；同一条航线，
 * 巡检和植保对「超速」的容忍度也不一样。写死就意味着每换一个场景就要改代码、发版。
 *
 * <h2>为什么用 Map，键直接用告警编码</h2>
 * <pre>
 * drone:
 *   alert:
 *     threshold:
 *       bat.low: 30
 *       bat.critical: 15
 *     pilot-action:
 *       bat.critical: 立即返航并就近降落
 * </pre>
 * 键就是 {@link AlertCode} 里的编码 —— <b>配置里看到的东西和清单里的是同一套名字</b>，
 * 不用在脑子里做一次翻译。
 *
 * <p><b>代价要说清楚</b>：Spring 绑定失败时不会报错，拼错一个键就静默生效不了。
 * 所以 {@link #logEffectiveConfig()} 在启动时把生效值全打出来 ——
 * 「我改了配置到底有没有生效」看一眼日志就知道，不用猜。
 */
@Slf4j
@Data
@Component
@ConfigurationProperties(prefix = "drone.alert")
public class AlertProperties {

    /** 识别总开关。关掉后告警不再产生（证据链路不受影响） */
    private boolean enabled = true;

    /**
     * 阈值表：键是告警编码，值是判定阈值。
     *
     * <p>代码里用 {@link #thresholdOf(String, double)} 取值，第二个参数是兜底默认值 ——
     * 这样「配置里没写」和「配置里写错了」都会退到默认值，不会因为缺一项就判不出告警。
     */
    private Map<String, Double> threshold = new LinkedHashMap<>();

    /**
     * 需要飞手在现场动手的告警：键是告警编码，值是给他看的一句话。
     *
     * <p>出现在这里的编码，落库时 {@code pilot_action_required} 会置 1、
     * {@code action_hint} 存这句话，飞手端据此显示待办提示。
     * <b>但飞手改不了告警状态</b> —— 处置责任和销账是两件事，
     * 见 docs/告警体系设计.md 第十二章。
     */
    private Map<String, String> pilotAction = new LinkedHashMap<>();

    // ------------------------------------------------------------------
    // 阈值默认值：配置里没写就用这里的
    // 取值依据是 docs/告警体系设计.md 2.1 节，不是标准答案，是「默认建议值」
    // ------------------------------------------------------------------

    // 注意用 Map.ofEntries(Map.entry(...)) 而不是 Map.of(k, v, k, v, ...)：
    // 后者最多只支持 10 对键值，超过就编译不过（这里 11 对）
    private static final Map<String, Double> DEFAULTS = Map.ofEntries(
            Map.entry(AlertCode.BAT_LOW, 30.0),
            Map.entry(AlertCode.BAT_CRITICAL, 15.0),
            Map.entry(AlertCode.BAT_TEMP_HIGH, 55.0),
            Map.entry(AlertCode.BAT_TEMP_CRITICAL, 65.0),
            Map.entry(AlertCode.GPS_WEAK, 2.0),
            Map.entry(AlertCode.RC_SIGNAL_WEAK, 2.0),
            Map.entry(AlertCode.ALT_OVER, 120.0),
            Map.entry(AlertCode.ALT_LOW, 5.0),
            Map.entry(AlertCode.SPEED_OVER, 15.0),
            Map.entry(AlertCode.STORAGE_LOW, 1.0),
            Map.entry(AlertCode.ROUTE_DEVIATION, 30.0));

    /**
     * 取某条告警的阈值。
     *
     * <p>配置里没配就退到 {@link #DEFAULTS}，再没有就用调用方给的兜底值。
     * <b>不抛异常</b>：一条告警算不出来不该让整条遥测的处理失败。
     */
    public double thresholdOf(String alertCode, double fallback) {
        Double configured = threshold == null ? null : threshold.get(alertCode);
        if (configured != null) {
            return configured;
        }
        return DEFAULTS.getOrDefault(alertCode, fallback);
    }

    /** 这条告警需不需要飞手在现场动手；需要的话返回给他的提示，不需要返回 null */
    public String pilotActionOf(String alertCode) {
        return pilotAction == null ? null : pilotAction.get(alertCode);
    }

    /**
     * 启动时把生效的阈值打进日志。
     *
     * <p>用途是排查「我改了 application.yml 怎么没反应」：
     * 配置绑定的失败是静默的，只有把生效值打出来才看得见。
     * 打在 info 级、只在启动时一次，不构成噪音。
     */
    @PostConstruct
    public void logEffectiveConfig() {
        if (!enabled) {
            log.warn("告警识别已关闭（drone.alert.enabled=false）：遥测仍会归档成证据，"
                    + "但不会产生任何告警");
            return;
        }
        StringBuilder sb = new StringBuilder("告警识别已启用，生效阈值（格式：编码=阈值[级别]）：");
        for (String code : AlertCode.TELEMETRY_CODES) {
            double value = thresholdOf(code, 0);
            boolean fromConfig = threshold != null && threshold.containsKey(code);
            sb.append("\n    ").append(code).append('=').append(value)
                    .append('[').append(AlertSeverity.label(AlertCode.defaultSeverity(code))).append(']')
                    .append(fromConfig ? "（配置）" : "（默认）");
        }
        if (pilotAction != null && !pilotAction.isEmpty()) {
            sb.append("\n    需要飞手动手的：").append(pilotAction.keySet());
        }
        log.info(sb.toString());
    }
}
