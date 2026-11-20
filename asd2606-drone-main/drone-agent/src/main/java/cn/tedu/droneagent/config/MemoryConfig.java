package cn.tedu.droneagent.config;

import cn.tedu.droneagent.memory.ContextAssembler;
import cn.tedu.droneagent.memory.ConversationCompactor;
import cn.tedu.droneagent.memory.LongTermMemoryStore;
import cn.tedu.droneagent.memory.LongTermSessionLoader;
import cn.tedu.droneagent.memory.MemoryArchiver;
import cn.tedu.droneagent.memory.MemoryAuditLog;
import cn.tedu.droneagent.memory.MemoryIndex;
import cn.tedu.droneagent.memory.MemorySearchTool;
import cn.tedu.droneagent.memory.MemoryShutdownFlusher;
import cn.tedu.droneagent.memory.SessionLocks;
import cn.tedu.droneagent.memory.SessionStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 记忆子系统的装配。
 *
 * <h2>为什么这些类不在自己头上加 {@code @Component}</h2>
 * 它们的构造依赖是显式的、需要按顺序拼的（执行器、加载器、归档器…），
 * 分散在各类里加注解会让「谁依赖谁」变得难以一眼看清；
 * 集中在这里装配，依赖方向一目了然，测试里也能用同样的顺序手工拼出一套。
 *
 * <h2>归档执行器为什么要显式给</h2>
 * 缓存的移除回调默认会跑在 JVM 共享的 {@code ForkJoinPool.commonPool} 上，
 * 而回调里要写文件——在共享池里做 IO 会拖累整个进程。
 * 这里给它一个专属的小线程池，并把它的停机交给 Spring。
 */
@Configuration
public class MemoryConfig {

    /**
     * 归档专用线程池。
     *
     * <p>2 个线程足够：归档是「攒一批慢慢写」的活，不是高并发场景；
     * 线程数不跟着请求量涨，反而能在磁盘变慢时形成天然背压。
     */
    @Bean(destroyMethod = "shutdown")
    public ExecutorService memoryArchivingExecutor() {
        AtomicInteger sequence = new AtomicInteger();
        return Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "memory-archive-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    @Bean
    public MemoryIndex memoryIndex(MemoryProperties properties) {
        return new MemoryIndex(Path.of(properties.root()));
    }

    @Bean
    public LongTermMemoryStore longTermMemoryStore(MemoryProperties properties) {
        return new LongTermMemoryStore(Path.of(properties.root()), properties.fileMaxBytes());
    }

    @Bean
    public MemoryArchiver memoryArchiver(LongTermMemoryStore longTermMemoryStore, MemoryIndex memoryIndex) {
        return new MemoryArchiver(longTermMemoryStore, memoryIndex);
    }

    @Bean
    public SessionStore sessionStore(MemoryProperties properties, MemoryIndex memoryIndex,
                                     LongTermMemoryStore longTermMemoryStore, MemoryArchiver memoryArchiver,
                                     Executor memoryArchivingExecutor) {
        return new SessionStore(properties,
                new LongTermSessionLoader(memoryIndex, longTermMemoryStore),
                memoryArchiver,
                memoryArchivingExecutor);
    }

    @Bean
    public ContextAssembler contextAssembler(SessionStore sessionStore, MemoryIndex memoryIndex,
                                             LongTermMemoryStore longTermMemoryStore,
                                             MemoryProperties properties,
                                             ConversationCompactor compactor) {
        return new ContextAssembler(sessionStore, memoryIndex, longTermMemoryStore, properties, compactor);
    }

    /** 停机刷盘。它是 {@code SmartLifecycle}，阶段比归档器大，停机时先跑 */
    @Bean
    public MemoryShutdownFlusher memoryShutdownFlusher(SessionStore sessionStore, MemoryArchiver memoryArchiver) {
        return new MemoryShutdownFlusher(sessionStore, memoryArchiver);
    }

    @Bean
    public MemoryAuditLog memoryAuditLog(MemoryProperties properties) {
        return new MemoryAuditLog(Path.of(properties.root()));
    }

    /** 长期记忆的按需检索工具（`REQ-AGENT-0021`）：模型调用时才读归档，不再每轮注入 */
    @Bean
    public MemorySearchTool memorySearchTool(LongTermMemoryStore longTermMemoryStore) {
        return new MemorySearchTool(longTermMemoryStore);
    }

    @Bean
    public SessionLocks sessionLocks() {
        return new SessionLocks();
    }
}
