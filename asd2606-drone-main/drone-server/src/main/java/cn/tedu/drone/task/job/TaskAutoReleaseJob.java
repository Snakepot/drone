package cn.tedu.drone.task.job;

import cn.tedu.drone.task.service.TaskService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 派发超时自动释放任务。
 *
 * <h2>它解决什么问题</h2>
 * 派发会把飞手置「作业中」、设备置「已派出」。如果飞手一直没露面、
 * 管理员也没想起来点「撤回派发」，这两个资源就被永久占住了 ——
 * 尤其是那台设备，从此不会再出现在派发下拉里。
 *
 * <p>这个定时任务定期扫一遍「派发出去了、但计划结束时间早过了还没开工」的任务，
 * 把资源收回来。判定条件、宽限期、为什么回「待派发」而不是新增终态，
 * 都写在 {@code docs/任务业务设计.md} 的「资源占用与释放」一章。
 *
 * <h2>为什么用 @Scheduled 而不是「查列表时顺手释放」</h2>
 * 后者的释放时机取决于「恰好有人打开任务页」，本质上还是不可靠的 ——
 * 没人看页面的时候，资源就一直占着。
 *
 * <h2>⚠️ 这个类上<b>不能</b>放 {@code @EnableScheduling}</h2>
 * 它带着 {@code @ConditionalOnProperty}：开关一关，这个 Bean 就不创建 ——
 * 而 {@code @EnableScheduling} 是<b>全局开关</b>，跟着它一起消失就意味着
 * <b>所有</b>定时任务静默停摆（不报错、日志里也看不出）。
 * 加「待派发归因」时正是这么被坑的，详见启动类上的注释。
 * 每个任务只管用<b>自己</b>那个 {@code @ConditionalOnProperty} 关自己。
 *
 * <h2>测试环境必须关掉它</h2>
 * {@code @SpringBootTest} 会把这个任务一起加载起来，而它跑在
 * <b>独立线程、独立事务</b>里 —— 不受测试方法上 {@code @Transactional} 的约束，
 * 会直接把改动提交到开发库。所以测试里用
 * {@code drone.task.auto-release.enabled=false} 关掉它，
 * 改为直接调用 {@link TaskService#releaseExpiredDispatched()} 来验证释放逻辑。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "drone.task.auto-release.enabled", havingValue = "true", matchIfMissing = true)
public class TaskAutoReleaseJob {

    @Autowired
    private TaskService taskService;

    /**
     * 按固定间隔重复执行（上一次跑完才开始计时，不会重叠执行）。
     *
     * <p>用 {@code fixedDelay} 而不是 {@code fixedRate}：这个任务要连数据库，
     * 万一某次扫得慢了，{@code fixedRate} 会让下一次紧接着压上来。
     */
    @Scheduled(fixedDelayString = "${drone.task.auto-release.interval-ms:600000}")
    public void releaseExpiredDispatched() {
        try {
            int released = taskService.releaseExpiredDispatched();
            if (released > 0) {
                log.info("派发超时自动释放：本次释放 {} 条任务占用的飞手与设备", released);
            }
        } catch (Exception e) {
            // 兜住异常，不让一次失败把后续的定时执行也带崩
            log.error("派发超时自动释放执行失败", e);
        }
    }
}
