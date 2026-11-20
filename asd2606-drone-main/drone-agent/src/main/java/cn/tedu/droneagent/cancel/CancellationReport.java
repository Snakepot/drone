package cn.tedu.droneagent.cancel;

import java.util.List;

/**
 * 取消后的部分完成报告（`REQ-AGENT-0029`）。
 *
 * <p>取消**不自动回滚**：报告只如实说明哪些写操作已完成、哪些未开始、哪些结果未知，
 * 并把可执行的后续选项交给用户。
 *
 * @param cancelled  是否因取消而结束
 * @param completed  已成功的写步骤
 * @param failed     明确失败的写步骤（业务错误，确定未生效）
 * @param notStarted 未开始的写步骤（取消时还没执行的那些）
 * @param unknown    结果未知的写步骤（超时/5xx/网络异常）
 * @param options    continue / revoke / keep
 * @param resumeToken 续跑所需（已完成写步骤的 operationId + 捕获的对象 ID）
 */
public record CancellationReport(
        boolean cancelled,
        List<Step> completed,
        List<Step> failed,
        List<Step> notStarted,
        List<Step> unknown,
        List<String> options,
        ResumeToken resumeToken
) {

    public CancellationReport {
        completed = List.copyOf(completed);
        failed = List.copyOf(failed);
        notStarted = List.copyOf(notStarted);
        unknown = List.copyOf(unknown);
        options = List.copyOf(options);
    }

    /** 一个写步骤 */
    public record Step(String operationId, String tool, String status, String objectId,
                       String summary) {
    }

    /**
     * 续跑令牌。
     *
     * <p>用**列表**而不是「工具名 → 值」的 Map：同一请求里同一种写工具可能被调用多次
     * （例如批量建 3 个用户），Map 会让后一次覆盖前一次，续跑与撤销都会丢步骤。
     *
     * @param operations 已完成的写操作；续跑时按「工具 + 参数摘要」找回同一个 operationId
     * @param captured   已捕获对象 ID 的写操作，**顺序即完成顺序**（撤销要反着来）
     */
    public record ResumeToken(List<Operation> operations, List<Captured> captured) {
        public ResumeToken {
            operations = operations == null ? List.of() : List.copyOf(operations);
            captured = captured == null ? List.of() : List.copyOf(captured);
        }
    }

    /**
     * 一次已完成的写操作。
     *
     * @param argsDigest 参数摘要；续跑时模型多半会重发同一组参数，用它把步骤对回来
     */
    public record Operation(String tool, String operationId, String argsDigest) {
    }

    /** 一次已捕获对象 ID 的写操作 */
    public record Captured(String tool, String objectId, String operationId) {
    }
}
