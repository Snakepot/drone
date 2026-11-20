package cn.tedu.drone.user.pojo;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 用户与角色的关联项，供批量插入 user_role 使用。
 *
 * <p>为什么不是简单的 {@code List<Long>}？因为一张表里要插多行、
 * 每行的 {@code (userId, roleId)} 组合都不同，必须成对传递。
 */
@Data
@AllArgsConstructor
public class UserRoleRef {

    private Long userId;

    private Long roleId;
}
