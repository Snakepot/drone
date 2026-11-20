---
name: alert-triage
version: 1
owner: 值班
description: 对未处理告警做分诊，给出处置建议并按状态机执行 claim / resolve / ignore
when_to_use: 「现在有哪些告警」「帮我处理告警」「告警怎么处置」
permissions: [alert:list, alert:handle]
tools:
  - drone__alert_list
  - drone__alert_detail
  - drone__task_detail
  - drone__alert_handle
---

# 告警分诊

## 目标

把「一屏告警」变成「一张可执行的处置单」：按紧急度排序、结合任务上下文给出建议动作，
并在用户确认后按状态机执行。

## 执行步骤（必须按顺序）

**第 1 步 · 拉取待处理告警**：调用 `drone__alert_list`，
参数 `{"status":"pending","pageSize":30}`。
服务端已经按紧急度排序，**保持原顺序**，不要自己重排。
记录：`id`、`alertCode` / `alertCodeLabel`、`severity`、`message`、`deviceCode`、
`taskId`、`observedAt`、`pilotActionRequired` 与 `actionHint`。

**第 2 步 · 补充上下文**：对 `severity=critical` 或 `warning` 且带 `taskId` 的告警，
调用 `drone__task_detail`，参数 `{"id": <taskId>}`，看任务状态、飞手与设备。
需要看告警载荷时，调用 `drone__alert_detail`，参数 `{"id": <告警ID>}`。

**第 3 步 · 给出分诊表与建议**
每条告警给出一个建议动作：

| 情况 | 建议动作 |
|---|---|
| 需要先接手排查 | `claim`（pending → handling） |
| 已处理完毕、有明确结论 | `resolve`（handling → resolved） |
| 确认是误报 / 无需处理 | `ignore`（pending/handling → ignored） |

**第 4 步 · 执行处置（仅在用户确认后）**
调用 `drone__alert_handle`，参数：
```json
{"id": <告警ID>, "action": "claim|resolve|ignore", "note": "<处置说明>"}
```
- `claim` 可不写 note；`resolve` / `ignore` **必须写 note**，写清原因或处理结果。
- 状态机是硬约束：`pending` 不能直接 `resolve`，必须先 `claim`。
- 每次最多处置一条，执行后复述结果。

## 严重度与状态字典

- `severity`：`critical` 严重（优先）、`warning` 警告、`info` 提示。
- `status`：`pending` 待处理、`handling` 处理中、`resolved` 已处理、`ignored` 误报。
- `pilotActionRequired=true` 表示需要飞手在现场动手，结合 `actionHint` 转述给用户。

## 输出模板

```markdown
## 告警分诊 · 共 {n} 条（严重 {n} / 警告 {n} / 提示 {n}）

| # | 级别 | 告警 | 设备 | 任务 | 观察时间 | 建议 |
|---|---|---|---|---|---|---|

## 处置建议
1. {告警} —— {claim/resolve/ignore}，理由：{…}

需要我按上面的建议处置吗？
```

## 禁止

- 不得跳过 `claim` 直接 `resolve`。
- 不得在用户确认前执行 `drone__alert_handle`。
- `resolve` / `ignore` 不得省略 note。
- 不得编造告警内容；`message` 是原始文案，照实转述。
