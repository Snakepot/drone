package cn.tedu.drone.user.pojo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * 角色保存参数
 */
@Data
@Accessors(chain = true)
@Schema(description = "角色保存参数")
public class RoleSaveParam implements Serializable {

    @Schema(description = "主键，新增时不传")
    private Long id;

    @Schema(description = "角色编码，仅新增时生效，如 pilot")
    private String code;

    @Schema(description = "角色名称，如 飞手")
    private String name;

    @Schema(description = "说明")
    private String description;

    @Schema(description = "状态：1 启用，0 禁用")
    private Integer status;
}
