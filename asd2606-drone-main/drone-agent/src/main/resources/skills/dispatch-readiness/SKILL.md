---
name: dispatch-readiness
version: 1
owner: 调度
description: 派发前检查任务状态、禁飞区与可用人机，给出「可派 / 不可派」结论
when_to_use: 「这个任务能派吗」「帮我派发任务 123」「派发前先检查一下」
permissions: [task:dispatch]
tools:
  - drone__task_detail
  - drone__task_zone_check
  - drone__task_dispatch_options
  - drone__task_dispatch
---

# 派发前检查

## 目标

在派发前一次性确认三件事：**任务状态合法、航线不撞禁飞区、有人有机可用**。
任何一项不满足，都要明确说「不可派」及原因，不要含糊。

## 执行步骤（必须按顺序）

**第 1 步 · 任务状态**：调用 `drone__task_detail`，参数 `{"id": <任务ID>}`。
- 只有 `status=pending`（待派发）才能派发。
- 记录任务号 `taskNo`、任务名、优先级（P0 紧急 / P1 高 / P2 中 / P3 低）、
  计划起止时间、航线信息、起飞点。
- 若状态是 `dispatched` / `executing` / `finished` / `failed` / `cancelled`，**直接停下**，
  告诉用户当前状态与可执行的动作（撤回 / 拒绝 / 收尾等）。

**第 2 步 · 禁飞区预检**：调用 `drone__task_zone_check`，参数 `{"taskId": <任务ID>}`。
- 返回空列表：无命中，继续。
- 有命中：记录每条命中的区域、限高与生效期。**必须让用户明确确认风险**，
  并在派发时传 `acknowledgedZones=true` 与 `zoneReason`（用户给出的理由）。

**第 3 步 · 可用资源**：调用 `drone__task_dispatch_options`，参数 `{}`。
- 可派飞手：`user.state=idle` 的人。
- 可派设备：`drone.status=idle` 的设备。
- 若飞手或设备为空，结论为「不可派：缺少可用人/机」，并说明缺哪一类。

**第 4 步 · 给出结论**
- 三项都通过：输出「可派」，列出建议的飞手与设备（**只是建议，等用户选定**）。
- 任一项不通过：输出「不可派」，逐条列出原因与建议动作。

**第 5 步 · 派发（仅在用户明确确认后）**
用户确认飞手与设备后，调用 `drone__task_dispatch`，参数：
```json
{"taskId": <ID>, "pilotId": <飞手ID>, "droneId": <设备ID>, "note": "<可选>"}
```
若有禁飞区命中，追加 `"acknowledgedZones": true, "zoneReason": "<用户确认的理由>"`。
派发成功后复述：任务号、飞手、设备、计划时间。

## 输出模板

```markdown
## 派发检查 · {任务号} {任务名}
| 检查项 | 结果 | 说明 |
|---|---|---|
| 任务状态 | 通过 / 不通过 | pending（待派发）… |
| 禁飞区 | 通过 / 命中 N 个 | {区域名、限高} |
| 可用飞手 | N 人 | {姓名} |
| 可用设备 | N 台 | {编号} |

**结论：可派 / 不可派**
{不可派时逐条列出建议动作}
```

## 禁止

- 状态不是 `pending` 时**不得**尝试派发。
- 禁飞区有命中却没有用户确认时，**不得**派发。
- 不得自行选择飞手/设备并直接派发；必须先给出建议、等用户确认。
- 不得编造飞手、设备或任务编号。
