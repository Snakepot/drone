package cn.tedu.drone.task.dao;

import cn.tedu.drone.task.pojo.TaskReportPO;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 报告数据访问层。
 *
 * <h2>方法名里为什么带 Latest</h2>
 * 报告是多版本的（一条任务可以有多份），旧版本留在表里当留痕。
 * 所以<b>按任务查报告时必须带 {@code is_latest = 1}</b> ——
 * 漏了会查出好几条，而且不报错。把 {@code Latest} 写进方法名，
 * 是让「我到底要的是不是当前版本」这件事在读代码时就绕不过去。
 */
public interface TaskReportDao {

    /**
     * 插入一份报告。
     *
     * <p>调用方负责把 {@code version} 与 {@code is_latest} 填好 ——
     * 通常新报告是「版本 = 当前最大版本 + 1、is_latest = 1」，
     * 而把旧版本翻成 0 是 {@link #markOldVersionsNotLatest} 的事。
     */
    int insert(TaskReportPO report);

    /** 查当前版本（详情页、以及「有没有报告」都用它） */
    TaskReportPO selectLatestByTaskId(@Param("taskId") Long taskId);

    /** 查全部版本，新的在前（将来的「修订历史」用） */
    List<TaskReportPO> selectAllVersions(@Param("taskId") Long taskId);

    /** 当前最大版本号；一条报告都没有时返回 null */
    Integer selectMaxVersion(@Param("taskId") Long taskId);

    /**
     * 把这条任务已有的报告全部标成「历史版本」（{@code is_latest = 0}）。
     *
     * <p>提交新版本时先调它，再插入新行 —— 两步合起来保证
     * 「同一任务最多只有一条 is_latest = 1」。顺序不能反：
     * 先插新的再翻旧的，中间会有一小段「两条都是最新」的时间窗。
     */
    int markOldVersionsNotLatest(@Param("taskId") Long taskId);
}
