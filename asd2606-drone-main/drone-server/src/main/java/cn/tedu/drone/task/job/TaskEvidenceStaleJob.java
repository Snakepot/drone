package cn.tedu.drone.task.job;

import cn.tedu.drone.task.service.TaskService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 证据过期扫描：把「声明了要执行、但迟迟没有设备数据佐证」的任务标记出来。
 *
 * <h2>它和 TaskAutoReleaseJob 是两件必须分开的事</h2>
 * 两个定时任务看起来都在处理「人没动静」，但改的东西完全不同：
 * <pre>
 * TaskAutoReleaseJob   →  改 status：把被占住的飞手与设备收回来
 * TaskEvidenceStaleJob →  只改 evidence_state：把「对不上」摆出来给人看
 * </pre>
 * 判据也不同：前者看「计划结束时间过了多久」，管的是<b>没开工</b>；
 * 后者看「开始执行之后多久没数据」，管的是<b>声称开工了却没旁证</b>。
 *
 * <p>后果的差别更要紧：前者释放资源是安全的（人根本没动手）；
 * 后者如果也去改任务状态（比如自动判失败、自动释放设备），
 * 就可能在冤枉一个真的在天上飞、只是数据链路不通的人。
 * 所以这个任务<b>只标记、不处置</b>，处置的权力留给人。
 *
 * <h2>为什么跟着 drone.mqtt.enabled 一起开关</h2>
 * 因为「证据新鲜度」这件事完全建立在设备数据能进来之上。
 * 没开（或没配）设备数据接入时，所有声明都会在同一时刻集体变成过期 ——
 * 那不是发现了问题，那只是这个功能没启用而已，只会制造噪音。
 * 顺带一个好处：测试环境把 {@code drone.mqtt.enabled=false} 一关，
 * 这个跑在独立线程、独立事务里的定时任务也跟着消失，
 * 不会在测试过程中偷偷改开发库的数据（和 TaskAutoReleaseJob 同样的道理）。
 *
 * <h2>⚠️ 这个类上<b>不能</b>放 {@code @EnableScheduling}</h2>
 * 它是全局开关，跟着一个带 {@code @ConditionalOnProperty} 的类一起消失，
 * 会让<b>所有</b>定时任务静默停摆 —— 详见启动类上的注释。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "drone.mqtt.enabled", havingValue = "true", matchIfMissing = true)
public class TaskEvidenceStaleJob {

    @Autowired
    private TaskService taskService;

    /**
     * 多久没收到设备数据算「声明过期」，单位秒。
     *
     * <p>取值直接绑 {@code drone.mqtt.evidence-stale-seconds}，
     * 与数据接入那边用的是同一个阈值 —— 它们本来就是同一件事的两面，
     * 分成两个配置项迟早会被改成不一样，然后没人说得清以哪个为准。
     */
    @Value("${drone.mqtt.evidence-stale-seconds:120}")
    private int staleSeconds;

    /**
     * 按固定间隔重复执行（上一次跑完才开始计时，不会重叠执行）。
     *
     * <p>间隔比 {@code TaskAutoReleaseJob} 短得多（默认 1 分钟 vs 10 分钟）：
     * 那个管的是「占资源」这种慢变量，晚十分钟无所谓；
     * 这个管的是「证据」这种快变量，晚太久就失去了提示的意义 ——
     * 阈值本身才 120 秒，扫得比它慢就无法及时反映。
     */
    @Scheduled(fixedDelayString = "${drone.mqtt.evidence-stale-scan-ms:60000}")
    public void markStaleEvidence() {
        try {
            int marked = taskService.markStaleEvidence(staleSeconds);
            if (marked > 0) {
                log.info("证据过期扫描：本次标记 {} 条任务为「声明已过期」", marked);
            }
        } catch (Exception e) {
            // 兜住异常，不让一次失败把后续的定时执行也带崩
            log.error("证据过期扫描执行失败", e);
        }
    }
}
