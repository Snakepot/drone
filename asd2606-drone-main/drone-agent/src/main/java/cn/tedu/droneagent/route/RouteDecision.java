package cn.tedu.droneagent.route;

/**
 * 一次路由决策。
 *
 * @param route          选中的编排路径
 * @param agent          SUBAGENT 时使用的子 Agent 名；其他为 null
 * @param reason         决策原因（日志/排障用）
 * @param planScore      Plan 得分
 * @param subAgentScore  SubAgent 得分
 * @param reactScore     ReAct 得分
 */
public record RouteDecision(TaskRoute route, String agent, String reason,
                            int planScore, int subAgentScore, int reactScore) {

    public static RouteDecision react(String reason, int plan, int sub, int react) {
        return new RouteDecision(TaskRoute.REACT, null, reason, plan, sub, react);
    }

    public static RouteDecision plan(String reason, int plan, int sub, int react) {
        return new RouteDecision(TaskRoute.PLAN, null, reason, plan, sub, react);
    }

    public static RouteDecision subAgent(String agent, String reason,
                                         int plan, int sub, int react) {
        return new RouteDecision(TaskRoute.SUBAGENT, agent, reason, plan, sub, react);
    }
}
