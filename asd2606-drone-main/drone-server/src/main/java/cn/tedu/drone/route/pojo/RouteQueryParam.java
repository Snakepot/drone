package cn.tedu.drone.route.pojo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * 航线查询条件。
 *
 * <p>航线本身没有太多可筛的维度（名称 + 时间），
 * 所以这里只有关键字 —— 不像点位那边还要按类型分。
 */
@Data
@Accessors(chain = true)
@Schema(description = "航线查询条件")
public class RouteQueryParam implements Serializable {

    public static final int DEFAULT_PAGE_SIZE = 10;

    public static final int MAX_PAGE_SIZE = 200;

    @Schema(description = "关键字，模糊匹配航线名称")
    private String keyword;

    @Schema(description = "页码，从 1 开始")
    private Integer page = 1;

    @Schema(description = "每页条数，默认 10，最大 200")
    private Integer pageSize = DEFAULT_PAGE_SIZE;

    /** SQL 的 OFFSET；页码非法时兜底为第 1 页 */
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
