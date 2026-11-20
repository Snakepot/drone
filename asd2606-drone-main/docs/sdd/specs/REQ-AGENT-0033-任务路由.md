# 规格 — REQ-AGENT-0033 任务路由（ReAct / Plan / SubAgent）

- **需求 ID**：`REQ-AGENT-0033`
- **提出人 / 日期**：需求方 / 2026-10-28
- **负责人**：AI（按 SDD 执行）
- **前置**：`ADR-0013`、`REQ-AGENT-0032`、`REQ-AGENT-0031`
- **状态**：已实现 + 一次真实手工冒烟；尚未沉淀自动化 E2E

## 1. 背景与目标

无人机系统的普通用户不关心“子代理/委派/计划”这些概念。只靠提示词让模型自己判断，
会出现“想了想，还是自己查更可控”的摇摆：同一类问题有时委派、有时不委派。

目标：在代码层做**任务路由**，根据用户问题直接选择：

- `REACT`：简单查询、知识问答、单步写操作；
- `PLAN`：内置固定流程、多步/批量写操作、明确要求先给方案；
- `SUBAGENT`：复杂只读分析/诊断（多数据源、逐条下钻、多轮归因）。

用户不需要知道路由存在；路由结果只体现在“这件事由谁来做”。

## 2. 范围

### 做（In Scope）

- `TaskRouter` 规则路由：显式指令、内置计划、知识问答、数据源数量、下钻/分析词、写操作；
- `RouteDecision`：route + agent + reason + scores，供日志与测试；
- `SubAgentService`：路由到 SUBAGENT 时直接执行子 Agent，不必主模型调用工具；
- `ToolCallingService` 接入：REACT / PLAN / SUBAGENT 三分支；
- 移除模型侧 `delegate_to_subagent` 工具：路由是唯一入口；
- 子 Agent 预算：`max-prompt-tokens`（自身累计）+ `min-remaining-tokens`（启动前剩余下限）；
- 路由关闭开关 `agent.route.enabled`，关闭后回到旧行为；
- 单测与真实手工冒烟。

### 不做（Out of Scope）

- 模型分类器（本期规则路由，无额外模型调用）；
- MultiAgent；
- 按用户个性化学习；
- 多语言意图识别；
- 子 Agent 写操作。

## 3. 路由规则（摘要）

**显式优先：**

- “先给方案/先给计划/先规划” → PLAN；
- “用子代理/委派/让子代理分析” → SUBAGENT；
- “直接查/不用子代理/不要委派” → REACT。

**其他：**

- 命中内置计划且不是知识问答 → PLAN（BUILTIN，不审批）；
- 知识/模板/文档类问题 → REACT（即使含“日报”等触发词）；
- 复杂只读：≥2 个数据域 + 对比/分析/下钻 → SUBAGENT；
- 多步/批量写操作 → PLAN（生成计划，必须审批）；
- 单步写操作 → REACT（走既有确认）；
- 其余 → REACT。

## 4. 验收条件

| ID | 场景 | 期望 |
|---|---|---|
| AC-1 | “对比本周和上周的任务完成率和告警数量” | SUBAGENT；主模型不参与 |
| AC-2 | “对比本周和上周的运营” | REACT；主模型直接处理 |
| AC-3 | “出个今天的运营日报” | PLAN；命中内置计划、不审批 |
| AC-4 | “创建一个用户再给他配设备” | PLAN；生成计划、等待审批 |
| AC-5 | “帮我派发任务123” | REACT；写操作走确认 |
| AC-6 | “日报模板怎么改” | REACT；不误跑日报计划 |
| AC-7 | 显式“先给方案/用子代理/直接查” | 覆盖自动路由 |
| AC-8 | `agent.route.enabled=false` | 回到旧行为 |
| AC-9 | 路由日志 | 记录 decision/reason/scores |
| AC-10 | 子 Agent 失败/超时 | 降级 ReAct，并向用户说明 |
| AC-11 | 主请求剩余额度低于阈值 | 不启动子 Agent，直接降级 ReAct |
| AC-12 | 子 Agent 自身累计超 `max-prompt-tokens` | 停止并返回预算提示 |
| AC-13 | 主模型工具目录 | 不再包含 `delegate_to_subagent`（74 个工具） |
