package cn.tedu.droneagent.subagent;

import java.util.List;

/**
 * 一个子 Agent 的定义（`REQ-AGENT-0032`）。
 *
 * @param name           子 Agent 名（如 ops-analyst）
 * @param description    给主 Agent 看的用途说明
 * @param tools          工具白名单；P0 只允许只读工具
 * @param maxRounds      最多几轮模型/工具循环
 * @param timeoutSeconds 整个子 Agent 的超时
 * @param readOnly       P0 必须为 true
 * @param prompt         正文即系统提示词（Markdown 已 strip）
 */
public record SubAgentDefinition(
        String name,
        String description,
        List<String> tools,
        int maxRounds,
        long timeoutSeconds,
        boolean readOnly,
        String prompt
) {

    public SubAgentDefinition {
        tools = tools == null ? List.of() : List.copyOf(tools);
    }
}
