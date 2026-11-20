package cn.tedu.drone.route.dao;

import cn.tedu.drone.route.pojo.RoutePO;
import cn.tedu.drone.route.pojo.RoutePointPO;
import cn.tedu.drone.route.pojo.RouteQueryParam;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 航线数据访问层。
 *
 * <h2>为什么两张表（route / route_point）共用一个 DAO</h2>
 * 因为航点<b>不是独立的东西</b>：它没有自己的列表页、不能被单独查询或修改，
 * 它只在「某条航线有几个点」这个语境里存在。拆成两个 DAO 就会出现
 * 「谁能调用 insertPoints」这种没法在代码里表达的约束 ——
 * 而放在一起，`insertPoints` 的调用者只有一个：{@code RouteService}。
 *
 * <p>这也决定了更新方式：<b>不做「航点局部增删改」，一律整份替换</b>
 * （见 {@code RouteServiceImpl#replacePoints}）。一条航线几十个点，
 * 改航线时前端本来拿到的就是完整的一份新的点列表。
 */
public interface RouteDao {

    /** 按条件分页查询航线（不带航点） */
    List<RoutePO> selectByCondition(RouteQueryParam param);

    /** 按条件统计总数；条件必须与 {@link #selectByCondition} 完全一致 */
    long countByCondition(RouteQueryParam param);

    /** 按主键查询航线；不存在返回 null */
    RoutePO selectById(@Param("id") Long id);

    /** 取某条航线的全部航点，已按 seq 升序 */
    List<RoutePointPO> selectPointsByRouteId(@Param("routeId") Long routeId);

    /** 新增航线（主键回填到 {@code route.id}） */
    int insert(RoutePO route);

    /** 按非空字段更新航线本体（点由 {@link #deletePointsByRouteId} + {@link #insertPoints} 处理） */
    int update(RoutePO route);

    int deleteById(@Param("id") Long id);

    /** 清空一条航线的全部航点 */
    int deletePointsByRouteId(@Param("routeId") Long routeId);

    /**
     * 批量插入航点。
     *
     * <p>{@code routeId} 已经在每个元素上设好；调用方需保证非空 ——
     * 空集合会拼出语法错误的 SQL，而不是「插了 0 行」。
     */
    int insertPoints(List<RoutePointPO> points);
}
