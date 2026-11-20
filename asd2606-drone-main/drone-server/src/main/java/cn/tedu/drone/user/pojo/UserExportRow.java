package cn.tedu.drone.user.pojo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 导出时的查询结果行。
 *
 * <p>比 {@link UserPO} 多一个 {@code roleNames} —— 它是 SQL 里用
 * {@code GROUP_CONCAT} 把该用户的多个角色名拼成的一串，
 * 这样一次查询就能带出角色信息，不用为每一行再查一次角色表。
 */
@Data
public class UserExportRow {

    private Long id;
    private String username;
    private String name;
    private String phone;
    private String email;
    private Integer age;
    private String gender;
    private String status;

    /** 多个角色以逗号连接，如「飞手,机务」 */
    private String roleNames;

    private LocalDateTime createTime;
}
