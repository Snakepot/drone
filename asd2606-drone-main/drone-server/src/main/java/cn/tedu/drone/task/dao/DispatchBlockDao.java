package cn.tedu.drone.task.dao;

import cn.tedu.drone.task.dispatch.DispatchBlockPO;
import org.apache.ibatis.annotations.Param;

/**
 * 待派发归因点的数据访问层（见 {@code docs/业务分析方案.md} 2.7）。
 *
 * <p>只有两件事：插一条、查某条任务最近那一条（用来做「同一个原因不重复记」）。
 *
 * <p><b>按区间统计归因分布不在这里</b> —— 那是分析页的聚合查询，
 * 归 {@code analytics/dao/AnalyticsDao}（归属是「问题」而不是「表」，
 * 理由见那个接口上的注释）。
 */
public interface DispatchBlockDao {

    int insert(DispatchBlockPO block);

    /** 某条任务**最近**一次采样的归因；没采过返回 null */
    DispatchBlockPO selectLatestByTaskId(@Param("taskId") Long taskId);
}
