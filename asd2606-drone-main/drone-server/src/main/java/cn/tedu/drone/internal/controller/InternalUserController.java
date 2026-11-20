package cn.tedu.drone.internal.controller;

import cn.tedu.drone.common.JsonResult;
import cn.tedu.drone.internal.pojo.AuthoritiesVO;
import cn.tedu.drone.user.service.UserService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 服务间内部接口，供 drone-agent 取用户的角色与权限。
 *
 * <h2>为什么要有这个接口</h2>
 * 权限刻意不放进 Token（见 docs/RBAC权限设计.md），token 里只有用户 ID。
 * drone-agent 是独立服务，需要同一份 RBAC 数据才能按同样的权限码鉴权，
 * 于是通过本接口向 drone-server 取，而不是自己连库。
 *
 * <h2>安全</h2>
 * 本路径已在 {@code WebMvcConfig} 里被用户 JWT 拦截器放行，
 * 改由 {@code InternalAuthInterceptor} 用共享密钥（{@code X-Internal-Token}）把关。
 * <b>只能在内网暴露</b>，不要经网关转发出去。
 */
@RestController
@RequestMapping("/internal/user")
public class InternalUserController {

    private final UserService userService;

    public InternalUserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping("/{userId}/authorities")
    public JsonResult<AuthoritiesVO> authorities(@PathVariable Long userId) {
        return JsonResult.ok(new AuthoritiesVO(
                userService.listRoles(userId),
                userService.listPermissions(userId)));
    }
}
