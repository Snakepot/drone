package cn.tedu.drone.zone.dao;

import cn.tedu.drone.zone.pojo.NoFlyZonePO;
import cn.tedu.drone.zone.pojo.NoFlyZoneQueryParam;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 禁飞区数据访问层。
 *
 * <p>几何字段（{@code geometry}）在这里始终是**一坨 JSON 文本** ——
 * DAO 不解析它。解析和序列化都在 Service 里，因为「怎么解析」取决于形状
 * （多边形是数组、圆是对象），那是业务判断，不是存取的活。
 */
public interface NoFlyZoneDao {

    /** 按条件分页查询 */
    List<NoFlyZonePO> selectByCondition(NoFlyZoneQueryParam param);

    /** 按条件统计总数；条件必须与 {@link #selectByCondition} 完全一致 */
    long countByCondition(NoFlyZoneQueryParam param);

    /** 按主键查询；不存在返回 null */
    NoFlyZonePO selectById(@Param("id") Long id);

    /**
     * 取所有「启用中且在生效期内」的区域 —— <b>校验用的就这一条查询</b>。
     *
     * <p>一次全取、判定在内存里做：区域数量是「几十」这个量级，
     * 而校验可能被频繁触发（第四步每条遥测都要判一次），
     * 逐条查库是不可接受的（设计文档 6.3.3 ②）。
     *
     * <p>⚠️ {@code now} 由 Java 传进来，不用 SQL 的 {@code NOW()}：
     * mysqld 的时区是 UTC，而 effective_from/to 是我们按本地时间写进去的，
     * 两边差 8 小时 —— 用 NOW() 比会让所有「今天开始生效」的区域提前 8 小时上线。
     * 同一个坑在任务超时释放那里也踩过（见 TaskMapper 的注释）。
     */
    List<NoFlyZonePO> selectEffective(@Param("now") LocalDateTime now);

    int insert(NoFlyZonePO zone);

    /** 整份覆盖（不是「按非空字段拼」）：编辑表单会给全所有字段，而且要让「清空限高/生效期」能生效 */
    int update(NoFlyZonePO zone);

    int deleteById(@Param("id") Long id);

    /** 只启用/停用，不动其它字段 —— 排查问题时要能临时停掉一个区，但不能顺手改了它的形状 */
    int updateEnabled(@Param("id") Long id, @Param("enabled") Integer enabled);
}
