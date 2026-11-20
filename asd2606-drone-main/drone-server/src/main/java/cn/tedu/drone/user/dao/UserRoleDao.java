package cn.tedu.drone.user.dao;

import cn.tedu.drone.user.pojo.UserRoleRef;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 用户角色关联表的数据访问层
 *
 * <p>关联表没有对应的 PO —— 它只存两个 ID，没有独立业务含义。
 */
public interface UserRoleDao {

    /**
     * 查询用户已有的角色 ID 列表，用于授权界面回显勾选状态
     */
    List<Long> selectRoleIdsByUserId(@Param("userId") Long userId);

    /**
     * 清空用户的全部角色。
     * <p>授权采用「覆盖式」：先把旧的删干净再插新的，避免在前端做增量比对。
     */
    int deleteByUserId(@Param("userId") Long userId);

    /**
     * 批量插入用户的角色
     *
     * @param userId  用户 ID
     * @param roleIds 角色 ID 列表（调用方需保证非空，空列表会导致 SQL 语法错误）
     */
    int insertBatch(@Param("userId") Long userId, @Param("roleIds") List<Long> roleIds);

    /**
     * 批量插入「用户-角色」关联，每条的组合都不同。
     *
     * <p>导入场景用：一次导入 1000 个用户，就是 1000 条各不相同的关联记录，
     * 没法用上面那个「一个用户配多个角色」的方法。
     *
     * @param refs 关联项列表，调用方需保证非空
     */
    int insertBatchRefs(@Param("refs") List<UserRoleRef> refs);
}
