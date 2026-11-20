package cn.tedu.droneagent.plan;

import cn.tedu.droneagent.context.ContextMessage;
import cn.tedu.droneagent.context.MessageKind;
import cn.tedu.droneagent.dto.ChatMessage;
import cn.tedu.droneagent.memory.SessionKey;
import cn.tedu.droneagent.prompt.PromptCatalog;
import cn.tedu.droneagent.service.ChatService;
import cn.tedu.droneagent.tool.ToolContext;
import cn.tedu.droneagent.tool.ToolDefinition;
import cn.tedu.droneagent.tool.ToolGovernance;
import cn.tedu.droneagent.tool.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 生成计划（`REQ-AGENT-0031`）：让模型把复杂任务拆成 JSON 步骤。
 *
 * <p>只负责“生成 + 解析 + 基本校验”；是否审批、何时执行由 {@link PlanService} 决定。
 */
@Slf4j
public class PlanPlanner {

    private final ChatService chatService;
    private final ToolRegistry registry;
    private final ToolGovernance governance;
    private final PlanCatalog catalog;
    private final PromptCatalog prompts;
    private final ObjectMapper mapper;

    public PlanPlanner(ChatService chatService, ToolRegistry registry, ToolGovernance governance,
                       PlanCatalog catalog, PromptCatalog prompts, ObjectMapper mapper) {
        this.chatService = chatService;
        this.registry = registry;
        this.governance = governance;
        this.catalog = catalog;
        this.prompts = prompts;
        this.mapper = mapper;
    }

    /**
     * 根据用户请求生成计划。
     *
     * @throws IllegalArgumentException 模型输出不是合法计划
     */
    public Plan generate(SessionKey key, String userMessage, ToolContext context, String traceId) {
        String toolText = toolText(context);
        String system = prompts.render("plan.planner", Map.of("tools", toolText));
        List<ContextMessage> messages = List.of(
                ContextMessage.of(new ChatMessage("system", system), MessageKind.SYSTEM),
                ContextMessage.of(new ChatMessage("user", userMessage), MessageKind.SESSION));

        String raw = chatService.chat(key, messages, context.permissions(), traceId);
        Plan parsed = parse(raw);
        Plan plan = new Plan(
                "plan-" + UUID.randomUUID(),
                parsed.name() == null || parsed.name().isBlank() ? "generated-plan" : parsed.name(),
                Plan.Origin.GENERATED,
                parsed.version() <= 0 ? 1 : parsed.version(),
                parsed.goal(),
                userMessage,
                List.of(),
                parsed.steps());
        Plan validated = catalog.validate(plan, Plan.Origin.GENERATED);
        for (PlanStep step : validated.steps()) {
            if (!step.toolStep()) {
                continue;
            }
            if (!governance.allows(step.tool(), context.roles())) {
                throw new IllegalArgumentException("计划引用了当前角色无权使用的工具："
                        + step.tool());
            }
            if (governance.isDangerous(step.tool())) {
                throw new IllegalArgumentException("计划引用了需要人工确认的危险工具："
                        + step.tool());
            }
        }
        log.info("【计划】已生成：planId={}，name={}，步骤 {} 个",
                validated.planId(), validated.name(), validated.steps().size());
        return validated;
    }

    private Plan parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("模型没有返回计划");
        }
        String text = raw.strip();
        if (text.startsWith("```")) {
            int firstLine = text.indexOf('\n');
            int lastFence = text.lastIndexOf("```");
            if (firstLine >= 0 && lastFence > firstLine) {
                text = text.substring(firstLine + 1, lastFence).strip();
            }
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("模型输出不是 JSON 对象");
        }
        try {
            return mapper.readValue(text.substring(start, end + 1), Plan.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("计划 JSON 解析失败：" + e.getMessage(), e);
        }
    }

    private String toolText(ToolContext context) {
        StringBuilder text = new StringBuilder();
        List<ToolDefinition> definitions = new ArrayList<>(registry.definitions());
        for (ToolDefinition definition : definitions) {
            if (!governance.allows(definition.name(), context.roles())
                    || governance.isDangerous(definition.name())) {
                continue;
            }
            text.append("- ").append(definition.name()).append("：")
                    .append(definition.description()).append("\n");
            if (definition.parametersJson() != null && !definition.parametersJson().isBlank()) {
                text.append("  参数：").append(definition.parametersJson()).append("\n");
            }
        }
        return text.toString();
    }
}
