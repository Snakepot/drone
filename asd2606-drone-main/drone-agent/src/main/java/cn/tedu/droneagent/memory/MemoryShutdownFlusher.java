package cn.tedu.droneagent.memory;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;

import java.util.List;

/**
 * 停机刷盘：进程退出前，把内存里还没落盘的会话同步写进长期记忆。
 *
 * <h2>为什么单独一个类，而不是并进 {@link MemoryArchiver}</h2>
 * 刷盘要知道「内存里还有哪些会话」，得读 {@link SessionStore}；
 * 而 {@code SessionStore} 构造时又需要一个 {@link SessionArchiver}。
 * 两者放在同一个类里就是**构造函数互相依赖**——Spring Boot 默认禁止循环引用，会直接启动失败。
 * 拆出来之后由本类同时持有两者，依赖方向是单向的，环就不存在了。
 *
 * <h2>为什么阶段要比归档器大</h2>
 * Spring 停机时按阶段**从大到小**执行 {@code stop()}。本类阶段更大，
 * 因此**先**跑：等它把该写的都写完，归档器才去关自己的重试调度器。
 * 顺序反过来就会出现「调度器已关、还有活没干」。
 *
 * <h2>它在链路里的位置，以及它不负责什么</h2>
 * <p><b>位置</b>：进程退出前的**最后一道保险**。内存里的会话平时是「满了/闲了」才归档，
 * 没被淘汰的那批本来会直接随进程消失 —— 本类在停机时把它们同步写出去。
 *
 * <ul>
 *   <li><b>自己不写文件</b>：写正文与索引一律转交 {@link MemoryArchiver}，
 *       本类只负责「找出还没落盘的、按什么顺序调」。</li>
 *   <li><b>不处理运行期的过期与淘汰</b>：那是 {@link SessionStore} 在运行中自己触发的，
 *       本类只在停机这一刻介入。</li>
 * </ul>
 */
@Slf4j
public class MemoryShutdownFlusher implements SmartLifecycle {

    /**
     * 停机阶段。大于归档器的默认阶段 0，所以先于归档器停止。
     */
    private static final int SHUTDOWN_PHASE = 1000;

    private final SessionStore sessionStore;

    private final MemoryArchiver archiver;

    private volatile boolean running;

    public MemoryShutdownFlusher(SessionStore sessionStore, MemoryArchiver archiver) {
        this.sessionStore = sessionStore;
        this.archiver = archiver;
    }

    @Override
    public void start() {
        running = true;
    }

    @Override
    public void stop() {
        running = false;

        List<Session> inMemory = sessionStore.allSessions();
        if (!inMemory.isEmpty()) {
            // 与运行期的归档用同一套方向标识，便于把「停机这批」和「平时那批」串起来看
            log.info("【文件←内存】停机刷盘：内存中还有 {} 个会话未落盘，开始同步写出", inMemory.size());
        }
        int failed = archiver.flushSynchronously(inMemory);

        // 先刷盘、再清空：清空走 EXPLICIT，不会又触发一遍归档，避免写出重复的会话段
        sessionStore.removeAll();

        // 之前写失败的尽力补一遍
        archiver.flushPendingRetriesSynchronously();

        if (failed > 0) {
            log.error("停机刷盘结束，有 {} 个会话未能写出，内容已丢失", failed);
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return SHUTDOWN_PHASE;
    }
}
