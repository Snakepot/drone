package cn.tedu.drone.user.dao;

import cn.tedu.drone.user.pojo.PermissionPO;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 权限数据访问层
 */
public interface PermissionDao {

    /**
     * 查询某个用户最终拥有的权限编码（用户 → 角色 → 权限 三级关联）。
     *
     * <p>只需一条 SQL 就能出结果，所以没有为两张关联表建 PO ——
     * 中间对象在这个场景里没有承载任何数据。
     *
     * @param userId 用户 ID
     * @return 权限编码列表，如 ["device:list", "device:detail"]
     */
    List<String> selectCodesByUserId(@Param("userId") Long userId);

    /**
     * 查询全部权限点，用于权限管理界面展示
     */
    List<PermissionPO> selectAll();
}
