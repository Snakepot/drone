package cn.tedu.droneagent.subagent;

import java.util.List;

/** 子 Agent 的返回：只返回摘要与使用情况，不返回大段原始数据。 */
public record SubAgentResult(String summary, int rounds, int toolCalls, long elapsedMs,
                             List<String> toolsUsed, String status) {

    public SubAgentResult {
        toolsUsed = toolsUsed == null ? List.of() : List.copyOf(toolsUsed);
    }
}
