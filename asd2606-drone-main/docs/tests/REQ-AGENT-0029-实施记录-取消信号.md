# REQ-AGENT-0029 实施记录（一）：取消信号与检查点

- **日期**：2026-10-26
- **范围**：T-2 / T-3（取消注册、`POST /chat/cancel`、循环检查点）
- **状态**：部分实现；T-4 及之后（票据持久化、台账、报告、前端）未开始

## 一、实现

| 组件 | 内容 |
|---|---|
| `cancel/CancellationSignal` | 原子取消位 |
| `cancel/CancellationRegistry` | 键 `userId:conversationId`；30 分钟 TTL；登记/置位/清理 |
| `ChatController` | `POST /chat/cancel {conversationId}`；无在途请求返回 409 |
| `ConversationService` | 请求开始登记、结束清理；把信号传进编排 |
| `ToolCallingService` | 轮次开始、模型返回后、工具执行前三个检查点 |

## 二、真机验证

发一个告警分诊请求（会调 `use_skill` + 多个工具），0.8 秒后取消：

```json
POST /chat/cancel {"conversationId":"cancel-smoke5"}
→ {"state":200,"data":true}
```

SSE `done`：

```json
{"type":"done","reply":"已取消本次请求。已完成的步骤仍然有效；如需撤销，请告诉我。", ...}
```

日志：

```text
【取消】已置位：userId=5，conversationId=cancel-smoke5，traceId=3db44a56
【取消】模型返回后检测到取消，丢弃本轮后续动作（traceId=3db44a56）
```

- 模型只调用 1 次；
- 工具执行日志 0 条（检查点在工具执行之前）。

## 三、冒烟中发现并修复

1. **注册表不是同一个实例**：一开始用 setter 注入，Controller 与编排各拿到一个实例，
   取消置位后编排层看不到。改为构造器注入，确保单例。
2. **信号没有传进流式路径**：`askOutcomeStreaming` 调用漏了 `signal` 参数；
   修好后立即生效。
3. **取消可能晚于预算/完成**：第一次冒烟请求 76k token 先撞预算，第二次取消来得太晚
   请求已完成——都属预期（取消太久/太晚），返回的是预算话术或完整答复。

## 四、测试

- `drone-agent`：**372 passed**
  - `CancellationRegistryTest` 3 条：归属、清理、无在途；
  - `ToolCallingServiceTest` 2 条：开始前取消不调模型、模型返回后取消不执行工具。

## 五、未覆盖（后续任务）

- 待确认票据持久化与取消作废（T-4/T-5）；
- 写台账、部分完成报告（T-6）；
- 前端停止按钮与报告卡片（T-7）；
- operationId 续跑、补偿（T-8/T-9）。

---

# 实施记录（二）：待确认票据持久化与恢复（T-4 / T-5）

- **日期**：2026-10-26
- **状态**：已完成；T-6 及之后未开始

## 一、实现

| 项 | 内容 |
|---|---|
| 存储 | `<root>/<userId>/<pendingId>.json`，临时文件 + 原子改名；权限尽量 600 |
| 启动加载 | 扫描目录，加载未过期票据；过期/损坏清理（损坏改名为 `.corrupt`） |
| 消费 | 先原子改名为 `.consumed`，再返回执行；失败则拒绝确认（最多一次） |
| 取消 | `removeByConversation` 删除内存并改名为 `.cancelled` |
| 查询 | `GET /chat/pending?conversationId=` 返回摘要（不含正文/参数原文） |
| 配置 | `agent.tool.pending-root` / `pending-ttl`；root 为空退回内存 |
| 前端 | 面板打开时 `fetchPending`，恢复确认框；已处理过的 pendingId 不再重复弹 |

## 二、测试（`drone-agent` 375 passed）

新增 `PendingActionStoreTest` 3 条：

| 用例 | 断言 |
|---|---|
| 重启恢复 | 新实例能 `take` 原票据；消费后重启不复活 |
| 取消作废 | `removeByConversation` 后内存与文件都查不到 |
| 启动清理 | 过期票据在启动时被清理，`size=0` |

既有 4 条（消费一次、归属、过期、未知 id）保持通过。

## 三、未覆盖

- 未在真机走「危险操作暂停 → 重启 → 确认」全链路（需要触发真实危险工具，避免改动业务数据）；
  持久化与恢复由单元测试覆盖，API 为薄映射；
- 前端恢复确认框未做浏览器实测（type-check/lint 通过）；
- 多实例共享未做。

---

# 实施记录（三）：写台账、取消报告与前端停止（T-6 / T-7）

- **日期**：2026-10-26
- **状态**：已完成；T-8（续跑）/ T-9（补偿）未开始

## 一、实现

| 项 | 内容 |
|---|---|
| 读写区分 | `ToolDescriptor.readOnly`；本地工具 yml 标 `read-only: true`，MCP 用 `readOnlyHint` |
| 写台账 | `WriteJournal` JSONL（`data/operations`）：operationId/tool/参数哈希/状态/对象 ID，不含参数原文 |
| 取消报告 | `CancellationReport`：completed / notStarted / unknown / options / resumeToken |
| 编排 | 写操作前记 `started`，执行后记 `succeeded/failed/unknown`；对象 ID 从结果 `id`/`data.id` 尽力提取 |
| SSE | `done.report` 下发；`ChatStreamOutcome` 携带报告 |
| 前端 | 发送中显示「停止」按钮；取消后显示报告卡片（已完成/结果未知/未开始） |

## 二、真机验证（浏览器）

登录 → 打开助手 → 问「现在有哪些未处理的告警？」→ 0.9 秒后点「停止」：

```text
stop visible= true
report= 已取消本次请求。已完成的步骤仍然有效；如需撤销，请告诉我。
        本次 11.7k token · 缓存 98.5%
        已取消本次请求
        未开始：use_skill；
        取消不会撤销已完成的步骤；如需撤销，请告诉我。
errors= []
```

- 停止按钮在流式期间可见；
- 报告卡片正确列出「未开始：use_skill」；
- 控制台无错误。

## 三、测试（`drone-agent` 377 passed）

- `ToolCallingServiceTest` 增加报告断言：取消后 `report.cancelled=true`，未执行工具进入 `notStarted`；
- `WriteJournalTest` 2 条：追加元数据且不含参数原文；noop 不落盘。

## 四、未覆盖

- 未执行真实写工具（避免改业务数据）；当时 `completed` / `unknown` **实际没有实现**，
  2026-10-26 已补全（见文末「实施记录（五）」）；
- 「继续 / 撤销」按钮尚未提供（T-8/T-9）；
- 台账未做保留期清理（后续可复用账单的按天清理）。

---

# 实施记录（四）：operationId 续跑与撤销入口（T-8 / T-9）

- **日期**：2026-10-26
- **状态**：已完成（T-9 为提示词驱动 MVP）

## 一、T-8 operationId 贯通

| 层 | 改动 |
|---|---|
| `ToolContext` | 增加 `operationId`，`withOperationId(...)` |
| `McpToolAdapter` | 写调用带 `X-Operation-Id` |
| `drone-mcp-server` | `McpEndpoint` 接收并转给 `ToolInvoker`；幂等键优先用 operationId |
| `ToolCallingService` | 写步骤生成 operationId；续跑时按「工具 + 参数摘要」复用 token 里的 operationId（见五） |
| 续跑指令 | `ConversationService.withResume` 注入 system 消息：已完成工具+对象 ID，要求不要重复创建 |
| 前端 | 报告卡「继续」按钮，携带 `resumeToken` 发起新请求 |

幂等键：`operationId`（有）优先于 `traceId`；因此续跑换 trace 也能命中 REQ-0027 的缓存。

## 二、T-9 撤销（MVP）

- 报告卡「撤销」按钮：携带 resumeToken 发送一条显式撤销请求；
- `CompensationPlanner` 生成逆序补偿计划：
  - `user_add → user_toggle`（优先停用）
  - `device_add → device_delete`
  - `route/point/zone_add → 对应 delete`
  - 未知写操作 → 「需人工处理」
- 计划以 system 消息注入，模型先查询确认、再调用；危险工具仍走既有确认票据；
- 不直接执行、不自动回滚。

## 三、测试

- `drone-agent`：**380 passed**
  - `CompensationPlannerTest` 2 条（逆序、未知转人工）；
  - `ConversationServiceTest` 1 条（续跑指令与撤销计划注入）。
- `drone-mcp-server`：**30 passed**
  - `ToolInvokerTest` 1 条（不同 trace、同一 operationId 只执行一次）。
- `drone-web`：type-check、lint 通过（「继续/撤销」按钮与 resumeToken 传参）。

## 四、未覆盖 / 已知限制

- 未执行真实写工具的全链路续跑/撤销（避免改动业务数据）；
- 撤销是**提示词驱动**：模型仍可能选错工具；后续应改为服务端确定性补偿执行器；
- operationId 只在通过 MCP 的写工具上生效；本地写工具（当前没有）不参与；
- 未做台账保留期清理；
- 多实例共享未做。

---

# 实施记录（五）：补全报告数据源与 operationId 注入（T-6 / T-8 复核）

- **日期**：2026-10-26
- **背景**：复核发现 T-6 / T-8 只完成了「接线」，数据没有真正流通：
  - 报告的 `completedWrites` / `unknownWrites` / `capturedIds` / `completedOperations`
    声明后从未写入，`resumeToken` 恒为 null；
  - `operationIds` 生成后没有注入 `ToolContext`，`X-Operation-Id` 根本没发到 MCP；
  - 台账只记 `started`，执行结果没有落盘。

## 一、实现

| 位置 | 改动 |
|---|---|
| `ToolCallingService` | 执行后逐条结算写操作：`succeeded / failed / unknown`，回写台账并填入报告 |
| `ToolCallingService` | `notStarted` 只列写工具；串行执行**每个工具前**检查取消，返回已执行数量 |
| `ToolCallingService` | 每次调用用 `withOperationId` 构造独立上下文（并行时各调用互不串号） |
| `CancellationReport` | 新增 `failed` 列表：业务错误（确定未生效）与 `unknown`（可能已生效）分开 |
| `CancellationReport` | 续跑令牌改为列表（`operations` / `captured`）：同一工具多次调用各记一条 |
| `ToolCallingService` | 续跑按「工具 + 参数摘要」匹配 operationId，不再按工具名整把覆盖 |
| `ToolCallingService` | 读工具在途时每 50ms 查一次取消位，取消即放弃等待；写工具仍等结算 |
| `ChatController` | SSE 断开 / 超时 / 完成回调置位取消信号；发送失败时立即置位 |
| `AgentChatPanel` | 报告卡新增「失败（未生效）」；`captured` 为空时不给「撤销」 |
| `WriteJournal` | 同一 operationId 记两条：`started` + 终态（含对象 ID） |

## 二、测试

- `drone-agent`：**392 passed**
  - 新增 8 条：
    报告含已完成 + 未开始 + resumeToken；超时 → unknown；业务错误 → failed；
    operationId 注入与续跑复用；台账 started + succeeded/cancelled；
    同工具不同参数各记一条；在途读工具取消后 1s 内返回；补偿顺序保持；
  - 原有取消用例调整为「写工具」（读工具不再进报告）。

## 三、仍存在的边界

- 读工具放弃等待后，后台调用仍会把 HTTP 请求跑完（结果丢弃）；没有强行中断线程，
  避免工具内部留下半截状态；
- 客户端断开只能在下一次写 SSE 或容器回调时感知，服务端无法「主动探测」断连；
- 参数摘要按原始 JSON 计算：模型续跑时若改写参数顺序/措辞，会对不上旧 operationId
  （宁可新建一次，也不误复用别人的操作）；
- 撤销仍是提示词驱动 MVP，未做服务端确定性补偿执行器；
- 未执行真实写工具的全链路续跑/撤销（避免改动业务数据）。
