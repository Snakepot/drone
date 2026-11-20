package cn.tedu.drone.user.pojo;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 角色，对应 role 表
 */
@Data
@Accessors(chain = true)
@Schema(description = "角色")
public class RolePO implements Serializable {

    @Schema(description = "主键")
    private Long id;

    @Schema(description = "角色编码，代码里引用它，如 admin")
    private String code;

    @Schema(description = "角色名称，如 管理员")
    private String name;

    @Schema(description = "说明")
    private String description;

    @Schema(description = "状态：1 启用，0 禁用")
    private Integer status;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @Schema(description = "更新时间")
    private LocalDateTime updateTime;
}
