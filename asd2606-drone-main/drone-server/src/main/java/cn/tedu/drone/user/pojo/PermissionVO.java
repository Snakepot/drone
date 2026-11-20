package cn.tedu.drone.user.pojo;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 权限点视图对象，返回给前端。
 *
 * <p>权限管理页要用它渲染「权限勾选树」：按 {@code parentId} 分组、
 * 按 {@code sort} 排序、用 {@code code} 提交授权。这些是页面真正需要的字段，
 * 所以这里全部保留。
 *
 * <p>它和 {@link PermissionPO} 的关系见 {@link RoleVO} 的类注释 ——
 * 一句话：PO 跟着表走，VO 跟着页面走，中间靠 Service 里一个 toVO 方法接上。
 */
@Data
@Accessors(chain = true)
@Schema(description = "权限点视图对象")
public class PermissionVO implements Serializable {

    @Schema(description = "主键")
    private Long id;

    @Schema(description = "权限编码，格式 模块:操作，如 staff:add")
    private String code;

    @Schema(description = "权限名称")
    private String name;

    @Schema(description = "类型：api 接口；预留 menu / button")
    private String type;

    @Schema(description = "父级 ID，0 为顶层")
    private Long parentId;

    @Schema(description = "排序")
    private Integer sort;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @Schema(description = "创建时间")
    private LocalDateTime createTime;
}
