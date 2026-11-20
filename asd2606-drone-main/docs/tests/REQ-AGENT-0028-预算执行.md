# REQ-AGENT-0028 端到端回归记录：预算执行

- **日期**：2026-10-26
- **环境**：本机；`drone-server` 8080、`drone-mcp-server` 8082、
  `drone-agent` 8083（隔离实例，bill root `./data/bills-smoke`，跑完已清理）
- **账号**：admin `13800001005`

## 一、单请求上限（AC-3）

启动参数：`--agent.budget.max-request-tokens=100`。

> 「现在有多少设备？只回答数量。」

```json
{"state":200,"data":{"reply":"本次请求的上下文预算已用完（已用 11570 / 上限 100 token）。我先收尾；请把问题拆小一点再试。","traceId":"0c4f1855"}}
```

```text
【预算】已超限：scope=request，traceId=0c4f1855，已用 11570，上限 100
【预算】单请求超限，停止工具循环：… 第 1 轮
```

- 模型只调用 1 次；
- 工具执行日志 **0 条**（预算检查在工具执行之前）；
- 答复是明确的收尾话术，不是 500。

## 二、日额度（AC-1）

用上一步产生的 11,570 token，把日额度设为 1 重启：

```json
{"state":200,"data":{"reply":"今日智能助手额度已用完（已用 11570 / 上限 1 token），请明天再试或联系管理员。","traceId":"d0dcc248"}}
```

```text
【预算】已超限：scope=daily，userId=5，已用 11570，上限 1
【预算】拒绝请求（日额度）：…
```

- 只有「收到请求」与预算拒绝日志，没有模型调用；
- 请求仍返回 HTTP 200 + 统一响应体，前端按 `reply` 展示。

## 三、回滚与边界（AC-5 / AC-6）

- `agent.budget.enabled=false`：单测 `disabledNeverExceeds` 覆盖，不拦截；
- 账单关闭/读取失败：`BudgetService` 按未超限放行并 WARN（`readFailureFailsOpen`）；
- 未超限的普通请求：既有 367 个单测全部通过，行为不变。

## 四、测试

- `drone-agent`：**367 passed**
  - `BudgetServiceTest` 4 条：日额度、单请求、关闭开关、读取失败放行；
  - `ToolCallingServiceTest` 1 条：超限后只调一次模型且不执行工具；
  - `ConversationServiceTest` 1 条：日额度超限不调模型。
- `drone-mcp-server`：29 passed（幂等改动，未受预算影响）。

## 五、未覆盖

- 未做按角色/模型定价的额度；
- 多实例计数分裂（单实例文件）；
- 额度用尽后的「次日自动恢复」依赖账单按天文件，跨天无需额外逻辑（`aggregate` 只读当天）。
