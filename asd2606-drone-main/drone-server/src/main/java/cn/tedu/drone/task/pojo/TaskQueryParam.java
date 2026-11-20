package cn.tedu.drone.task.pojo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;
import org.springframework.format.annotation.DateTimeFormat;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 任务查询条件。
 *
 * <p>监控页最常用的筛选维度：状态、优先级、飞手，加上关键字。
 */
@Data
@Accessors(chain = true)
@Schema(description = "任务查询条件")
public class TaskQueryParam implements Serializable {

    /** 默认每页条数 */
    public static final int DEFAULT_PAGE_SIZE = 10;

    /** 单页最大条数 */
    public static final int MAX_PAGE_SIZE = 200;

    @Schema(description = "关键字，模糊匹配 任务编号 / 任务名称")
    private String keyword;

    @Schema(description = "状态，见状态机")
    private String status;

    @Schema(description = "优先级")
    private String priority;

    @Schema(description = "飞手 ID")
    private Long pilotId;

    /**
     * 「实际结束时间」区间（下钻用，见 docs/业务分析方案.md 三点五）。
     *
     * <p>为什么按**实际结束时间**而不是创建时间：分析页那些按时间口径算出来的数
     * （完成率、按时率、平均时长）都是按它落窗的。列表若按创建时间筛，
     * 下钻过去的条数就会和那个数对不上 —— 而「对得上」正是下钻唯一的验收标准。
     */
    @Schema(description = "实际结束时间 >=（含）")
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime endFrom;

    @Schema(description = "实际结束时间 <（不含）")
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime endTo;

    @Schema(description = "页码，从 1 开始")
    private Integer page = 1;

    @Schema(description = "每页条数，默认 10，最大 200")
    private Integer pageSize = DEFAULT_PAGE_SIZE;

    /**
     * SQL 的 OFFSET，供 MyBatis 的 {@code #{offset}} 调用。
     * <p>页码非法时兜底为第 1 页，每页条数收敛到上限。
     */
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
