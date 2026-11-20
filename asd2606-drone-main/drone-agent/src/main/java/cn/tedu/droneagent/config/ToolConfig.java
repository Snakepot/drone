package cn.tedu.droneagent.config;

import cn.tedu.droneagent.mcp.McpToolset;
import cn.tedu.droneagent.service.ChatService;
import cn.tedu.droneagent.service.ToolCallingService;
import cn.tedu.droneagent.tool.PendingActionStore;
import cn.tedu.droneagent.tool.ResolvedToolCatalog;
import cn.tedu.droneagent.tool.Tool;
import cn.tedu.droneagent.tool.ToolAuditLog;
import cn.tedu.droneagent.tool.ToolDescriptor;
import cn.tedu.droneagent.tool.ToolGovernance;
import cn.tedu.droneagent.journal.WriteJournal;
import cn.tedu.droneagent.tool.ToolRegistry;
import cn.tedu.droneagent.tool.ToolResultCompressor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 工具调用子系统的装配。
 *
 * <h2>为什么单开一个配置类</h2>
 * 与 {@link MemoryConfig} 同理：这些类的构造依赖是**显式拼**出来的，
 * 集中在这里一眼看清依赖方向，测试也能照同样顺序手工拼。
 *
 * <h2>工具来自两处，必须合并成一份</h2>
 * <ol>
 *   <li><b>本地工具</b>：实现了 {@link Tool} 的 Spring Bean（如知识库检索），
 *       元数据来自 {@code agent.tool.catalog}；</li>
 *   <li><b>远程 MCP 工具</b>：{@link McpToolset} 启动时发现，行为与元数据**成对**产出。</li>
 * </ol>
 * {@link ToolRegistry} 要求两者严格 1:1，所以这里先合并成 {@link ResolvedToolCatalog}，
 * 再让注册表与治理共用同一份——**不要各自拿一半**，否则会出现「注册表见过的工具，
 * 治理层没见过」这类只在运行时才暴露的问题。
 *
 * <h2>为什么 MCP 适配器不作为 Bean</h2>
 * 若把每个 MCP 工具也注册成 {@code Tool} Bean，Spring 注入的 {@code List<Tool>} 里就会同时出现
 * 「本地工具」和「MCP 工具」，这里再合并一次就重名、启动失败。
 * 所以 MCP 工具**只从 {@link McpToolset} 显式取**，不进容器。
 */
@Configuration
public class ToolConfig {

    /** 待确认动作的有效期（`REQ-AGENT-0006` 定的 5 分钟） */
    private static final Duration PENDING_TTL = Duration.ofMinutes(5);

    /**
     * 把本地工具与 MCP 工具合并成一份「行为 + 元数据」的快照。
     *
     * <p>两个列表**同序、等长**：第 N 个行为对应第 N 条元数据。虽然 {@code ToolRegistry}
     * 是按名字匹配的，保持同序能让日志与排障更直观。
     */
    @Bean
    public ResolvedToolCatalog resolvedToolCatalog(List<Tool> localTools, McpToolset mcpToolset,
                                                   ToolProperties properties) {
        List<Tool> tools = new ArrayList<>(localTools);
        List<ToolDescriptor> catalog = new ArrayList<>(
                properties.catalog() == null ? List.of() : properties.catalog());

        tools.addAll(mcpToolset.tools());
        catalog.addAll(mcpToolset.descriptors());

        return new ResolvedToolCatalog(List.copyOf(tools), List.copyOf(catalog));
    }

    /**
     * 工具注册处。把合并后的**行为**与**元数据**按名字校验；对不上会直接让服务启动失败。
     */
    @Bean
    public ToolRegistry toolRegistry(ResolvedToolCatalog catalog, ObjectMapper objectMapper) {
        return new ToolRegistry(catalog.tools(), catalog.descriptors(), objectMapper);
    }

    /**
     * 跑工具用的线程池。
     *
     * <p>工具可能慢（外部接口），放到独立线程池里跑，请求线程只等结果；
     * 并发上限由 {@code concurrency} 给，**不跟着请求量无限涨**。停机交给 Spring（{@code shutdown}）。
     *
     * <p>注意：MCP 调用也跑在这个池里，日志中的线程名会是 {@code tool-invoke-*}，
     * 与请求线程（{@code http-nio-*}）区分开。
     */
    @Bean(destroyMethod = "shutdown")
    public ExecutorService toolExecutor(ToolProperties properties) {
        AtomicInteger sequence = new AtomicInteger();
        return Executors.newFixedThreadPool(properties.concurrency(), runnable -> {
            Thread thread = new Thread(runnable, "tool-invoke-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * 工具结果的**压缩器**：结构化裁剪之后仍超长时，用 {@link ChatService#summarize} 让模型压一遍
     * （`REQ-AGENT-0007` 的「分级压缩」）。
     */
    @Bean
    public ToolResultCompressor toolResultCompressor(ChatService chatService) {
        return new ToolResultCompressor((content, maxChars) -> chatService.summarize(content, maxChars));
    }

    /**
     * 危险工具治理：谁是危险工具、谁有权用。
     *
     * <p>用**合并后的** catalog 校验，否则 MCP 工具名会因「不在 agent.tool.catalog 中」被判为配置错误。
     */
    @Bean
    public ToolGovernance toolGovernance(ResolvedToolCatalog catalog, ToolProperties properties) {
        return new ToolGovernance(catalog.descriptors(), properties.dangerous(), properties.roleTools());
    }

    /**
     * 待确认动作的存储：内存 + 文件持久化（REQ-AGENT-0029）。
     *
     * <p>`pending-root` 为空时退回只存内存（既有测试/不需要重启恢复的场景）。
     */
    @Bean
    public PendingActionStore pendingActionStore(ToolProperties properties, ObjectMapper objectMapper) {
        Duration ttl = properties.pendingTtl() == null ? PENDING_TTL : properties.pendingTtl();
        String root = properties.pendingRoot();
        Path path = root == null || root.isBlank() ? null : Path.of(root);
        return new PendingActionStore(ttl, path, path == null ? null : objectMapper);
    }

    /** 写操作台账（REQ-AGENT-0029）：operationId / 工具 / 状态 / 对象 ID */
    @Bean
    public WriteJournal writeJournal(ToolProperties properties, ObjectMapper objectMapper) {
        String root = properties.operationRoot();
        return root == null || root.isBlank()
                ? WriteJournal.noop() : new WriteJournal(Path.of(root), objectMapper);
    }

    /** 危险操作执行的审计：写在与记忆同一个数据根目录下（{@code <root>/tool-audit.log}） */
    @Bean
    public ToolAuditLog toolAuditLog(MemoryProperties memory) {
        return new ToolAuditLog(Path.of(memory.root()));
    }

    /** 编排器：只依赖注册表，不关心工具来自本地还是 MCP */
    @Bean
    public ToolCallingService toolCallingService(ChatService chatService, ToolRegistry registry,
                                                 ExecutorService toolExecutor, ToolResultCompressor compressor,
                                                 ToolGovernance governance, PendingActionStore pendingStore,
                                                 ToolAuditLog auditLog, ToolProperties properties,
                                                 cn.tedu.droneagent.trace.TraceRecorder traceRecorder,
                                                 cn.tedu.droneagent.budget.BudgetService budgetService,
                                                 WriteJournal writeJournal,
                                                 cn.tedu.droneagent.plan.PlanService planService,
                                                 cn.tedu.droneagent.route.TaskRouter taskRouter,
                                                 cn.tedu.droneagent.subagent.SubAgentService subAgentService) {
        return new ToolCallingService(chatService, registry, toolExecutor, compressor, governance,
                pendingStore, auditLog, properties, traceRecorder, budgetService, writeJournal,
                planService, taskRouter, subAgentService);
    }
}
