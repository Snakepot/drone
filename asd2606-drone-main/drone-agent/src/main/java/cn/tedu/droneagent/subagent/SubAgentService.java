package cn.tedu.droneagent.subagent;

import cn.tedu.droneagent.tool.ToolContext;

import java.util.List;
import java.util.Optional;

/**
 * 子 Agent 门面（`REQ-AGENT-0033`）：供代码级路由直接执行子 Agent，
 * 不必再让主模型调用工具。
 */
public class SubAgentService {

    private final SubAgentCatalog catalog;
    private final SubAgentRunner runner;

    public SubAgentService(SubAgentCatalog catalog, SubAgentRunner runner) {
        this.catalog = catalog;
        this.runner = runner;
    }

    public static SubAgentService noop() {
        return new SubAgentService(SubAgentCatalog.empty(), null);
    }

    public boolean enabled() {
        return runner != null && !catalog.all().isEmpty();
    }

    public Optional<SubAgentDefinition> find(String name) {
        if (name != null && !name.isBlank()) {
            return catalog.find(name);
        }
        return defaultAgent();
    }

    public SubAgentResult runDefault(String task, ToolContext context, String traceId) {
        return defaultAgent()
                .map(definition -> runner.run(definition, task, context, traceId))
                .orElseGet(() -> new SubAgentResult("没有可用的子代理。", 0, 0, 0,
                        List.of(), "error"));
    }

    public SubAgentResult run(String name, String task, ToolContext context, String traceId) {
        return find(name)
                .map(definition -> runner.run(definition, task, context, traceId))
                .orElseGet(() -> new SubAgentResult("没有名为「" + name + "」的子代理。",
                        0, 0, 0, List.of(), "error"));
    }

    private Optional<SubAgentDefinition> defaultAgent() {
        Optional<SubAgentDefinition> preferred = catalog.find("ops-analyst");
        return preferred.isPresent() ? preferred : catalog.all().stream().findFirst();
    }
}
