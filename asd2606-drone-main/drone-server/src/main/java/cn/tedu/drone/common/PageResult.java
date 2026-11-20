package cn.tedu.drone.common;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.util.List;

/**
 * 分页结果。
 *
 * <p>和 {@link JsonResult} 是两层含义，别混：
 * <ul>
 *   <li>{@code JsonResult} 是「这次请求成不成功」的外壳</li>
 *   <li>{@code PageResult} 是「成功之后返回的这一页数据」</li>
 * </ul>
 * 实际响应形如 {@code {"state":200,"data":{"list":[...],"total":100,"page":1,"pageSize":10}}}
 *
 * <p>为什么要单独返回 {@code total}？因为前端分页组件需要知道总条数才能算出总页数。
 * 所以分页查询一定是两条 SQL：一条查当页数据，一条 {@code COUNT(*)}。
 */
@Data
@Accessors(chain = true)
@Schema(description = "分页结果")
public class PageResult<T> implements Serializable {

    @Schema(description = "当前页数据")
    private List<T> list;

    @Schema(description = "总条数")
    private long total;

    @Schema(description = "当前页码，从 1 开始")
    private int page;

    @Schema(description = "每页条数")
    private int pageSize;

    public static <T> PageResult<T> of(List<T> list, long total, int page, int pageSize) {
        return new PageResult<T>()
                .setList(list)
                .setTotal(total)
                .setPage(page)
                .setPageSize(pageSize);
    }
}
