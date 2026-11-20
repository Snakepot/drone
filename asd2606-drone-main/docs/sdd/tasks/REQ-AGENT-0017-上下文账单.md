# 任务拆解 — REQ-AGENT-0017 上下文账单（按消息计量）

- **需求 ID**：`REQ-AGENT-0017`
- **负责人 / 日期**：AI / 2026-10-23

## 任务表

| 编号 | 任务 | 产出 | 完成标志 | 依赖 | 状态 |
|---|---|---|---|---|---|
| T-1 | 规范三件套 | `docs/sdd/*/REQ-AGENT-0017-*` | 评审通过 | - | 已完成 |
| T-2 | 计量基础类型 | `context/{MessageKind,TokenEstimator,ContextMessage,ContextBill}` | 估算与汇总单测通过 | T-1 | 已完成 |
| T-3 | 上下文链路改造 | `ContextAssembler` / `ToolCallingService` / `ConversationService` | 编排用例通过，工具结果带工具名 | T-2 | 已完成 |
| T-4 | 请求构造与账单日志 | `ChatService` + `StreamAssembler` | 每次调用一条账单；流式带实际 usage | T-3 | 已完成 |
| T-5 | 测试与真机冒烟 | 单测 + `docs/tests/` | 全绿 + 账单日志可见 | T-4 | 已完成 |

## 验收条件覆盖

| 验收条件 | 任务 |
|---|---|
| AC-1 / AC-2 / AC-3 | T-3 / T-4 |
| AC-4 | T-4 / T-5 |
| AC-5 / AC-6 | T-2 |
