package cn.tedu.drone.user.pojo;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 权限点，对应 permission 表
 */
@Data
@Accessors(chain = true)
@Schema(description = "权限点")
public class PermissionPO implements Serializable {

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
