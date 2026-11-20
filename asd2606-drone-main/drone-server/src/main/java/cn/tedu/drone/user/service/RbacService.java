package cn.tedu.drone.user.service;

import cn.tedu.drone.user.pojo.PermissionVO;
import cn.tedu.drone.user.pojo.RoleSaveParam;
import cn.tedu.drone.user.pojo.RoleVO;
import cn.tedu.drone.user.pojo.UserVO;

import java.util.List;

/**
 * 权限管理（RBAC 配置端）业务接口。
 *
 * <p>前面 {@link UserService} 提供的是「消费端」——校验某个用户有没有权限；
 * 本接口提供的是「配置端」——管理角色、管理权限、给角色和员工授权。
 * 两者合起来才构成权限闭环：改配置不用再手工动数据库。
 *
 * <p>返回给前端的都是 VO 而不是 PO：PO 只活在 DAO 与 Service 之间。
 */
public interface RbacService {

    /**
     * 查询全部角色
     */
    List<RoleVO> listRoles();

    /**
     * 新增或更新角色（有 id 为更新，无 id 为新增）
     *
     * @throws cn.tedu.drone.common.BusinessException 编码重复、角色不存在、试图禁用管理员角色
     */
    void saveRole(RoleSaveParam param);

    /**
     * 查询某个角色已勾选的权限 ID
     */
    List<Long> listRolePermissionIds(Long roleId);

    /**
     * 覆盖式保存角色的权限：先清空原有关系，再写入新的
     */
    void grantRolePermissions(Long roleId, List<Long> permissionIds);

    /**
     * 查询全部权限点
     */
    List<PermissionVO> listPermissions();

    /**
     * 查询员工列表（授权界面用）
     */
    List<UserVO> listUsers();

    /**
     * 查询某个员工已勾选的角色 ID
     */
    List<Long> listUserRoleIds(Long userId);

    /**
     * 覆盖式保存员工的角色
     *
     * @throws cn.tedu.drone.common.BusinessException 试图修改自己的角色
     */
    void grantUserRoles(Long userId, List<Long> roleIds);
}
