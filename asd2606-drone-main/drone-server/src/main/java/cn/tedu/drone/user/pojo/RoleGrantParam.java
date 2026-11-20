package cn.tedu.drone.user.pojo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.util.List;

/**
 * 角色授权参数：把一个角色该有哪些权限一次性提交，服务端按「覆盖式」保存。
 */
@Data
@Accessors(chain = true)
@Schema(description = "角色授权参数")
public class RoleGrantParam implements Serializable {

    @Schema(description = "角色 ID")
    private Long roleId;

    @Schema(description = "权限 ID 列表，传空数组表示清空该角色的全部权限")
    private List<Long> permissionIds;
}
