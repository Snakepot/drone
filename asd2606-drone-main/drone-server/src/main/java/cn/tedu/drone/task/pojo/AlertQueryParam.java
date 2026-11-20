package cn.tedu.drone.task.pojo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;
import org.springframework.format.annotation.DateTimeFormat;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 告警查询条件（管理端告警页的筛选栏）。
 *
 * <p>和 {@link TaskQueryParam} 一样自带分页：把「页码怎么兜底、
 * 每页最多多少条」这类规则收在一个类里，免得每个查询接口各写一遍、
 * 各漏一个边界。
 */
@Data
@Accessors(chain = true)
@Schema(description = "告警查询条件")
public class AlertQueryParam implements Serializable {

    public static final int DEFAULT_PAGE_SIZE = 15;
    public static final int MAX_PAGE_SIZE = 200;

    @Schema(description = "关键字，模糊匹配 设备编号 / 告警内容")
    private String keyword;

    @Schema(description = "严重级别：info / warning / critical")
    private String severity;

    @Schema(description = "处理状态：pending / handling / resolved / ignored")
    private String status;

    @Schema(description = "设备编号，精确匹配")
    private String deviceCode;

    @Schema(description = "任务 id，精确匹配。飞手端查「自己这条任务上的告警」用它；"
            + "管理端也能用（任务详情页将来要按任务筛）")
    private Long taskId;

    @Schema(description = "只看需要飞手在现场动手的（1 是 / 0 否，不传就是全部）")
    private Integer pilotActionRequired;

    /**
     * 「发生时间」区间（下钻用，见 docs/业务分析方案.md 三点五）。
     *
     * <p>与 MTTR、告警趋势同一列：那些数都是按 {@code observed_at} 落窗的，
     * 列表按同一列筛才对得上。
     */
    @Schema(description = "发生时间 >=（含）")
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime observedFrom;

    @Schema(description = "发生时间 <（不含）")
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime observedTo;

    @Schema(description = "页码，从 1 开始")
    private Integer page = 1;

    @Schema(description = "每页条数，默认 15，最大 200")
    private Integer pageSize = DEFAULT_PAGE_SIZE;

    /** SQL 的 OFFSET，供 MyBatis 的 {@code #{offset}} 调用 */
    public int getOffset() {
        return (normalizedPage() - 1) * normalizedPageSize();
    }

    public int getLimit() {
        return normalizedPageSize();
    }

    private int normalizedPage() {
        return (page == null || page < 1) ? 1 : page;
    }

    private int normalizedPageSize() {
        if (pageSize == null || pageSize < 1) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(pageSize, MAX_PAGE_SIZE);
    }
}
