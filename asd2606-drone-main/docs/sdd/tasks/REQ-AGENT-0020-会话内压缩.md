# 任务拆解 — REQ-AGENT-0020 会话内压缩（Compaction）

- **需求 ID**：`REQ-AGENT-0020`
- **负责人 / 日期**：AI / 2026-10-23

## 任务表

| 编号 | 任务 | 产出 | 完成标志 | 依赖 | 状态 |
|---|---|---|---|---|---|
| T-1 | 规范三件套 | `docs/sdd/*/REQ-AGENT-0020-*` | 评审通过 | - | 已完成 |
| T-2 | 摘要提示词与压缩接口 | `prompts/memory/compaction.md` / `ConversationCompactor` / `ChatService` | 提示词可加载，方法可调用 | T-1 | 已完成 |
| T-3 | Session 与组装逻辑 | `Session` / `ContextAssembler` / `MemoryProperties` | 触发、复用、回退用例通过 | T-2 | 已完成 |
| T-4 | 账单分类 | `MessageKind.COMPACTION` | 账单显示「历史摘要」 | T-3 | 已完成 |
| T-5 | 回归与真机 | 单测 + `docs/tests/` | 全绿 + 长会话验证 | T-4 | 已完成 |

## 验收条件覆盖

| 验收条件 | 任务 |
|---|---|
| AC-1 / AC-2 | T-3 |
| AC-3 | T-3 |
| AC-4 | T-3 |
| AC-5 | T-4 |
| AC-6 | T-3（既有用例回归） |
