package cn.tedu.drone.user.controller;

import cn.tedu.drone.common.JsonResult;
import cn.tedu.drone.common.RequirePermission;
import cn.tedu.drone.common.UserContext;
import cn.tedu.drone.user.pojo.PermissionVO;
import cn.tedu.drone.user.pojo.RoleGrantParam;
import cn.tedu.drone.user.pojo.RoleSaveParam;
import cn.tedu.drone.user.pojo.RoleVO;
import cn.tedu.drone.user.pojo.UserGrantParam;
import cn.tedu.drone.user.pojo.UserVO;
import cn.tedu.drone.user.service.RbacService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 权限管理接口（对应前端的「系统设置 → 权限管理」）。
 *
 * <h2>它和 UserController 的关系</h2>
 * <ul>
 *   <li>{@code /user/**} 是 <b>消费端</b>：登录、查自己、按权限访问业务接口</li>
 *   <li>{@code /system/**} 是 <b>配置端</b>：管理角色、管理权限、给角色与员工授权</li>
 * </ul>
 * 两者合起来才是权限闭环 —— 改权限不用再手工动数据库。
 *
 * <h2>为什么路径用 /system 但类放在 user 包</h2>
 * 路径按功能归属划分（系统设置），包按业务领域划分（角色权限属于用户领域）。
 * 数据层也都在 {@code cn.tedu.drone.user} 下，放在一起更好找。
 *
 * <h2>每个接口都挂了权限</h2>
 * 即使拿到了菜单入口，不同操作还要求不同的权限码 ——
 * 比如能看（{@code system:rbac:list}）不一定能改（{@code system:role:save}）。
 *
 * <h2>出入参一律是 Param 和 VO</h2>
 * 角色、权限点以前是直接把 {@code RolePO} / {@code PermissionPO} 返出去的，
 * 现在统一换成 {@code RoleVO} / {@code PermissionVO}：
 * PO 跟着表结构走，VO 跟着页面需求走，中间在 Service 里转一道。
 * 好处是加一列内部字段不会自动泄漏给前端。
 */
@Slf4j
@Tag(name = "系统设置 - 权限管理")
@RestController
@RequestMapping("/system")
public class RbacController {

    @Autowired
    private RbacService rbacService;

    @Operation(summary = "角色列表")
    @RequirePermission("system:rbac:list")
    @GetMapping("/role/list")
    public JsonResult<List<RoleVO>> roleList() {
        log.info("Controller [{}] 查询角色列表", UserContext.threadLabel());
        return JsonResult.ok(rbacService.listRoles());
    }

    @Operation(summary = "新增或更新角色")
    @RequirePermission("system:role:save")
    @PostMapping("/role/save")
    public JsonResult<Void> saveRole(@RequestBody RoleSaveParam param) {
        rbacService.saveRole(param);
        return JsonResult.ok("保存成功", null);
    }

    @Operation(summary = "查询角色已勾选的权限")
    @RequirePermission("system:rbac:list")
    @GetMapping("/role/{roleId}/permission-ids")
    public JsonResult<List<Long>> rolePermissionIds(@PathVariable Long roleId) {
        return JsonResult.ok(rbacService.listRolePermissionIds(roleId));
    }

    @Operation(summary = "保存角色的权限")
    @RequirePermission("system:role:grant")
    @PostMapping("/role/grant")
    public JsonResult<Void> grantRolePermissions(@RequestBody RoleGrantParam param) {
        rbacService.grantRolePermissions(param.getRoleId(), param.getPermissionIds());
        return JsonResult.ok("授权成功", null);
    }

    @Operation(summary = "权限点列表")
    @RequirePermission("system:rbac:list")
    @GetMapping("/permission/list")
    public JsonResult<List<PermissionVO>> permissionList() {
        return JsonResult.ok(rbacService.listPermissions());
    }

    @Operation(summary = "员工列表（授权用）")
    @RequirePermission("system:rbac:list")
    @GetMapping("/user/list")
    public JsonResult<List<UserVO>> userList() {
        return JsonResult.ok(rbacService.listUsers());
    }

    @Operation(summary = "查询员工已勾选的角色")
    @RequirePermission("system:rbac:list")
    @GetMapping("/user/{userId}/role-ids")
    public JsonResult<List<Long>> userRoleIds(@PathVariable Long userId) {
        return JsonResult.ok(rbacService.listUserRoleIds(userId));
    }

    @Operation(summary = "保存员工的角色")
    @RequirePermission("system:user:grant")
    @PostMapping("/user/grant")
    public JsonResult<Void> grantUserRoles(@RequestBody UserGrantParam param) {
        rbacService.grantUserRoles(param.getUserId(), param.getRoleIds());
        return JsonResult.ok("授权成功", null);
    }
}
