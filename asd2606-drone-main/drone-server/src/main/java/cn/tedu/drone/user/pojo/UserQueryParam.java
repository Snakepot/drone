package cn.tedu.drone.user.pojo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * 员工查询条件。
 *
 * <p>列表查询与导出共用同一套条件 —— 这样「先筛选、再导出」才能成立：
 * 用户看到的列表和导出的文件是同一批数据。
 * 区别只在于导出不传分页参数，取的是符合条件的全部记录。
 */
@Data
@Accessors(chain = true)
@Schema(description = "员工查询条件")
public class UserQueryParam implements Serializable {

    /** 默认每页条数 */
    public static final int DEFAULT_PAGE_SIZE = 10;

    /** 单页最大条数，防止前端传个超大值把服务端拖垮 */
    public static final int MAX_PAGE_SIZE = 200;

    @Schema(description = "关键字，模糊匹配 姓名 / 登录账号 / 手机号")
    private String keyword;

    @Schema(description = "角色编码，如 pilot")
    private String roleCode;

    @Schema(description = "账号状态：1 启用，0 禁用")
    private String status;

    @Schema(description = "页码，从 1 开始")
    private Integer page = 1;

    @Schema(description = "每页条数，默认 10，最大 200")
    private Integer pageSize = DEFAULT_PAGE_SIZE;

    /**
     * SQL 的 OFFSET，供 MyBatis 的 {@code #{offset}} 调用。
     *
     * <p>这里而不是在 XML 里算，是因为要顺手做两层保护：
     * 页码非法时兜底为第 1 页，每页条数非法或过大时收敛到上限。
     *
     * <p><b>注意</b>：OFFSET 会随页码线性劣化（翻到第 10 万页时数据库要扫过前面所有行），
     * 数据量大时应改为游标查询，详见 docs/Excel导入导出.md。
     */
    public int getOffset() {
        return (normalizedPage() - 1) * normalizedPageSize();
    }

    /**
     * SQL 的 LIMIT
     */
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
