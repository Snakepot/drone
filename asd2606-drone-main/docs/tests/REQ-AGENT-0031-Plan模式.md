# REQ-AGENT-0031 实施记录：Plan 模式

> **功能状态：已实现，功能未测试。** 已完成单测、前端类型检查与**一次手工冒烟**
> （8083 + 真实 MCP + 真实 DeepSeek，两种流程各跑一次）；
> **尚未沉淀为可重复的自动化 E2E**，不能视为“功能已验证”。

- **日期**：2026-10-27
- **范围**：内置计划（日报）+ 生成计划（审批）+ 前端计划卡
- **环境**：本机；`drone-agent` 单测

## 一、实现内容

| 层 | 内容 |
|---|---|
| 计划模型 | `plan/Plan`、`PlanStep`、`PlanSummary` |
| 内置计划 | `resources/plans/daily-operations-report.json`；`PlanCatalog` 启动加载/校验/触发词匹配 |
| 生成计划 | `PlanPlanner` 调模型输出 JSON；Prompt 在 `prompts/plan/planner.md` |
| 存储 | `PlanStore`：`data/plans/{userId}/{planId}.json`，TTL 30m，原子消费 |
| 执行 | `PlanExecutor`：依赖排序、只读并行、写串行、取消、危险工具拒绝、`synthesize` 汇总 |
| 台账 | 计划内写步骤复用 `WriteJournal`：operationId=`plan-{planId}-{stepId}`，记 started/终态 |
| 接入 | `ToolCallingService` 入口：内置命中 → 直接执行；要求先给方案 → 生成并等待审批 |
| 审批 | `POST /chat/plan/confirm`；批准执行、拒绝作废；`PlanStore` 保证只消费一次 |
| 响应 | `ChatResponse` / `ChatStreamOutcome` / SSE `done` 带 `plan` 摘要 |
| 前端 | 计划卡片：步骤、状态、批准/拒绝 |

## 二、两条路径

### 内置计划（不审批）

用户说“出个日报” → `PlanCatalog` 命中 `daily-operations-report` →
按 JSON 的 `s1~s6` 执行（5 个数据源 + synthesize HTML）→ `done.plan` 展示步骤。

### 生成计划（必须审批）

用户说“先给方案：对比本周和上周的运营情况……” →
`PlanPlanner` 生成 JSON → `PlanStore` 落盘 → 返回 `PENDING_APPROVAL` →
前端显示计划卡 → 用户批准 → `PlanExecutor` 执行 → 返回最终答复与步骤状态。
拒绝则计划只读消费、不执行任何工具。

## 三、验证

- `drone-agent`：**407 passed**
  - `PlanCatalogTest` 4 条：触发词、环、生成计划非法工具、内置计划缺工具容忍；
  - `DailyPlanCatalogTest` 1 条：真实日报 JSON 可加载、可命中、步骤与 Skill 对齐；
  - `PlanStoreTest` 2 条：落盘/重启可读/只消费一次、用户隔离；
  - `PlanExecutorTest` 5 条：依赖执行 + 合成、失败停止、取消、危险工具拒绝、步骤参数引用；
  - `ToolCallingServiceTest` 2 条：内置计划不走 ReAct、生成计划未批准零执行/批准后执行。
- `drone-web`：type-check、lint 通过（0 warnings / 0 errors）。

## 四、已验证 / 未验证

已验证（手工冒烟，2026-10-27）：

- 内置日报：6 步全 DONE，约 28.6s，生成 14.8KB HTML；
- 生成计划：Planner 约 4.1s 产出 3 步计划，批准后执行约 8.3s；
- 生成流程首次因 `${week.*}` 占位符未解析失败，修复后重跑成功。

未验证 / 未自动化：

- 未沉淀为可重复的自动化 E2E（日志只在 `/tmp/plan-*-flow.log`）；
- 未跑写入型“临时多实体配置”（避免改动业务数据）；
- 未跑流式审批、未跑计划执行中途取消的端到端场景。

## 五、已知限制 / 后续

- 内置触发词包含“日报”，范围较宽；后续可改为入口/菜单显式触发；
- 生成计划只由“先给方案/先给计划/先规划/plan”显式触发，未做自动复杂度路由；
- 计划中的危险工具由 Planner 与 Executor 双重拒绝，尚未接入逐次确认票据；
  **这是 MVP 实现限制，不是产品强制要求**：Planner 的“危险工具默认不引入”“写操作以必需为限”
  都是默认建议，真正的强制边界是 RBAC、计划审批、危险确认与预算；
- 计划执行中途失败只停并报告，没有自动重规划；
- 计划落盘用于“等待审批”，未做执行中途断点恢复；
- 只读并行步骤不逐个推送状态，只在串行步骤/结束时更新。

## 六、演示脚本

1. **内置日报**：直接说“出个今天的运营日报”——无审批，计划卡显示 5 个数据步骤 + HTML 合成。
2. **生成计划（只读）**：
   “先给方案：对比 10 月 20–26 日和前一周的完成率、按时率、派发积压、告警，给 3 条建议。”
   预期：先出计划卡；批准后才取数；拒绝则零执行。
3. **生成计划（写入，测试数据）**：
   “先给方案：创建一个临时运维账号，配一台备用机，再建一条巡检航线，明天派一条任务。”
   预期：计划卡包含依赖步骤；批准后按顺序执行；每步写操作有 operationId。
