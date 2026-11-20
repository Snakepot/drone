package cn.tedu.droneagent.common;

import lombok.Data;
import lombok.ToString;
import lombok.experimental.Accessors;

/**
 * 当前登录用户。由 {@code AuthInterceptor} 解析 Token 并向 drone-server 取到角色与权限后，
 * 放进 {@link UserContext} 供业务代码取用。
 *
 * <p>字段与 drone-server 的同名类保持一致，便于两边对照排查。
 *
 * <h2>为什么要保存原始 Token</h2>
 * 调用 `drone-mcp-server` 时，平台侧要按**用户本人**的身份鉴权与审计。
 * Token 在拦截器里本来就有，只是以前用完就丢；现在顺势带在这里，
 * 由 {@code ConversationService} 取走放进 {@link cn.tedu.droneagent.tool.ToolContext}。
 *
 * <p><b>安全</b>：{@code bearerToken} 用 {@code @ToString.Exclude} 排除，
 * 避免任何 {@code log.info("user={}", loginUser)} 把凭据打进日志。
 */
@Data
@Accessors(chain = true)
public class LoginUser {

    private Long id;

    private String username;

    /** 角色编码集合，如 [admin] */
    private java.util.Set<String> roles;

    /** 权限编码集合，如 [task:list, agent:chat] */
    private java.util.Set<String> permissions;

    /** 当前请求的原始 JWT（不含 `Bearer ` 前缀）；只用于向内部服务转发身份 */
    @ToString.Exclude
    private String bearerToken;

    public boolean hasPermission(String code) {
        return permissions != null && permissions.contains(code);
    }
}
