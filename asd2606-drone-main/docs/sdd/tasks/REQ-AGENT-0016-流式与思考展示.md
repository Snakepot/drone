# 任务拆解 — REQ-AGENT-0016 流式输出与思考过程展示

- **需求 ID**：`REQ-AGENT-0016`
- **负责人 / 日期**：AI / 2026-10-22

## 任务表

| 编号 | 任务 | 产出 | 完成标志 | 依赖 | 状态 |
|---|---|---|---|---|---|
| T-1 | 规范与 ADR-0011 | docs | 评审通过 | - | 已完成 |
| T-2 | 流式解析与 `streamStep` | `StreamAssembler` / `ChatService` | 解析用例通过 | T-1 | 已完成 |
| T-3 | 流式编排 | `askOutcomeStreaming` / `reasoningMs` | 编排用例通过 | T-2 | 已完成 |
| T-4 | `streamAsk` 与事件协议 | `ConversationService` / `ChatController` | 事件序列通过 | T-3 | 已完成 |
| T-5 | 前端事件分发 | `agent.ts` | 前端单测通过 | T-4 | 已完成 |
| T-6 | 思考块与流式正文 | `AgentChatPanel` | 手工可见 | T-5 | 已完成 |
| T-7 | 全量测试与冒烟 | 测试与记录 | 全绿 + 真机 | T-6 | 已完成 |
| T-8 | 思考过程改为中文 | `assistant/system.md` v8 | 规则生效、版本号更新 | T-7 | 已完成 |
| T-9 | 规范同步与语言回归 | docs + 真机 | AC-9 通过 | T-8 | 进行中 |

## 验收条件覆盖

| 验收条件 | 任务 |
|---|---|
| AC-1 / AC-2 / AC-3 | T-2 / T-6 |
| AC-4 | T-3 |
| AC-5 / AC-6 | T-4 |
| AC-7 | T-2 |
| AC-8 | T-4 |
| AC-9 | T-8 / T-9 |
