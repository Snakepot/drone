---
name: shift-handover
version: 1
owner: 飞手班组
description: 生成飞手交接单：未完成任务、异常告警、证据缺口与下一班注意事项
when_to_use: 「我要交班了」「帮我出个交接单」「下班前看看还有什么事」
permissions: [task:execute]
tools:
  - drone__task_my
  - drone__task_alert_list
  - drone__task_detail
  - drone__task_track
---

# 飞手交接班

## 目标

交班时，接班的同事需要一眼看清：**哪些任务没结束、哪些告警没处理、哪些任务缺旁证**。
输出必须能直接贴进交接群。

## 执行步骤（必须按顺序）

**第 1 步 · 我的任务**：调用 `drone__task_my`，参数 `{"pageSize":50}`。
按状态归类，重点保留非终态（`pending` / `dispatched` / `executing`）与今天的 `finished` / `failed`。
记录任务号、任务名、状态、设备编号、计划时间、实际开始时间、`evidenceState`。

**第 2 步 · 执行中任务的告警**：对每个 `executing` 任务，
调用 `drone__task_alert_list`，参数 `{"taskId": <任务ID>}`。
记录 `severity`、`message`、`status`、`observedAt`。

**第 3 步 · 异常核对**：对以下任务调用 `drone__task_detail`，参数 `{"id": <任务ID>}`：
- `evidenceState` 为 `none` 或 `stale`；
- 已过计划结束时间但仍未收尾；
- 有未处理告警。

需要确认飞行轨迹时，调用 `drone__task_track`，参数 `{"taskId": <任务ID>}`。

**第 4 步 · 输出交接单**

## 输出模板

```markdown
# 交接单 · {姓名} · {YYYY-MM-DD HH:mm}

## 一、未完成任务
| 任务号 | 名称 | 状态 | 设备 | 计划时间 | 当前进度 |

## 二、异常告警
| 任务号 | 级别 | 告警 | 状态 | 观察时间 |

## 三、证据缺口
| 任务号 | evidenceState | 说明 | 建议 |

## 四、下一班注意事项
1. {待办事项 + 期望完成时间}
```

## 规则

- `evidenceState` 只有：`none` 无依据、`declared` 仅声明、`confirmed` 已证实、`stale` 声明过期。
- 「未完成」只包含非终态任务；`finished` / `failed` / `cancelled` 不列入。
- 告警只列未处理（`pending` / `handling`）的；已处理的可放在备注里。
- 不得编造任务号、告警或轨迹数据；没有就写「无」。
