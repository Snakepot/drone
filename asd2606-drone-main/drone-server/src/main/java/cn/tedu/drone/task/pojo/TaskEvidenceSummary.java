package cn.tedu.drone.task.pojo;

import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 证据汇总 —— 报告里「自动汇总」那一半的数据来源。
 *
 * <h2>它不是一张表</h2>
 * 这些数字**每次都从 {@code task_evidence} 现算**，不落库。
 * 为什么不存进报告表：证据是可以事后追加的（飞行日志补传、照片补交），
 * 存下来的汇总值会过时；一旦过时就要考虑「什么时候重算」，于是又要一套刷新逻辑。
 * 而一条任务的证据行数是有界的（一次飞行几分钟到几小时），现算的开销可以接受。
 *
 * <h2>口径写在文档里，不在这里自由发挥</h2>
 * 每一项的定义见 {@code docs/任务收尾与执行报告.md} 5.2 节 ——
 * 比如「遥测条数」只数 {@code valid = 1} 的。口径分散在代码里迟早会不一致。
 */
@Data
@Accessors(chain = true)
public class TaskEvidenceSummary implements Serializable {

    /** 遥测条数（只数有效的） */
    private Integer telemetryCount;

    /** 第一条证据的时刻 */
    private LocalDateTime firstObservedAt;

    /** 最后一条证据的时刻 */
    private LocalDateTime lastObservedAt;

    /**
     * 期间最低电量（百分比）。
     *
     * <p>从证据原文的 {@code payload.payload.battery} 里取 —— 路径依赖设备报文的
     * 字段约定（见 {@code MQTT与设备接入.md} 4.4）。设备报告里没这个字段（或不是数字）
     * 时返回 null，**不要当成 0**：「没数据」和「电量 0」是两件事。
     */
    private BigDecimal minBattery;

    /** 期间最大高度（米，同上的取法） */
    private BigDecimal maxAlt;
}
