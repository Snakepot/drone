package cn.tedu.drone.task.service;

import cn.tedu.drone.task.pojo.TaskTrackVO;

/**
 * 任务轨迹（给地图画的那条线）。
 *
 * <p>单独一个 Service 而不是塞进 {@code TaskService}：那一个已经很长了，
 * 而这里做的事自成一摊 —— 查证据里的坐标、抽稀、转坐标系，
 * 三件事都和「把轨迹画出来」有关，和任务状态机没有关系。
 */
public interface TaskTrackService {

    /**
     * 取某条任务的轨迹，<b>坐标转成 GCJ-02</b>，并可交给地图直接画。
     *
     * <p>数据来源是 {@code task_evidence} 的报文原文（设备每秒报的经纬度）——
     * 也就是说这条轨迹**不是新数据**，只是把早就在库里的坐标取出来画。
     *
     * @param taskId 任务 id
     * @return 轨迹；这条任务一条带坐标的证据都没有时，{@code points} 是空列表
     * @throws cn.tedu.drone.common.BusinessException 任务不存在
     */
    TaskTrackVO getTrack(Long taskId);
}
