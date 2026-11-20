# 任务拆解 — REQ-AGENT-0002 简单记忆

> **回填件**（2026-10-12）：任务按**实际完成顺序**追记。

- **需求 ID**：`REQ-AGENT-0002`
- **负责人 / 日期**：2026-10-12（回填）

## 任务表

| 编号 | 任务 | 涉及模块/层 | 产出 | 完成标志 | 依赖 | 状态 |
|---|---|---|---|---|---|---|
| T-1 | 契约改为消息列表 | drone-agent / dto | `ChatMessage` 新增；`ChatRequest` 改 `{messages}` | 编译通过，接口接受列表 | - | 已完成 |
| T-2 | 历史校验与裁剪 | drone-agent / service | `ChatService.normalizeHistory()`、`MAX_HISTORY_MESSAGES=20` | 空列表/非法 role/尾非 user 均被拒；超 20 条只取最近 20 条 | T-1 | 已完成 |
| T-3 | 系统提示词定位为第一条 | drone-agent / service | `buildBody()` 先放 system 再拼历史 | 客户端无法改变 system 内容 | T-2 | 已完成 |
| T-4 | 非法入参映射业务码 | drone-agent / common | `GlobalExceptionHandler` 加 `IllegalArgumentException` → `state 400` | 非法入参返回 `state 400` 而非 500 | T-2 | 已完成 |
| T-5 | 前端发送整段历史 | drone-web / api | `agent.ts` 入参改 `messages`；`AgentChatParam` 移除 `systemPrompt` | 面板能带上历史发起请求 | T-1 | 已完成 |
| T-6 | 失败占位消息不回传 | drone-web / 组件 | `ChatMessage` 加 `error?`；发送前过滤 | 失败占位不出现在请求体里 | T-5 | 已完成 |
| T-7 | 端到端验证多轮记忆 | - | 验证记录 | AC-1 通过（第二轮正确回忆出「华东区」「M300」） | T-5 T-2 | 已完成 |
| T-8 | 验证角色注入防护 | - | 验证记录 | AC-2 / AC-3 / AC-4 均返回 `state 400` | T-2 T-4 | 已完成 |
| T-9 | 补 `normalizeHistory` 单元测试 | drone-agent / test | `ChatServiceTest`（8 个用例） | 覆盖 AC-2~AC-6，`mvn test` 通过 | T-2 | 已完成（2026-10-12） |

## 验收条件覆盖

| 验收条件 | 覆盖任务 | 备注 |
|---|---|---|
| AC-1 | T-7 | 已验证 |
| AC-2 / AC-3 / AC-4 | T-8 T-9 | 已验证（T-9 起有自动化用例） |
| AC-5 | T-2 T-9 | 已验证（`keepsOnlyLastTwentyMessages`） |
| AC-6 | T-2 T-9 | 已验证（`dropsLeadingAssistantAfterTruncation`） |
| AC-7 | T-6 | **未单独验证** |
| AC-8 | T-3 | **未单独验证** |

## 与流程不符之处（如实记录）

- 未做事前拆解；T-2 把「校验 + 裁剪」揉在一条里，按规则应拆开。
- T-9 是本需求**最该先做**的一条（纯函数、无外部依赖、成本最低），当时却留成了欠账；已于 2026-10-12 补齐。
- 同上：本需求全部验收无自动化用例，与 `java-standards.md` 第 9 节不符。
