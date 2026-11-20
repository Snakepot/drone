package cn.tedu.drone.user.pojo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * 用户登录结果
 */
@Data
@Accessors(chain = true)
@Schema(description = "用户登录结果")
public class UserLoginVO implements Serializable {

    @Schema(description = "登录令牌")
    private String token;

    @Schema(description = "用户信息")
    private UserVO user;
}
