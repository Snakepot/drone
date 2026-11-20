package cn.tedu.drone.user.pojo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * 用户登录参数
 */
@Data
@Accessors(chain = true)
@Schema(description = "用户登录参数")
public class UserLoginParam implements Serializable {

    @Schema(description = "用户名")
    private String username;

    @Schema(description = "密码")
    private String password;
}
