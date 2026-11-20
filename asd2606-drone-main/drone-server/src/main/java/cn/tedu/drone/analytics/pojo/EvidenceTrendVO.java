package cn.tedu.drone.analytics.pojo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.util.List;

/**
 * 证据量与活跃设备趋势（见 {@code docs/业务分析方案.md} 2.3 与 4.3 ③）。
 *
 * <h2>两条序列、一条横轴</h2>
 * 「每天多少条证据」与「每天多少台设备活跃」来自**同一次扫描**：
 * 预聚合表的粒度是「天 × 设备」，所以一天的总量是 {@code SUM(value)}、
 * 一天的活跃设备数是那天的行数（{@code COUNT(*)}）。
 * 这也是当初把粒度定在这一层的原因（见 4.5 的实测）。
 *
 * <h2>哪几天是「实时补」的，报文里直接说</h2>
 * {@link #precomputedDays} 与 {@link #liveDays} 告诉界面：
 * 这几天里有多少来自预聚合、有多少是当场从明细算的。
 * 界面上要能说清「今天那个点还在涨」—— 否则看的人会以为数据已经定了。
 */
@Data
@Accessors(chain = true)
@Schema(description = "证据量与活跃设备趋势")
public class EvidenceTrendVO implements Serializable {

    @Schema(description = "区间起点（含）")
    private String from;

    @Schema(description = "区间终点（不含）")
    private String to;

    @Schema(description = "横轴：区间内每一天（缺口也在）")
    private List<String> days;

    @Schema(description = "每天多少条证据（只算有效的）")
    private List<Long> evidenceCounts;

    @Schema(description = "每天多少台设备活跃")
    private List<Long> activeDevices;

    @Schema(description = "其中有多少天来自预聚合表")
    private Integer precomputedDays;

    @Schema(description = "有多少天是当场从明细实时算的（正常只该是「今天」）")
    private Integer liveDays;
}
