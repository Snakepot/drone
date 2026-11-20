package cn.tedu.drone.user.pojo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * 员工新增 / 编辑参数。
 *
 * <p>新增与编辑共用一个参数对象，靠是否存在 {@code id} 区分：
 * <ul>
 *   <li>不传 id → 新增</li>
 *   <li>传 id → 编辑</li>
 * </ul>
 *
 * <p><b>编辑时登录账号不可修改</b>：它是登录凭证，也是业务上用来认人的标识，
 * 改了之后用户自己都不知道该用什么登录。参数里虽然保留 username 字段，
 * 但服务端在编辑分支会忽略它。
 *
 * <p><b>密码是选填的</b>：编辑时留空表示「不改密码」。
 * 如果做成必填，管理员每次改个手机号都得先知道原密码，显然不合理。
 */
@Data
@Accessors(chain = true)
@Schema(description = "员工新增/编辑参数")
public class UserSaveParam implements Serializable {

    @Schema(description = "主键，新增时不传")
    private Long id;

    @Schema(description = "登录账号，新增时必填；编辑时会被忽略")
    private String username;

    @Schema(description = "姓名")
    private String name;

    @Schema(description = "密码，新增时必填；编辑时留空表示不修改")
    private String password;

    @Schema(description = "手机号")
    private String phone;

    @Schema(description = "邮箱")
    private String email;

    @Schema(description = "年龄")
    private Integer age;

    @Schema(description = "性别：male / female")
    private String gender;

    @Schema(description = "角色编码，如 pilot；编辑时留空表示不调整角色")
    private String roleCode;
}
