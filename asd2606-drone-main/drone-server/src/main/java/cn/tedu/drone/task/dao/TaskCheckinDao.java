package cn.tedu.drone.task.dao;

import cn.tedu.drone.task.pojo.TaskCheckinPO;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 到点打卡数据访问层。
 *
 * <p>坐标全是 WGS84。字面上看这个 DAO 只有两件事（插一条、按任务查），
 * 但插入那一条的<b>去重语义</b>是整条离线补传链路的地基 —— 见
 * {@link #insertIgnore} 的说明。
 */
public interface TaskCheckinDao {

    /**
     * 插一条打卡，<b>撞上唯一键就跳过</b>（返回 0）。
     *
     * <p>为什么必须是 IGNORE：飞手在没信号的地方打卡，客户端先把记录存本地、
     * 联网后补传；而补传这件事本身可能失败、可能超时后重试、也可能用户手动又点了一次同步。
     * 用「先查有没有、再插」两步来做，中间就有窗口；靠唯一键
     * {@code (task_id, seq, observed_at)} 一次到位才是可靠的 ——
     * 这三样一样就是同一次打卡，重发多少次都只落一条。
     *
     * @return 影响行数：1 = 真的新插了一条；0 = 这条之前已经在了
     */
    int insertIgnore(TaskCheckinPO checkin);

    /** 取某条任务的全部打卡记录，按航点序号 + 时刻升序 */
    List<TaskCheckinPO> selectByTaskId(@Param("taskId") Long taskId);

    /** 一条任务已经打卡了几个**不同**的航点（飞手端显示「3/6 个点已打卡」用） */
    int countDistinctPoints(@Param("taskId") Long taskId);
}
