package cn.tedu.droneagent.config;

import cn.tedu.droneagent.plan.PlanCatalog;
import cn.tedu.droneagent.route.TaskRouter;
import cn.tedu.droneagent.subagent.SubAgentService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 任务路由装配（`REQ-AGENT-0033`）。 */
@Configuration
public class RouteConfig {

    @Bean
    public TaskRouter taskRouter(RouteProperties properties, PlanCatalog plans,
                                 SubAgentService subAgents) {
        if (!properties.enabled()) {
            return TaskRouter.disabled();
        }
        return new TaskRouter(plans, subAgents.enabled());
    }
}
