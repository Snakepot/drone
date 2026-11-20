package cn.tedu.droneagent.dto;

/** 计划审批请求（`REQ-AGENT-0031`）：批准或拒绝一份生成计划。 */
public record PlanConfirmRequest(String conversationId, String planId, Boolean approve) {
}
