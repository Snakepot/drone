package cn.tedu.drone.analytics.pojo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 预聚合表 {@code stat_daily} 的一行（见 {@code docs/业务分析方案.md} 4.3 ③）。
 *
 * <p>窄表：一行 = 「某天 + 某指标 + 某维度」的一个值。
 * 加指标不用改表结构，代价是查询要带 {@code metric} 条件。
 */
@Data
@Accessors(chain = true)
@Schema(description = "预聚合日表的一行")
public class StatDailyPO implements Serializable {

    private Long id;

    @Schema(description = "哪一天（按应用本地时间的那天）")
    private LocalDate statDate;

    @Schema(description = "指标名，见 StatMetric")
    private String metric;

    /** 维度取值（设备编号等）；不分维度时是空串而不是 null —— 见 StatMetric.NO_DIMENSION 的说明 */
    @Schema(description = "维度取值；空串表示不分维度")
    private String dimension;

    @Schema(description = "值")
    private BigDecimal value;

    @Schema(description = "口径版本")
    private Integer caliberVersion;
}
