package cn.tedu.drone.user.dao;

import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 角色权限关联表的数据访问层
 */
public interface RolePermissionDao {

    /**
     * 查询角色已有的权限 ID 列表，用于授权界面回显勾选状态
     */
    List<Long> selectPermissionIdsByRoleId(@Param("roleId") Long roleId);

    /**
     * 清空角色的全部权限（覆盖式授权的前半步）
     */
    int deleteByRoleId(@Param("roleId") Long roleId);

    /**
     * 批量插入角色的权限
     *
     * @param roleId        角色 ID
     * @param permissionIds 权限 ID 列表（调用方需保证非空）
     */
    int insertBatch(@Param("roleId") Long roleId, @Param("permissionIds") List<Long> permissionIds);
}
