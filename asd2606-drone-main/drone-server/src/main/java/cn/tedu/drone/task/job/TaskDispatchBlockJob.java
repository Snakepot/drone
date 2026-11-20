package cn.tedu.drone.task.job;

import cn.tedu.drone.task.service.DispatchBlockService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 定时给待派发任务打归因点（见 {@code docs/业务分析方案.md} 2.7）。
 *
 * <h2>为什么必须定时跑，而不是「打开分析页时再算」</h2>
 * 它记的是「**当时**有没有空闲飞手和设备」。打开页面时再算，算出来的是**现在**的资源池，
 * 而任务可能上周就卡在那儿了 —— 那时候缺不缺人，现在谁也说不清。
 * 这类「当时的观测」只能靠定时采样积累，而且**停一天就缺一天的数据**，
 * 所以它的日志要能让人看出「今天有没有正常采样」。
 *
 * <h2>为什么单独一个 Job 而不塞进别的定时任务里</h2>
 * 它和超时释放、证据过期扫描之间没有任何关系，混在一起之后
 * 「谁把谁的异常带崩了」会很难查；各自独立，一个出了事不影响另一个。
 *
 * <h2>开关：用 {@code @ConditionalOnProperty}，不用方法里的 if</h2>
 * 与另外两个 Job 保持一致 —— 关掉时这个 Bean 干脆不创建，不留一个
 * 「每分钟醒一次、醒了立刻返回」的空转任务。
 * <b>注意别把 {@code @EnableScheduling} 挪到这个类上</b>：它是全局开关，
 * 跟着开关一起消失会让所有定时任务静默停摆（见启动类上的注释）。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "drone.task.dispatch-block.enabled", havingValue = "true", matchIfMissing = true)
public class TaskDispatchBlockJob {

    @Autowired
    private DispatchBlockService dispatchBlockService;

    /**
     * 每 10 分钟一轮。
     *
     * <p>为什么是 10 分钟而不是更频繁：这是**趋势数据**（按天/周看积压归因），
     * 10 分钟的分辨率已经远超需要；再勤只会让表白涨。
     * 走配置是因为这个频率完全看业务节奏（有人一天派一次活，就没必要 10 分钟一扫）。
     */
    @Scheduled(fixedDelayString = "${drone.task.dispatch-block.interval-ms:600000}")
    public void sample() {
        try {
            dispatchBlockService.samplePendingTasks();
        } catch (Exception e) {
            // 一个采样任务失败不该把调度线程带崩（下次还会跑）
            log.error("待派发归因采样失败，本轮跳过", e);
        }
    }
}
