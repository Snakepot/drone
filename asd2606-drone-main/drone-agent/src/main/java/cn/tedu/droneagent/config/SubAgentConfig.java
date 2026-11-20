package cn.tedu.droneagent.config;

import cn.tedu.droneagent.budget.BudgetService;
import cn.tedu.droneagent.service.ChatService;
import cn.tedu.droneagent.subagent.SubAgentCatalog;
import cn.tedu.droneagent.subagent.SubAgentRunner;
import cn.tedu.droneagent.subagent.SubAgentService;
import cn.tedu.droneagent.tool.ToolGovernance;
import cn.tedu.droneagent.tool.ToolRegistry;
import cn.tedu.droneagent.trace.TraceRecorder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;

/** 子 Agent 装配（`REQ-AGENT-0032`）。 */
@Configuration
public class SubAgentConfig {

    private static final String SUBAGENT_PATTERN = "classpath*:subagents/*.md";

    @Bean
    public SubAgentCatalog subAgentCatalog() {
        return SubAgentCatalog.load(SUBAGENT_PATTERN);
    }

    @Bean
    public SubAgentRunner subAgentRunner(ChatService chatService,
                                         ObjectProvider<ToolRegistry> registry,
                                         ObjectProvider<ToolGovernance> governance,
                                         TraceRecorder traceRecorder,
                                         BudgetService budgetService,
                                         SubAgentProperties properties,
                                         ExecutorService toolExecutor) {
        return new SubAgentRunner(chatService, registry::getObject, governance::getObject,
                traceRecorder, budgetService, properties, toolExecutor);
    }

    @Bean
    public SubAgentService subAgentService(SubAgentCatalog catalog, SubAgentRunner runner) {
        return new SubAgentService(catalog, runner);
    }
}
