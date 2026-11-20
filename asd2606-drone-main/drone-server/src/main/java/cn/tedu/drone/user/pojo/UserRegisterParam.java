package cn.tedu.drone.user.pojo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * 用户注册参数
 */
@Data
@Accessors(chain = true)
@Schema(description = "用户注册参数")
public class UserRegisterParam implements Serializable {

    @Schema(description = "用户名")
    private String username;

    @Schema(description = "密码")
    private String password;

    @Schema(description = "姓名")
    private String name;

    @Schema(description = "邮箱")
    private String email;

    @Schema(description = "手机号")
    private String phone;

    @Schema(description = "年龄")
    private Integer age;

    @Schema(description = "性别")
    private String gender;
}
