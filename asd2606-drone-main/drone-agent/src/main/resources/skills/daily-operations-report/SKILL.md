---
name: daily-operations-report
version: 2
owner: 运营部
description: 生成当日运营日报（任务 / 人员 / 设备 / 告警 / 风险建议）
when_to_use: 管理员问「今天运营情况怎么样」「出个日报」「今天的任务和告警汇总一下」
permissions: [task:list, alert:list]
tools:
  - drone__dashboard_summary
  - drone__analytics_overview
  - drone__analytics_dispatch_block
  - drone__task_list
  - drone__alert_list
  - read_skill_resource
---

# 当日运营日报

## 目标

用固定的查数口径与固定版式，输出一份当天（00:00–24:00）的运营日报。
读者是管理员，需要**数字准确、口径统一、风险可行动**。

## 输出形式：每次都是 HTML

**每次生成日报都必须输出 HTML 版，没有「Markdown 版」这种默认。**

1. 先给 **2–3 行摘要**（核心数字 + 最需要关注的风险），让用户先看结论；
2. 然后输出**围栏 HTML**：`` ```html … ``` ``。系统会把围栏里的内容抽成
   可预览、可下载的产物，聊天里只展示摘要；
3. 围栏之外不要再重复整份 HTML，也**不要只给 Markdown 表格**当作日报；
4. 版式必须来自 `assets/daily-report.html`，不要自己另画一套。

## 执行步骤（必须按顺序；任何一步失败，停下说明，不要用估算补齐）

> 第 2、3 步互不依赖，**可以在同一轮并发调用**；第 4、5 步也可以并发。

**第 1 步 · 总览**：调用 `drone__dashboard_summary`，参数 `{}`。
记录 `tasks.byStatus`、`alerts.open` 与 `bySeverity`、`devices.byStatus`、
`staff.idle` / `busy`、`evidence.today`。
注意：返回里**缺失的块**表示当前账号没有该权限，报告写「无权限查看」，**不要当成 0**。

**第 2 步 · 今日任务**：调用 `drone__analytics_overview`，参数
`{"from":"<今天 00:00:00>","to":"<明天 00:00:00>"}`。
取任务量、完成率、按时率、平均时长、告警趋势与 MTTR。

**第 3 步 · 派发积压**：调用 `drone__analytics_dispatch_block`，参数同上。
重点看 `reasonShare`（缺飞手 / 缺设备 / 缺人又缺设备 / 资源够但没人派）与 `medianMinutes`。

**第 4 步 · 在执行清单**：调用 `drone__task_list`，参数 `{"status":"executing","pageSize":50}`。
逐条记录任务号、任务名、飞手、设备、计划时间、实际开始时间；
`evidenceState` 为 `stale` / `none` 的单独标出来。

**第 5 步 · 待处理告警**：调用 `drone__alert_list`，参数 `{"status":"pending","pageSize":30}`。
按 `severity`（critical / warning / info）归类，记录 `message`、设备 `deviceCode`、
所属任务号与 `observedAt`。

**第 6 步 · 口径核对（按需）**：如果对「完成率 / 按时率 / MTTR / 积压」的口径不确定，
调用 `read_skill_resource`，参数
`{"skill":"daily-operations-report","path":"references/metric-definitions.md"}`。

**第 7 步 · 生成并输出 HTML**：按文末「HTML 版式」执行——读取模板、替换占位符与示例行，
最后给出「2–3 行摘要 + 围栏 HTML」。

## 日期与格式

- 「今天」按服务器本地时间；时间参数格式固定为 `yyyy-MM-dd HH:mm:ss`。
- 统计窗口 `from` 含、`to` 不含；今天就是「今天 00:00:00」到「明天 00:00:00」。
- 任务状态只有：`pending` 待派发、`dispatched` 已派发、`executing` 执行中、
  `finished` 已完成、`failed` 已失败、`cancelled` 已取消。
- 设备状态只有：`idle` 待命、`assigned` 已派出、`flying` 飞行中、
  `charging` 充电中、`offline` 离线、`maintenance` 维护中。

## 摘要（围栏前只写这些）

围栏 HTML 之前，只写 2–3 行，不要展开成 Markdown 表格：

- 今日新增任务 {n}，完成 {n}，失败 {n}，完成率 {x%}；
- 在执行 {n} 条（超期 {n}）；未处理告警 {n} 条（严重 {n}）；
- 最需要关注：{一句话风险 + 建议动作}。

## HTML 版式（唯一的日报输出）

每次生成日报都按下面做：

1. 调用 `read_skill_resource`，参数
   `{"skill":"daily-operations-report","path":"assets/daily-report.html"}`，取到完整模板；
2. **替换模板里所有 `{{...}}` 占位符**为第 1–5 步查到的真实数据；
   百分比条（`--w`）按真实占比填；没有数据的写「无数据」或「—」；
3. 表格里的 `<tr class="sample">` 是示例行：复制成多行替换，最后删掉 `sample` 类；
4. 输出**单文件 HTML**：保留模板的内联样式，不要引入外部 CSS/JS/字体；
5. 保留 `<title>运营日报 · {日期}</title>`，系统会用它作为下载文件名；
6. **HTML 必须放在 ` ```html ` 围栏里**，围栏**前**先写 2–3 行摘要（用户先看结论）；
   围栏之外不要再重复整份 HTML。示例：

   ````
   今日完成 8 条、失败 1 条，完成率 88.9%；UAV-001 有 3 条未闭环严重告警。

   ```html
   <!DOCTYPE html> …整份报告… </html>
   ```
   ````

系统会把围栏里的 HTML 抽成可预览、可下载的产物，聊天里只保留摘要。

## 禁止

- **不得编造**任何接口没有返回的数字、任务号或告警。
- 接口失败时写「该项数据获取失败：{原因}」，不要用估计值填空。
- 某一块「无权限查看」时如实写，不要写成 0。
- 建议必须能落地（谁、做什么、什么时候），不要写「加强管理」这类空话。
