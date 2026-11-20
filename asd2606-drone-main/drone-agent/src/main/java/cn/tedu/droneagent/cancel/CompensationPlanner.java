package cn.tedu.droneagent.cancel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 撤销计划（`REQ-AGENT-0029`）：把「已完成的写操作」翻译成**逆序**的补偿动作。
 *
 * <p>只做「建议计划」，不直接执行：补偿多为危险操作，必须由模型先查询确认、
 * 再走人工确认流程。映射放在代码里而不是猜，避免模型自由发挥删错对象。
 *
 * <p>未覆盖的写操作标记「需人工处理」，不猜补偿方式。
 */
public final class CompensationPlanner {

    /** 写工具 → 补偿工具 + 备注 */
    private static final Map<String, Rule> RULES = Map.of(
            "drone__user_add", new Rule("drone__user_toggle", "停用用户（优先停用，不直接删除）"),
            "drone__device_add", new Rule("drone__device_delete", "删除设备为危险操作，需确认"),
            "drone__route_add", new Rule("drone__route_delete", "删除航线为危险操作，需确认"),
            "drone__point_add", new Rule("drone__point_delete", "删除点位为危险操作，需确认"),
            "drone__zone_add", new Rule("drone__zone_delete", "删除禁飞区为危险操作，需确认"),
            "drone__task_create", new Rule("drone__task_withdraw", "撤回任务为危险操作，需确认")
    );

    private CompensationPlanner() {
    }

    /**
     * 生成逆序补偿计划。
     *
     * @param captured 已捕获对象 ID 的写操作，**按完成顺序**传入
     */
    public static List<Action> plan(List<CancellationReport.Captured> captured) {
        List<Action> actions = new ArrayList<>(captured.size());
        for (int i = captured.size() - 1; i >= 0; i--) {
            CancellationReport.Captured step = captured.get(i);
            Rule rule = RULES.get(step.tool());
            if (rule == null) {
                actions.add(new Action(step.tool(), null, null, "该写操作没有自动补偿方案，需人工处理"));
            } else {
                actions.add(new Action(step.tool(), rule.tool(),
                        "{\"id\":" + step.objectId() + "}", rule.note()));
            }
        }
        return actions;
    }

    /**
     * @param sourceTool 原写工具
     * @param tool       补偿工具；null 表示无自动方案
     * @param argsJson   补偿参数（按对象 ID 生成）
     * @param note       给模型/用户的说明
     */
    public record Action(String sourceTool, String tool, String argsJson, String note) {
    }

    private record Rule(String tool, String note) {
    }
}
