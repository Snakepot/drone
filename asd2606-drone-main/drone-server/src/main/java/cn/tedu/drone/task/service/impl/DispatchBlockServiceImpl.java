package cn.tedu.drone.task.service.impl;

import cn.tedu.drone.task.dao.DispatchBlockDao;
import cn.tedu.drone.task.dao.TaskDao;
import cn.tedu.drone.task.dispatch.DispatchBlockPO;
import cn.tedu.drone.task.dispatch.DispatchBlockReason;
import cn.tedu.drone.task.pojo.TaskDispatchOptionVO;
import cn.tedu.drone.task.pojo.TaskPO;
import cn.tedu.drone.task.pojo.TaskQueryParam;
import cn.tedu.drone.task.pojo.TaskStatusFlow;
import cn.tedu.drone.task.service.DispatchBlockService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 待派发归因的实现。
 *
 * <h2>判定用的全是现成的东西</h2>
 * 「可派飞手/设备」的两条来源：
 * <ul>
 *   <li>{@code selectDispatchablePilots} / {@code selectDispatchableDrones} —— 派发下拉那两个候选池</li>
 *   <li>再减掉 {@code countConflictingPilots} / {@code countConflictingDrones}（时间窗内已被占住的）</li>
 * </ul>
 * 一个候选「能不能派」= 在候选池里 <b>且</b> 不冲突 —— 这与派发时的校验规则一致。
 * 所以这里**不另写一套判断**，避免两处结论不一致。
 */
@Slf4j
@Service
public class DispatchBlockServiceImpl implements DispatchBlockService {

    @Autowired
    private TaskDao taskDao;

    @Autowired
    private DispatchBlockDao dispatchBlockDao;

    /**
     * 同一个任务、同样的归因，最短隔多久再记一条（分钟）。
     *
     * <p>默认 60：够画出「这周缺设备」的趋势，又不会让表被同一件事灌满。
     * 原因**变了**则立刻记 —— 那正是我们想看到的信息（从「缺设备」变成「没人派」）。
     */
    @Value("${drone.task.dispatch-block.repeat-minutes:60}")
    private int repeatMinutes;

    @Override
    public int samplePendingTasks() {
        TaskQueryParam query = new TaskQueryParam();
        query.setStatus(TaskStatusFlow.PENDING);
        query.setPageSize(SCAN_BATCH_SIZE);

        List<TaskPO> pending = taskDao.selectByCondition(query);
        if (pending.isEmpty()) {
            log.debug("待派发归因：没有待派发的任务，跳过");
            return 0;
        }

        LocalDateTime now = LocalDateTime.now();
        int written = 0;
        for (TaskPO task : pending) {
            if (task.getPlanStartTime() == null || task.getPlanEndTime() == null) {
                // 计划时间是创建时的必填项，正常走不到这里；真没有就没法判断冲突，
                // 跳过而不是猜 —— 猜出来的归因会污染趋势图
                log.warn("待派发归因：任务缺计划时间，跳过，taskId={}", task.getId());
                continue;
            }

            int pilotCandidates = pilotCandidates(task);
            int droneCandidates = droneCandidates(task);
            String reason = DispatchBlockReason.of(pilotCandidates, droneCandidates);

            if (!shouldWrite(task.getId(), reason, now)) {
                continue;
            }

            dispatchBlockDao.insert(new DispatchBlockPO()
                    .setTaskId(task.getId())
                    .setCheckedAt(now)
                    .setReason(reason)
                    .setPilotCandidates(pilotCandidates)
                    .setDroneCandidates(droneCandidates));
            written++;

            // 只在「资源够但没人派」时记 info：那是唯一一条指向管理动作的结论，
            // 其余三种是「确实缺东西」，属于业务常态，记 debug 即可
            if (DispatchBlockReason.IDLE.equals(reason)) {
                log.info("待派发归因：任务 {} 卡在待派发，但资源是够的（可派飞手 {}、待命设备 {}）"
                                + "—— 指向没人点派发，见 docs/业务分析方案.md 2.7",
                        task.getTaskNo(), pilotCandidates, droneCandidates);
            } else {
                log.debug("待派发归因：taskId={}，原因={}，可派飞手={}，待命设备={}",
                        task.getId(), reason, pilotCandidates, droneCandidates);
            }
        }

        if (written > 0) {
            log.info("待派发归因完成：扫了 {} 条待派发任务，新增 {} 个归因点", pending.size(), written);
        }
        return written;
    }

    /** 可派飞手 = 候选池 − 时间窗内已被占住的。负数兜底为 0（数据异常时不要产出负的候选数） */
    private int pilotCandidates(TaskPO task) {
        int pool = taskDao.selectDispatchablePilots().size();
        long busy = taskDao.countConflictingPilots(task.getPlanStartTime(), task.getPlanEndTime());
        return (int) Math.max(0, pool - busy);
    }

    private int droneCandidates(TaskPO task) {
        List<TaskDispatchOptionVO> pool = taskDao.selectDispatchableDrones();
        long busy = taskDao.countConflictingDrones(task.getPlanStartTime(), task.getPlanEndTime());
        return (int) Math.max(0, pool.size() - busy);
    }

    /**
     * 该不该写这一条。
     *
     * <p>三种情况要写：<b>没记过</b>、<b>原因变了</b>、<b>上次记录已经超过 repeatMinutes</b>。
     * 其余跳过 —— 同一个原因每 10 分钟记一条，一天就是 144 条重复，表会白涨。
     */
    private boolean shouldWrite(Long taskId, String reason, LocalDateTime now) {
        DispatchBlockPO last = dispatchBlockDao.selectLatestByTaskId(taskId);
        if (last == null) {
            return true;
        }
        if (!reason.equals(last.getReason())) {
            return true;
        }
        if (last.getCheckedAt() == null) {
            return true;
        }
        return Duration.between(last.getCheckedAt(), now).toMinutes() >= repeatMinutes;
    }
}
