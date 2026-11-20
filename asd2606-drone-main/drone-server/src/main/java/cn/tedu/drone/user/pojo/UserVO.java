package cn.tedu.drone.user.pojo;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Set;

/**
 * 用户视图对象，返回给前端（不含密码）
 */
@Data
@Accessors(chain = true)
@Schema(description = "用户视图对象")
public class UserVO implements Serializable {

    @Schema(description = "主键")
    private Long id;

    @Schema(description = "用户名")
    private String username;

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

    @Schema(description = "账号状态")
    private String status;

    @Schema(description = "在线状态")
    private String state;

    @Schema(description = "角色编码集合，如 [admin]")
    private Set<String> roles;

    @Schema(description = "权限编码集合，如 [staff:add, device:list]")
    private Set<String> permissions;

    @Schema(description = "创建时间")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;
}
