package cn.tedu.droneagent.config;

import cn.tedu.droneagent.plan.PlanCatalog;
import cn.tedu.droneagent.plan.PlanExecutor;
import cn.tedu.droneagent.plan.PlanPlanner;
import cn.tedu.droneagent.plan.PlanService;
import cn.tedu.droneagent.plan.PlanStore;
import cn.tedu.droneagent.prompt.PromptCatalog;
import cn.tedu.droneagent.service.ChatService;
import cn.tedu.droneagent.skill.SkillCatalog;
import cn.tedu.droneagent.tool.ToolGovernance;
import cn.tedu.droneagent.tool.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.ExecutorService;

/** Plan 模式装配（`REQ-AGENT-0031`）。 */
@Configuration
public class PlanConfig {

    private static final String PLAN_PATTERN = "classpath*:plans/*.json";

    @Bean
    public PlanCatalog planCatalog(ToolRegistry registry, SkillCatalog skills,
                                   ObjectMapper objectMapper) {
        return PlanCatalog.load(PLAN_PATTERN, registry, skills, objectMapper);
    }

    @Bean
    public PlanStore planStore(PlanProperties properties, ObjectMapper objectMapper) {
        Duration ttl = properties.ttl() == null ? Duration.ofMinutes(30) : properties.ttl();
        String root = properties.root();
        Path path = root == null || root.isBlank() ? null : Path.of(root);
        return new PlanStore(ttl, path, path == null ? null : objectMapper);
    }

    @Bean
    public PlanExecutor planExecutor(ChatService chatService, SkillCatalog skills,
                                     ToolRegistry registry, ToolGovernance governance,
                                     ObjectMapper objectMapper, ExecutorService toolExecutor,
                                     PlanProperties properties,
                                     cn.tedu.droneagent.journal.WriteJournal writeJournal) {
        return new PlanExecutor(chatService, skills, registry, governance, objectMapper,
                toolExecutor, Math.max(1, properties.parallel()), writeJournal);
    }

    @Bean
    public PlanPlanner planPlanner(ChatService chatService, ToolRegistry registry,
                                   ToolGovernance governance, PlanCatalog catalog,
                                   PromptCatalog prompts, ObjectMapper objectMapper) {
        return new PlanPlanner(chatService, registry, governance, catalog, prompts, objectMapper);
    }

    @Bean
    public PlanService planService(PlanProperties properties, PlanCatalog catalog,
                                   PlanStore store, PlanPlanner planner, PlanExecutor executor) {
        if (!properties.enabled()) {
            return PlanService.noop();
        }
        return new PlanService(catalog, store, planner, executor);
    }
}
