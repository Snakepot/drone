package cn.tedu.drone.internal.pojo;

import java.util.Set;

/**
 * 某个用户的角色与权限码，内部接口的返回体。
 *
 * <p>与 {@code AuthInterceptor} 组装 {@code LoginUser} 时取的同一份数据，
 * 这样 drone-agent 校验权限的结果与 drone-server 完全一致。
 *
 * @param roles       角色编码，如 [admin]
 * @param permissions 权限编码，如 [task:list, agent:chat]
 */
public record AuthoritiesVO(Set<String> roles, Set<String> permissions) {
}
