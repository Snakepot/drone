package cn.tedu.drone.device.pojo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * 设备查询条件。
 *
 * <p>与员工查询的条件结构类似，但筛选维度不同：
 * 设备看的是「类型」和「工作状态」，员工看的是「角色」和「账号状态」。
 * <p>列表查询与导出共用同一套条件，保证「先筛选、再导出」结果一致。
 */
@Data
@Accessors(chain = true)
@Schema(description = "设备查询条件")
public class DeviceQueryParam implements Serializable {

    /** 默认每页条数 */
    public static final int DEFAULT_PAGE_SIZE = 10;

    /** 单页最大条数，防止前端传个超大值把服务端拖垮 */
    public static final int MAX_PAGE_SIZE = 200;

    @Schema(description = "关键字，模糊匹配 设备编号 / 名称 / 型号")
    private String keyword;

    @Schema(description = "设备类型，如 multi-rotor")
    private String type;

    @Schema(description = "工作状态，如 idle / flying / maintenance")
    private String status;

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
