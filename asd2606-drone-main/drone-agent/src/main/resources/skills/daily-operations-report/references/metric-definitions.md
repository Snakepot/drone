# 运营日报的指标口径

> 本文件是 L3 资料：正文需要核对口径时才通过 `read_skill_resource` 读取，
> 平时不占上下文。

## 1. 统计窗口

- 所有 `from` / `to` 的格式为 `yyyy-MM-dd HH:mm:ss`。
- `from` **含**、`to` **不含**。例如「今天」= 今天 00:00:00 至明天 00:00:00。
- 默认窗口为最近 7 天；最大跨度 366 天。
- `/analytics/overview` 的任务与告警窗口基于 `actual_end_time`（实际结束时间），
  不是创建时间。

## 2. 任务指标

| 指标 | 口径 |
|---|---|
| 完成率 | `finished / (finished + failed)`；没有样本时为 null，报告写「无样本」 |
| 按时率 | 实际结束时间不晚于计划结束时间的任务占比（分母为 `onTimeBase`） |
| 平均时长 | `actual_end_time - actual_start_time` 的均值（分钟），分母为 `durationBase` |
| 新建任务 | 按 `task.create_time` 落在窗口内统计 |

> 完成率的分母**不含** `cancelled` 与未结束任务；不要用「完成 / 总数」去算，会偏低。

## 3. 派发积压

`reasonShare` 的取值：

| reason | 含义 |
|---|---|
| `blocked_pilot` | 缺可用飞手 |
| `blocked_drone` | 缺可用设备 |
| `blocked_both` | 人机都缺 |
| `idle` | 资源够，但没人派发（管理动作问题） |

`backlogAge`：`lt1h` 一小时内、`lt1d` 一天内、`ge1d` 超过一天。
`medianMinutes` 是「创建到派发」的中位时长。

## 4. 告警

- `severity`：`critical` 严重、`warning` 警告、`info` 提示。
- `status`：`pending` 待处理、`handling` 处理中、`resolved` 已处理、`ignored` 误报。
- `MTTR`：已处理告警从观察到处理的平均时长（分钟）。

## 5. 任务旁证（evidenceState）

| 值 | 含义 |
|---|---|
| `none` | 无依据 |
| `declared` | 仅飞手声明 |
| `confirmed` | 已证实（有遥测/飞行日志等旁证） |
| `stale` | 声明已过期（长时间没有新遥测） |

> 已完成为 `none` / `stale` 的任务，结算与审计时风险更高，应在日报里单独点名。

## 6. 常用状态字典

- 任务：`pending` / `dispatched` / `executing` / `finished` / `failed` / `cancelled`
- 设备：`idle` / `assigned` / `flying` / `charging` / `offline` / `maintenance`
- 人员在线：`idle` 空闲 / `busy` 作业中 / `grounded` 停飞
- 账号：`status=1` 启用 / `status=0` 禁用
