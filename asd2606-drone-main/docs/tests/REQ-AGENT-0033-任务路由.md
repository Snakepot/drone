# REQ-AGENT-0033 实施记录：任务路由

- **日期**：2026-10-28
- **状态**：已实现 + 一次真实手工冒烟；尚未沉淀自动化 E2E

## 一、实现内容

| 层 | 内容 |
|---|---|
| 路由模型 | `TaskRoute`（REACT/PLAN/SUBAGENT）、`RouteDecision`（route/agent/reason/scores） |
| 路由规则 | `TaskRouter`：显式指令、内置计划、知识问答、数据域/分析/下钻、写操作、简单查询 |
| 子 Agent 门面 | `SubAgentService`：按名字/default 执行，供路由直接调用 |
| AgentLoop | `ToolCallingService`：三路由分支；子 Agent/Plan 失败可降级 ReAct |
| 配置 | `agent.route.enabled`；关闭后回到旧行为；`agent.subagent.max-prompt-tokens` / `min-remaining-tokens` |
| 防重复委派 | 移除模型侧 `delegate_to_subagent` 工具；路由是唯一入口 |
| 日志 | `【路由】decision=…，reason=…，domains=…，scores=…` |

## 二、真实冒烟

### 复杂只读 → SUBAGENT

请求：`对比本周和上周的任务完成率和告警数量，给 3 条建议`

```text
【工具编排】可用工具 74 个（已移除 delegate_to_subagent）
【路由】decision=SUBAGENT，reason=complex_readonly，domains=2，
        scores(plan/sub/react)=0/8/0，消息长度=26
【子代理】开始：name=ops-analyst，maxRounds=4
【子代理】完成：status=ok，轮次=3，工具调用=6，耗时 13698ms，摘要 1082 字
【子代理】路由完成：agent=ops-analyst，traceId=9c7a6ba3
```

主模型没有进入 ReAct 工具循环，直接把子 Agent 摘要作为回复。
（本次冒烟时 admin 当日额度已被前序测试用尽，使用隔离实例临时放宽日额度；仅测试配置，不改代码。）

### 简单对比 → REACT

请求：`对比本周和上周的运营`

```text
【路由】decision=REACT，reason=simple_or_default，domains=0，
        scores(plan/sub/react)=0/4/1，消息长度=10
【工具编排】第 1 轮：调用模型
【工具编排】第 1 轮：模型请求调用 2 个工具（analytics_overview / dashboard_config）
```

主模型自己完成了查询与回答。

## 三、冒烟中发现并修复的问题

单测第一次失败：`"完成率"` 命中了写操作词 `"完成"`，导致复杂对比被误判为 PLAN。

修复：从写操作词表移除 `"完成"`、`"拒绝"`（它们常出现在指标名“完成率/拒绝率”里），
补充回归用例后再跑通过。

## 四、测试

- `drone-agent`：**419 passed**
  - `TaskRouterTest` 5 条：复杂/简单、内置/知识、显式、写操作、简单查询；
  - `ToolCallingServiceTest` 新增 1 条：复杂只读直接走子 Agent、主模型未被调用；
  - `SubAgentRunnerTest` 新增 1 条：剩余预算不足时不启动；
  - 原 ReAct/Plan/子 Agent 测试保持通过。

## 五、设计取舍：提示词委派 vs 代码路由 vs 工具门禁

| 方案 | 优点 | 问题 | 本项目 |
|---|---|---|---|
| 提示词委派 | 灵活，适合开放式任务 | 不稳定，失败可能反复委派 | 仅作补充说明 |
| 代码路由 | 确定、可测试、无额外模型调用 | 规则要维护、可能误判 | **主方案** |
| 工具门禁 | 权限/只读/危险/预算绕不过 | 不负责选择路径 | 安全与成本兜底 |

三者叠加：路由选路、门禁兜底、提示词补充说明。

## 六、已知限制

- 规则是启发式，可能误判；例如“派发积压”同时含“派发”（写操作词）与“积压”（指标）；
- 未做模型分类器/个性化学习；
- 子 Agent 取消仍不能从主 Agent 穿透；
- 未覆盖 MultiAgent；
- 真实冒烟只覆盖两条路由，尚未自动化。
