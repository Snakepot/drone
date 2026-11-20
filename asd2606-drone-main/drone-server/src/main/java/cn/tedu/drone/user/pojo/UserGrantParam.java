package cn.tedu.drone.user.pojo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.util.List;

/**
 * 员工授权参数：把一个员工该有哪些角色一次性提交，服务端按「覆盖式」保存。
 */
@Data
@Accessors(chain = true)
@Schema(description = "员工授权参数")
public class UserGrantParam implements Serializable {

    @Schema(description = "用户 ID")
    private Long userId;

    @Schema(description = "角色 ID 列表，传空数组表示收回该员工的全部角色")
    private List<Long> roleIds;
}
