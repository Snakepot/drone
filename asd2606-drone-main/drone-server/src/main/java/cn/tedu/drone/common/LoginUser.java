package cn.tedu.drone.common;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.util.Set;

/**
 * 当前登录用户，由拦截器解析 Token 并查库补全角色与权限后，放进 {@link UserContext}。
 *
 * <h2>角色与权限为什么要查库，而不是塞进 Token</h2>
 * 好处是<b>权限变更下次请求立即生效</b> —— 管理员改了某人的角色，他不用重新登录。
 * 如果写进 Token，就得等 Token 过期才生效。
 * <p>代价是每个受保护请求多一次关联查询（当前数据量下是毫秒级）。
 *
 * <h2>它和 UserPO、UserVO 有什么区别（初学者常混）</h2>
 * <ul>
 *   <li>{@code UserPO}：和数据库表一一对应，可能包含密码等敏感字段，只在 DAO 与 Service 之间流转</li>
 *   <li>{@code UserVO}：返回给前端展示的对象，不含密码，但带角色与权限</li>
 *   <li><b>本类</b>：本次请求期间的「身份凭证」，由拦截器组装，供鉴权与业务层取用</li>
 * </ul>
 */
@Data
@Accessors(chain = true)
@Schema(description = "当前登录用户")
public class LoginUser implements Serializable {

    @Schema(description = "用户 ID")
    private Long id;

    @Schema(description = "用户名")
    private String username;

    @Schema(description = "角色编码集合，如 [admin]")
    private Set<String> roles;

    @Schema(description = "权限编码集合，如 [staff:add, device:list]")
    private Set<String> permissions;

    /**
     * 判断是否拥有指定权限。
     *
     * @param code 权限编码，如 {@code "staff:add"}
     */
    public boolean hasPermission(String code) {
        return permissions != null && permissions.contains(code);
    }
}
