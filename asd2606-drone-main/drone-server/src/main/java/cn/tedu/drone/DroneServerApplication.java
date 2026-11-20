package cn.tedu.drone;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 启动类。
 *
 * <p>{@code @MapperScan} 是<b>逐个列出 DAO 包</b>的，不是扫全部子包 ——
 * 所以新增一个模块（比如 route）时，忘了在这里补一行，
 * 表现是启动时报「No qualifying bean of type ...Dao」。
 * 加这一行的成本很低，代价是「加了新模块要记得改这里」，
 * 而好处是 DAO 的边界始终是显式的：哪几个包里有 Mapper，看这一行就知道。
 *
 * <h2>为什么 {@code @EnableScheduling} 在这里，而不是某个 Job 上</h2>
 * 这个注解是<b>全局开关</b>：一处没有，所有 {@code @Scheduled} 都不生效。
 * 它原先挂在 {@link cn.tedu.drone.task.job.TaskAutoReleaseJob} 上，而那个类带着
 * {@code @ConditionalOnProperty(drone.task.auto-release.enabled)} ——
 * 于是「关掉派发超时释放」会<b>连带关掉别的定时任务</b>，
 * 而且完全不报错：新加的任务一个点都不攒，日志里也看不出所以然
 * （加待派发归因时就是这么被坑的，见 docs/业务分析方案.md 2.7）。
 * 所以它必须留在<b>应用</b>这一层：谁想关自己，用自己那个 {@code @ConditionalOnProperty} 关自己。
 */
@SpringBootApplication
@EnableScheduling
@MapperScan({
        "cn.tedu.drone.user.dao",
        "cn.tedu.drone.device.dao",
        "cn.tedu.drone.task.dao",
        "cn.tedu.drone.route.dao",
        "cn.tedu.drone.zone.dao",
        "cn.tedu.drone.analytics.dao"
})
public class DroneServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(DroneServerApplication.class, args);
    }
}
