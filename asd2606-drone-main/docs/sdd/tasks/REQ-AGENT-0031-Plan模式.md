# 任务拆解 — REQ-AGENT-0031 Plan 模式

- **需求 ID**：`REQ-AGENT-0031`
- **负责人 / 日期**：AI / 2026-10-27
- **状态**：代码已落地；**功能未测试**（407 条单测 + 一次手工冒烟，未沉淀自动化 E2E）

## 任务表

| 编号 | 阶段 | 任务 | 产出 | 完成标志 | 依赖 | 状态 |
|---|---|---|---|---|---|---|
| T-1 | - | 规范与方案 | specs/plans/tasks/ADR | 评审通过 | - | 已完成 |
| T-2 | P0 | 计划模型与目录 | `Plan` / `PlanStep` / `PlanCatalog` | 启动加载并校验 `plans/*.json` | T-1 | 已完成 |
| T-3 | P0 | 内置日报计划 | `plans/daily-operations-report.json` | 触发词命中；步骤与 Skill 一致 | T-2 | 已完成 |
| T-4 | P0 | 计划执行器 | `PlanExecutor` | 依赖/并发/取消/失败/合成 | T-2 | 已完成 |
| T-5 | P0 | AgentLoop 接入 | `ToolCallingService` | 命中内置计划则执行；原 ReAct 不变 | T-3/T-4 | 已完成 |
| T-6 | P0 | 响应与前端 | `PlanSummary` / SSE / `AgentChatPanel` | 计划步骤与状态可见 | T-5 | 已完成 |
| T-7 | P1 | 生成计划 | `PlanPlanner` | 输出合法 JSON；非法工具拒绝 | T-2 | 已完成 |
| T-8 | P1 | 计划存储与审批 | `PlanStore` + `/chat/plan/confirm` | 落盘/TTL/原子消费；批准执行、拒绝作废 | T-7 | 已完成 |
| T-9 | P1 | 前端审批卡 | `AgentChatPanel` | 批准/拒绝可操作 | T-8 | 已完成 |
| T-10 | - | 回归与记录 | 单测 + `docs/tests/` | 全绿 + 演示记录 | T-6/T-9 | 已完成（407 条单测；手工冒烟完成，未自动化） |

## 验收覆盖

| 验收条件 | 任务 |
|---|---|
| AC-1 / AC-2 | T-3 / T-4 / T-5 |
| AC-3 / AC-4 / AC-5 / AC-12 | T-7 / T-8 / T-9 |
| AC-6 | T-7 |
| AC-7 / AC-8 | T-8 |
| AC-9 | T-4 |
| AC-10 | T-5 / T-10 |
| AC-11 | T-4 |

## 明确不做

- 通用工作流 DSL / BPMN；
- 多 Agent；
- 计划中途自动重规划；
- 危险工具在计划内自动放行；
- 流式审批（本期非流式）。
