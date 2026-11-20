# 任务拆解 — REQ-AGENT-0033 任务路由

- **需求 ID**：`REQ-AGENT-0033`
- **负责人 / 日期**：AI / 2026-10-28
- **状态**：已实现 + 一次真实手工冒烟

## 任务表

| 编号 | 任务 | 产出 | 完成标志 | 依赖 | 状态 |
|---|---|---|---|---|---|
| T-1 | 规范与方案 | specs/plans/tasks | 评审通过 | - | 已完成 |
| T-2 | 路由模型 | `TaskRoute` / `RouteDecision` / `TaskRouter` | 规则可单测 | T-1 | 已完成 |
| T-3 | 子 Agent 门面 | `SubAgentService` | 可按名字/default 执行 | T-2 | 已完成 |
| T-4 | AgentLoop 接入 | `ToolCallingService` | 三路由分支 + 降级 | T-2/T-3 | 已完成 |
| T-5 | 配置与装配 | `RouteProperties` / `RouteConfig` / `SubAgentProperties` / yml | 开关可回滚；子 Agent 预算生效 | T-4 | 已完成 |
| T-6 | 测试与记录 | 单测 + `docs/tests/` | 全绿 + 冒烟 | T-5 | 已完成（419 条单测 + 手工冒烟） |

## 验收覆盖

| 验收条件 | 任务 |
|---|---|
| AC-1 / AC-2 | T-2 / T-4 |
| AC-3 / AC-4 / AC-5 | T-2 / T-4 |
| AC-6 / AC-7 | T-2 |
| AC-8 | T-5 |
| AC-9 | T-2 |
| AC-10 | T-4 |

## 明确不做

- 模型分类器；
- MultiAgent；
- 个性化学习；
- 多语言意图；
- 子 Agent 写操作。
