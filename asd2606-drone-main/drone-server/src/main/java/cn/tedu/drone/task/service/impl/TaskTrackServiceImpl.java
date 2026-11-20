package cn.tedu.drone.task.service.impl;

import cn.tedu.drone.common.BusinessException;
import cn.tedu.drone.common.CoordinateUtil;
import cn.tedu.drone.task.dao.TaskDao;
import cn.tedu.drone.task.dao.TaskEvidenceDao;
import cn.tedu.drone.task.pojo.TaskPO;
import cn.tedu.drone.task.pojo.TaskTrackPoint;
import cn.tedu.drone.task.pojo.TaskTrackVO;
import cn.tedu.drone.task.service.TaskTrackService;
import cn.tedu.drone.task.track.TrackSampler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 任务轨迹：把证据里的坐标取出来，抽稀，转成地图能用的坐标系。
 *
 * <h2>三步，顺序固定</h2>
 * <ol>
 *   <li><b>查</b>：从 {@code task_evidence} 的报文原文里取 lng/lat（按时间升序）</li>
 *   <li><b>抽稀</b>：见 {@link TrackSampler} —— 不抽稀会卡</li>
 *   <li><b>转坐标系</b>：WGS84 → GCJ-02，<b>放在最后一步</b></li>
 * </ol>
 * 为什么转换必须最后做：抽稀要算「两点间几米」，而算距离用 WGS84 更自然
 * （设备报的原值、库里存的原值）。先转换再抽稀的话，距离是在偏移后的坐标上算的
 * —— 虽然差得极小，但**让一个纯数学步骤去依赖坐标系的语义**是不必要的耦合。
 */
@Slf4j
@Service
public class TaskTrackServiceImpl implements TaskTrackService {

    private final TaskDao taskDao;
    private final TaskEvidenceDao evidenceDao;

    public TaskTrackServiceImpl(TaskDao taskDao, TaskEvidenceDao evidenceDao) {
        this.taskDao = taskDao;
        this.evidenceDao = evidenceDao;
    }

    @Override
    public TaskTrackVO getTrack(Long taskId) {
        TaskPO task = taskDao.selectById(taskId);
        if (task == null) {
            // 不返回空轨迹：那样「任务 id 打错了」会表现得和「这条任务没有坐标」一样，
            // 而这两件事该给的反馈完全不同
            throw new BusinessException("任务不存在");
        }

        List<TaskTrackPoint> raw = evidenceDao.selectTrack(taskId);
        List<TaskTrackPoint> points = TrackSampler.sample(raw);

        for (TaskTrackPoint point : points) {
            double[] gcj = CoordinateUtil.wgs84ToGcj02(point.getLng(), point.getLat());
            point.setLng(gcj[0]);
            point.setLat(gcj[1]);
        }

        log.debug("任务轨迹已生成：taskId={}，原始 {} 个点，抽稀后 {} 个点",
                taskId, raw.size(), points.size());

        return new TaskTrackVO()
                .setRawCount(raw.size())
                .setPoints(points);
    }
}
