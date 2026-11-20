package cn.tedu.drone.user.dao;

import cn.tedu.drone.user.pojo.RolePO;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 角色数据访问层
 */
public interface RoleDao {

    /**
     * 查询某个用户拥有的角色编码。
     *
     * <p>只返回启用状态（status = 1）的角色 —— 角色被禁用后它的权限自动不再生效，
     * 不需要去删 role_permission 里的关联记录。
     *
     * @param userId 用户 ID
     * @return 角色编码列表，如 ["admin"]
     */
    List<String> selectCodesByUserId(@Param("userId") Long userId);

    /**
     * 查询全部角色，用于权限管理界面
     */
    List<RolePO> selectAll();

    /**
     * 按主键查询角色
     */
    RolePO selectById(@Param("id") Long id);

    /**
     * 按编码查询角色，新增时用于校验 code 是否已被占用。
     * <p>不写进 SQL 的 WHERE 里加 status 条件 —— 被禁用的角色也占着这个 code，
     * 不能允许重复。
     */
    RolePO selectByCode(@Param("code") String code);

    /**
     * 新增角色
     */
    int insert(RolePO role);

    /**
     * 按主键更新角色
     */
    int update(RolePO role);
}
