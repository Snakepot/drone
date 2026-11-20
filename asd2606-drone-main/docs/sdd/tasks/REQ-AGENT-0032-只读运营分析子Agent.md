# 任务拆解 — REQ-AGENT-0032 只读运营分析子 Agent

- **需求 ID**：`REQ-AGENT-0032`
- **负责人 / 日期**：AI / 2026-10-28
- **状态**：已实现 + 一次真实手工冒烟；**尚未沉淀为可重复自动化 E2E**

## 任务表

| 编号 | 任务 | 产出 | 完成标志 | 依赖 | 状态 |
|---|---|---|---|---|---|
| T-1 | 规范与方案 | specs/plans/tasks | 评审通过 | - | 已完成 |
| T-2 | 子 Agent 模型与目录 | `SubAgentDefinition` / `SubAgentCatalog` | 加载并校验 `subagents/*.md` | T-1 | 已完成 |
| T-3 | 受限执行器 | `SubAgentRunner` | 独立上下文、白名单、只读、轮次/超时/预算 | T-2 | 已完成 |
| T-4 | 路由接入 | `SubAgentService` + `TaskRouter` | 复杂只读请求直接执行子 Agent | T-3 | 已完成 |
| T-5 | 运营分析定义 | `ops-analyst.md`（frontmatter + 正文） | 可完成跨周期对比/日报取数 | T-2 | 已完成 |
| T-6 | 主 Agent 接入 | `TaskRouter` / `SubAgentService` / yml | 路由生效、无模型侧委派 | T-4 | 已完成 |
| T-7 | 测试与记录 | 单测 + `docs/tests/` | 全绿 + 场景走查 | T-6 | 已完成（419 条单测 + 手工冒烟） |

## 验收覆盖

| 验收条件 | 任务 |
|---|---|
| AC-1 | T-4 / T-6 |
| AC-2 / AC-3 | T-3 |
| AC-4 / AC-5 / AC-7 | T-3 |
| AC-6 | T-3 |
| AC-8 | T-6 / T-7 |
| AC-9 | T-3 |

## 明确不做

- 子 Agent 写操作；
- 主对话共享；
- 自动路由；
- MultiAgent；
- 取消穿透。
