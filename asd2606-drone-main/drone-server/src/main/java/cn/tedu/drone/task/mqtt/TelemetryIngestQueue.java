package cn.tedu.drone.task.mqtt;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 设备消息的<b>缓冲队列 + 消费线程</b>：MQTT 回调只入队，真正的处理在这里慢慢做。
 *
 * <h2>为什么中间要加一道队列（这是整个接入里最关键的一个设计）</h2>
 * Paho 收到消息后是在<b>它自己的网络线程</b>上回调我们的。如果在回调里直接写数据库，
 * 会出两个连锁反应：
 * <ol>
 *   <li>数据库一慢（锁等待、慢查询），回调就卡住 → Paho 的网络线程被占着，
 *       心跳包发不出去 → broker 判定你失联 → <b>连接被断开</b>。
 *       也就是说「数据库慢」会升级成「MQTT 掉线」，故障范围被放大。</li>
 *   <li>就算不断线，回调阻塞也会让 broker 的发送窗口被填满，
 *       后续消息积压在 broker 侧的内存里 —— 把压力推给了别人。</li>
 * </ol>
 * 加一道队列，回调变成「往内存里放一个对象」这种微秒级操作，
 * 网络线程立刻返回。写库的活儿由独立线程做，慢就慢它自己的。
 *
 * <h2>为什么队列必须有界</h2>
 * 如果用无界队列，消费跟不上时消息会一直堆在内存里，
 * 最终 <b>OutOfMemoryError 把整个后端打死</b> —— 一个设备数据接入搞垮整个管理后台，
 * 显然不成比例。有界队列满了就丢，并打警告日志，这叫<b>背压保护</b>：
 * 宁可丢设备数据（它下一帧还会再来），也不能让服务不可用。
 *
 * <p>丢的时候<b>不阻塞、不重试</b>，用的是 {@code offer}（放不进去立刻返回 false），
 * 不是 {@code put}（放不进去就无限等）。这一点和「回调要尽快返回」是同一个目的。
 *
 * <h2>为什么用 ArrayBlockingQueue</h2>
 * 它是有界的阻塞队列里最直白的一个：构造时就要指定容量，
 * 想忘了设上限都难 —— 用 {@code LinkedBlockingQueue} 而忘了传容量的话，
 * 它默认是 {@code Integer.MAX_VALUE}，等于无界，坑就埋下了。
 */
@Slf4j
@Component
public class TelemetryIngestQueue {

    /** 有界队列。容量来自配置，构造后不再变 */
    private final BlockingQueue<DeviceMessage> queue;

    /**
     * 队列容量，单独存一份。
     *
     * <p>{@code queue.size() + queue.remainingCapacity()} 也能算出来，
     * 但容量是构造后就不会变的常量，日志里每次去算两遍没意义 —— 干脆存下来。
     */
    private final int capacity;

    /** 真正的处理逻辑。用接口注入，测试时可以换成一个只记数的假实现 */
    private final TelemetryHandler handler;

    /** 消费线程个数，@PreDestroy 时按这个数量收工 */
    private final int workerThreads;

    private final List<Thread> workers = new ArrayList<>();

    /**
     * 停机标志。用 volatile 是因为它被主线程写、被消费线程读，
     * 不加 volatile 的话消费线程可能一直看到旧值（缓存），醒不过来。
     */
    private volatile boolean running = true;

    /**
     * 累计入队成功条数，日志里报累计量用。
     *
     * <p>写者是 Paho 的回调线程（{@link #submit} 从那里被调用），
     * 读者除了它自己还有停机线程（{@link #stop()} 里汇总一行）——
     * 跨线程读写一个裸 long 不保证可见性，所以用原子类。
     */
    private final AtomicLong enqueuedCount = new AtomicLong();

    /** 累计因队满丢弃条数。这个数字持续增长就说明消费端扛不住了 */
    private final AtomicLong droppedCount = new AtomicLong();

    public TelemetryIngestQueue(MqttProperties properties, TelemetryHandler handler) {
        // 容量至少给 1，防止有人把配置写成 0 或负数导致启动即失败
        this.capacity = Math.max(1, properties.getQueueCapacity());
        this.queue = new ArrayBlockingQueue<>(capacity);
        this.workerThreads = Math.max(1, properties.getWorkerThreads());
        this.handler = handler;
    }

    /**
     * 把消息放进队列。由 MQTT 回调线程调用，<b>必须立刻返回</b>。
     *
     * <p>流程里的第 <b>7.1</b> 步：这一步的调用方是 Paho 的网络线程
     * （第 7 步 {@code messageArrived} 里），而真正取走消息的是下面
     * {@link #consumeLoop()} 里的消费线程。日志里两边的线程名不一样，
     * 那就是「回调线程只负责入队、写库换线程做」的直接证据。
     *
     * @return true 表示已入队；false 表示队满被丢弃
     */
    public boolean submit(DeviceMessage message) {
        if (!running) {
            // 停机过程中可能还有零星回调进来，直接丢掉，避免往一个没人消费的队列里塞
            return false;
        }

        if (queue.offer(message)) {
            long total = enqueuedCount.incrementAndGet();
            // 每条都记，但用 debug 级别：本地开发默认就是 debug（能看到流程），
            // 生产调回 info 后这些逐条日志自动消失，不会把磁盘刷满
            log.debug("【MQTT 7.1 入队】回调线程把消息放进队列：device={}，当前深度 {}/{}，累计入队 {} 条。"
                            + "本线程的活儿到此结束，立刻返回给 Paho",
                    message.deviceCode(), queue.size(), capacity, total);
            return true;
        }

        long dropped = droppedCount.incrementAndGet();
        // 丢弃日志限流：前 3 次必打（让人第一时间发现），之后每 500 次打一次
        if (dropped <= 3 || dropped % 500 == 0) {
            log.warn("【MQTT 7.1 入队】队列已满（容量 {}），本条已丢弃，累计丢弃 {} 条。"
                            + "若持续出现，说明消费端跟不上或数据库变慢，需要扩容或优化消费逻辑",
                    capacity, dropped);
        }
        return false;
    }

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    /**
     * 启动消费线程。
     *
     * <p>放在 {@code @PostConstruct} 而不是构造方法里：构造方法里不应该把线程跑起来 ——
     * 此时这个 Bean 还没「初始化完成」，别的 Bean 也还没准备好，
     * 消费线程过早开始干活可能撞上还没初始化好的依赖。
     *
     * <p>线程名手动指定（{@code drone-mqtt-worker-N}）：线上出问题时
     * {@code jstack} 一看线程名就知道是谁，比默认的 {@code pool-1-thread-3} 有用得多。
     * 另外把它们设为<b>非守护线程</b>是不行的 —— 那样 JVM 不肯退出；
     * 这里保持守护线程 + 优雅停机（见 {@link #stop()}）两手都做。
     */
    @PostConstruct
    public void start() {
        for (int i = 0; i < workerThreads; i++) {
            Thread worker = new Thread(this::consumeLoop, "drone-mqtt-worker-" + i);
            // 设为守护线程只是「最后一道保险」：真正正常的关闭路径是 stop() 里的优雅停机
            worker.setDaemon(true);
            workers.add(worker);
            worker.start();
        }
        log.info("【MQTT 7 前置】设备消息消费线程已启动，数量={}，队列容量={}。"
                        + "这行会出现在活动日志的第 0 步之前 —— 因为队列 Bean 比订阅器先创建，"
                        + "而它是第 7.1 步入队的目标，必须先准备好。"
                        + "第 7.1 步入队发生在 Paho 的回调线程上，第 7.2 步消费发生在这里起的线程上 —— "
                        + "日志里这两步的线程名不同，消息就是这样跨线程交接的",
                workerThreads, capacity);
    }

    /**
     * 优雅停机：先停止收新消息，再把队列里剩下的消费完，最后才让线程退出。
     *
     * <p>顺序很重要。直接 {@code interrupt} 掉会把队列里已经收下的消息丢掉 ——
     * 那些消息我们已经「答应」过 broker 了（QoS 1 的确认是在回调返回后发的），
     * 丢掉就真丢了，broker 不会重发。
     *
     * <p>但也不能无限等：总容量 10000 条、消费一条几毫秒，最多等几秒。
     * 所以给一个上限（{@code drainMillis}），到点还没清完就放弃剩下的。
     * 这是「尽量不丢」和「重启不能卡住」之间的折中。
     */
    @PreDestroy
    public void stop() {
        running = false;
        log.info("【MQTT 7.2 停机】消费线程准备停机，剩余待处理 {} 条", queue.size());

        long deadline = System.currentTimeMillis() + DRAIN_MILLIS;
        while (!queue.isEmpty() && System.currentTimeMillis() < deadline) {
            sleepQuietly(20);
        }
        if (!queue.isEmpty()) {
            log.warn("【MQTT 7.2 停机】等待 {} ms 后仍有 {} 条未处理，将放弃（这些消息不会重发）",
                    DRAIN_MILLIS, queue.size());
        }

        // 队列里的活儿处理完了，再叫醒可能还在 take() 上等着的线程
        for (Thread worker : workers) {
            worker.interrupt();
        }
        for (Thread worker : workers) {
            try {
                worker.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        log.info("【MQTT 7.2 停机】消费线程已停止，累计入队 {} 条，累计丢弃 {} 条",
                enqueuedCount.get(), droppedCount.get());
    }

    /** 停机时最多等这么久把队列清空 */
    private static final long DRAIN_MILLIS = 3000;

    // ------------------------------------------------------------------
    // 内部方法
    // ------------------------------------------------------------------

    /**
     * 消费循环：取一条、处理一条，如此往复。
     *
     * <p>{@code take()} 在队列空时会阻塞（不用空转轮询，不浪费 CPU），
     * 直到有新消息、或者线程被 {@code interrupt} —— 后者会抛
     * {@link InterruptedException}，我们据此退出循环。这是标准的
     * 「阻塞 + 可中断退出」写法。
     */
    private void consumeLoop() {
        while (running || !queue.isEmpty()) {
            DeviceMessage message;
            try {
                message = queue.take();
            } catch (InterruptedException e) {
                // 停机时被 interrupt 是正常路径；恢复中断标记是规范动作
                Thread.currentThread().interrupt();
                return;
            }

            try {
                log.debug("【MQTT 7.2 消费】消费线程取出消息：device={}，队列还剩 {} 条。"
                                + "注意本行日志的线程名和第 7.1 步不一样：消息已经从回调线程交到消费线程手上了",
                        message.deviceCode(), queue.size());
                handler.handle(message);
            } catch (Exception e) {
                // 一条消息处理失败不能把线程带走 —— 线程死了，后面的消息全都没人处理了，
                // 而队列还在继续收，等于悄悄「假死」。这是最常见的一类生产事故。
                log.error("【MQTT 7.2 消费】处理设备消息失败，topic={}，device={}，该条已跳过",
                        message.topic(), message.deviceCode(), e);
            }
        }
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
