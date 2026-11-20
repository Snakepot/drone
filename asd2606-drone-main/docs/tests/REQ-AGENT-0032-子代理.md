# REQ-AGENT-0032 实施记录：只读运营分析子 Agent

- **日期**：2026-10-28
- **范围**：SubAgent 定义/目录/执行器/委派工具 + `ops-analyst`
- **状态**：已实现 + 一次真实手工冒烟；**尚未沉淀为可重复自动化 E2E**

## 一、实现内容

| 层 | 内容 |
|---|---|
| 定义 | `subagent/SubAgentDefinition`：name、tools、maxRounds、timeoutSeconds、readOnly、prompt（正文） |
| 目录 | `SubAgentCatalog`：加载 `resources/subagents/*.md`，解析 frontmatter + 正文 |
| 执行 | `SubAgentRunner`：独立上下文、白名单只读工具、权限校验、轮次/超时/预算、trace |
| 门面 | `SubAgentService`：供 `TaskRouter` 直接执行；REQ-0033 起不再向模型暴露委派工具 |
| 场景 | `subagents/ops-analyst.md`（frontmatter + 正文提示词） |
| 接入 | 由 `TaskRouter` 直接路由（REQ-0033）；工具目录仍为 74 个（不再新增委派工具） |
| 超时 | 新增工具级 `timeout-ms`；委派工具 90s，其余工具仍用全局 15s |

## 二、真实冒烟

> 本节是 REQ-0032 工具入口时期的记录；REQ-0033 后改为 `TaskRouter` 直连，
> 新的路由冒烟见 `docs/tests/REQ-AGENT-0033-任务路由.md`。

环境：8083 独立实例 + 真实 `drone-mcp-server` 8082 + 真实 DeepSeek；admin 账号。

请求：

> 请调用 delegate_to_subagent，让 ops-analyst 对比本周和上周的任务完成率与告警数量，给出 3 条建议。

关键日志：

```text
【子代理】加载完成：1 个（[ops-analyst]）
【工具编排】可用工具 75 个
【子代理】收到委派：agent=ops-analyst，traceId=b233bf60
【子代理】开始：maxRounds=4，tools=[alert_list, analytics_dispatch_block, …]
【子代理】第 1 轮：模型请求 2 个工具
【子代理】工具完成：analytics_overview 27ms / analytics_overview 57ms
【子代理】第 2 轮：模型请求 4 个工具
【子代理】工具完成：alert_list / alert_list / task_list / task_list
【子代理】第 3 轮：模型请求 2 个工具
【子代理】工具完成：alert_list / task_list
【子代理】完成：status=ok，轮次=4，工具调用=8，耗时 15241ms，摘要 1888 字
【工具编排】工具「delegate_to_subagent」执行完毕，耗时 15243ms
```

主 Agent 最终答复引用了子代理的结论、数据窗口与“无数据/不可环比”的判断。

## 三、REQ-0033 后的变化

- 委派工具已移除，由 `TaskRouter` 直连 `SubAgentService`；
- 子 Agent 超时由 `definition.timeout_seconds` 控制；
- 子 Agent 增加预算闸（`max-prompt-tokens` / `min-remaining-tokens`）；
- 工具目录仍为 74 个。

## 四、测试

- `drone-agent`：**419 passed**
  - `SubAgentRunnerTest`：独立上下文、白名单/写工具拒绝、轮次上限、定义校验、预算不足拒绝、Markdown 定义加载；
  - `ToolPropertiesTest` 仍为 4 个本地工具；
  - 原 Plan/ReAct 测试保持通过。
- 注：`REQ-AGENT-0033` 起，复杂只读任务由 `TaskRouter` 直接路由到子 Agent，
  不再向主模型暴露 `delegate_to_subagent` 工具。
- 前端无改动。

## 五、已知限制

- 子 Agent 取消暂不能从主 Agent 穿透（主 Agent 取消后，子 Agent 仍会跑完/超时）；
- 子 Agent 的 `chatStep` 仍会带上主 `assistant.system` 提示词，上下文隔离是“会话/工具结果隔离”，不是“系统提示词完全隔离”；
- 子 Agent 只读；写操作仍未接入；
- 子 Agent 由 `TaskRouter` 自动路由，用户无需知道；
- 无 MultiAgent；
- 真实冒烟只覆盖 `ops-analyst` 一个场景，尚未自动化。
