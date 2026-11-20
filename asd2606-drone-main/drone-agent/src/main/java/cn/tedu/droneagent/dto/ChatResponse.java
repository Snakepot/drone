package cn.tedu.droneagent.dto;

import cn.tedu.droneagent.artifact.ArtifactRef;
import cn.tedu.droneagent.plan.PlanSummary;

import java.util.List;

/**
 * 对话响应。
 *
 * @param reply     模型回复的正文（已去掉被抽取的 HTML 源码）
 * @param pending   需要人工确认的危险操作（`REQ-AGENT-0006`）；无需确认时为 null
 * @param artifacts 本次生成的 HTML 产物；没有则为空列表
 * @param traceId   本次对话的追踪 id（`REQ-AGENT-0026`）；前端与日志用它串起同一条链路
 * @param plan      计划摘要（`REQ-AGENT-0031`）；没有走计划时为 null
 */
public record ChatResponse(String reply, PendingConfirmation pending, List<ArtifactRef> artifacts,
                           String traceId, PlanSummary plan) {

    public ChatResponse {
        artifacts = artifacts == null ? List.of() : List.copyOf(artifacts);
    }

    /** 兼容既有调用：没有计划 */
    public ChatResponse(String reply, PendingConfirmation pending, List<ArtifactRef> artifacts,
                        String traceId) {
        this(reply, pending, artifacts, traceId, null);
    }

    /** 兼容既有调用：没有产物、没有 traceId、没有计划 */
    public ChatResponse(String reply, PendingConfirmation pending, List<ArtifactRef> artifacts) {
        this(reply, pending, artifacts, null, null);
    }

    /** 兼容既有调用：没有产物、没有 traceId */
    public ChatResponse(String reply, PendingConfirmation pending) {
        this(reply, pending, List.of(), null, null);
    }
}
