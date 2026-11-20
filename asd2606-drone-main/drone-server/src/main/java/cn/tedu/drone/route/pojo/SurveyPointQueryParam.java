package cn.tedu.drone.route.pojo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * 点位查询条件。
 *
 * <p>和别的列表一样，条件对象同时给「查这一页」和「数总数」用 ——
 * 两处共用一份条件，才不会出现「总数说有 10 条、翻页只有 8 条」。
 */
@Data
@Accessors(chain = true)
@Schema(description = "点位查询条件")
public class SurveyPointQueryParam implements Serializable {

    public static final int DEFAULT_PAGE_SIZE = 10;

    /** 单页最大条数：点位一次可能要看很多（比如「把这条线路的 12 个点都列出来」） */
    public static final int MAX_PAGE_SIZE = 200;

    @Schema(description = "关键字，模糊匹配 名称 / 编号")
    private String keyword;

    @Schema(description = "类型 survey / takeoff / dock / other")
    private String category;

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
