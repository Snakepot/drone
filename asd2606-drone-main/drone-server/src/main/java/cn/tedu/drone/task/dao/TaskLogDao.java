package cn.tedu.drone.task.dao;

import cn.tedu.drone.task.pojo.TaskLogPO;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 任务流转日志数据访问层
 */
public interface TaskLogDao {

    /**
     * 追加一条流转日志。
     * <p>只有 insert，没有 update / delete —— 流水表只追加不修改。
     */
    int insert(TaskLogPO log);

    /**
     * 查询某条任务的全部流转日志，按时间正序
     */
    List<TaskLogPO> selectByTaskId(@Param("taskId") Long taskId);
}
