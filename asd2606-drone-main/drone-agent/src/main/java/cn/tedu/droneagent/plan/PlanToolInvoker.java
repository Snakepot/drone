package cn.tedu.droneagent.plan;

import cn.tedu.droneagent.tool.ToolCall;
import cn.tedu.droneagent.tool.ToolContext;
import cn.tedu.droneagent.tool.ToolResult;

/**
 * 计划执行器回调：由 {@code ToolCallingService} 提供，复用它的治理、追踪与执行线程。
 */
@FunctionalInterface
public interface PlanToolInvoker {

    ToolResult invoke(ToolCall call, ToolContext context, String operationId);
}
