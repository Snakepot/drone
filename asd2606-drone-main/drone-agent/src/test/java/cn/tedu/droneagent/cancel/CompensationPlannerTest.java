package cn.tedu.droneagent.cancel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** 撤销计划（REQ-AGENT-0029）：逆序、按映射、未知写操作转人工。 */
class CompensationPlannerTest {

    @Test
    @DisplayName("用户 + 设备：逆序补偿，设备先删、用户后停用")
    void plansInReverseOrder() {
        List<CancellationReport.Captured> captured = List.of(
                new CancellationReport.Captured("drone__user_add", "123", "op-1"),
                new CancellationReport.Captured("drone__device_add", "D1", "op-2"));

        List<CompensationPlanner.Action> actions = CompensationPlanner.plan(captured);

        assertEquals(2, actions.size());
        assertEquals("drone__device_add", actions.get(0).sourceTool());
        assertEquals("drone__device_delete", actions.get(0).tool());
        assertEquals("{\"id\":D1}", actions.get(0).argsJson());
        assertEquals("drone__user_add", actions.get(1).sourceTool());
        assertEquals("drone__user_toggle", actions.get(1).tool());
    }

    @Test
    @DisplayName("没有映射的写操作：不给自动方案，交人工")
    void unknownToolNeedsManual() {
        List<CompensationPlanner.Action> actions = CompensationPlanner.plan(List.of(
                new CancellationReport.Captured("drone__task_finish", "9", "op-3")));

        assertEquals(1, actions.size());
        assertNull(actions.get(0).tool());
        assertEquals("该写操作没有自动补偿方案，需人工处理", actions.get(0).note());
    }

    @Test
    @DisplayName("ResumeToken 保留完成顺序：复制后仍能逆序补偿")
    void resumeTokenKeepsCompletionOrder() {
        List<CancellationReport.Operation> operations = List.of(
                new CancellationReport.Operation("drone__user_add", "op-1", "digest-1"),
                new CancellationReport.Operation("drone__device_add", "op-2", "digest-2"));
        List<CancellationReport.Captured> captured = List.of(
                new CancellationReport.Captured("drone__user_add", "123", "op-1"),
                new CancellationReport.Captured("drone__device_add", "D1", "op-2"));

        CancellationReport.ResumeToken token =
                new CancellationReport.ResumeToken(operations, captured);
        List<CompensationPlanner.Action> actions = CompensationPlanner.plan(token.captured());

        assertEquals("drone__device_add", actions.get(0).sourceTool(),
                "逆序补偿依赖 captured 的完成顺序，复制 token 时不能被重新排序");
        assertEquals("drone__user_add", actions.get(1).sourceTool());
    }
}
