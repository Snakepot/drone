# 任务拆解 — REQ-AGENT-0012 新建 drone-mcp-server

> 输入是 `plans/REQ-AGENT-0012-drone-mcp-server.md`。

- **需求 ID**：`REQ-AGENT-0012`
- **负责人 / 日期**：AI / 2026-10-21

## 任务表

| 编号 | 任务 | 涉及模块 | 产出 | 完成标志 | 依赖 | 状态 |
|---|---|---|---|---|---|---|
| T-1 | 规范三件套与 ADR-0008 | docs/sdd | spec/plan/tasks/ADR | 评审通过 | - | 已完成 |
| T-2 | 新工程骨架 | drone-mcp-server | pom、启动类、`/health`、回环绑定 | 上下文启动，`/health` 返回 UP | T-1 | 已完成 |
| T-3 | 快照加载与后台刷新 | catalog | `openapi.json` + 加载器 + 刷新 | 上游不可达仍启动并返回 70 个工具 | T-2 | 已完成 |
| T-3b | 快照同步脚本 | scripts | `sync-openapi.sh` | 能从 8080 覆盖快照 | T-3 | 已完成 |
| T-4 | 工具目录构建 | catalog | 过滤/命名/Schema 内联 | 70 个、唯一、无 `$ref`、无 offset | T-3 | 已完成 |
| T-5 | `/mcp` JSON-RPC | web | 端点与错误码 | initialize/list/call/405/-32601 用例通过 | T-4 | 已完成 |
| T-6 | 上游调用 | invoke | 分流、转发鉴权、响应映射 | 桩上游用例通过 | T-5 | 已完成 |
| T-7 | agent 每调用请求头 | drone-agent mcp | 传输层与客户端重载 | 两个 token 的用例通过 | T-6 | 已完成 |
| T-8 | 身份透传 | drone-agent | LoginUser/ToolContext/adapter | toString 不泄漏、缺 token 不发请求 | T-7 | 已完成 |
| T-9 | 配置与发现重试 | drone-agent config | forward-authorization、server 条目、重试 | 绑定测试通过 | T-8 | 已完成 |
| T-10 | 治理默认值 | drone-agent yml | 危险清单、role-tools | 启动校验通过 | T-9 | 已完成 |
| T-11 | 提示词 v6 与文档 | prompts/docs | 系统提示词、README、手册 | 文档评审 | T-10 | 已完成 |
| T-12 | 真机冒烟 | 运行 | 冒烟记录 | admin 成功、pilot 403、确认流程 | T-11 | 部分完成（确认续跑被 REQ-0006 既有缺陷阻断） |
| T-13 | 原始报文日志 | agent / mcp-server | 开关 + 原始请求/响应日志 + 断言测试 | 两边日志出现 `【MCP-RAW】` 原文且无 `Bearer` | T-12 | 已完成 |

## 验收条件覆盖

| 验收条件 | 覆盖任务 |
|---|---|
| AC-1 | T-3 |
| AC-2 | T-4 |
| AC-3 / AC-4 / AC-8 | T-6 / T-12 |
| AC-5 | T-6 / T-8 |
| AC-6 | T-10 / T-12 |
| AC-7 | T-6 / T-12 |
| AC-9 | T-9 |
| AC-10 | T-3b / T-4 |

## 执行规则（AI 与人都适用）

- 【强制】一次只做一条任务，做完一条再开下一条。
- 【强制】每条任务完成后更新「状态」列，并给出可验证的证据（测试结果、命令输出）。
- 【强制】执行中发现规格或方案有问题，停止当前任务，先回 specs/plans 改并评审。
- 【强制】不得擅自扩大范围；发现「顺手也该改」的地方另提需求。

## 执行记录（2026-10-21）

- `drone-mcp-server`：21 个用例通过；快照生成 70 个工具。
- `drone-agent`：253 个用例通过（新增 5 个：Token 透传、失败关闭、toString 遮蔽等）。
- 真机：上游不可达仍启动；admin 查询成功；pilot 调删除得到 403；无身份拒绝；
  完整自然语言链路（8083 独立实例）跑通。
- 已知问题：危险工具「拒绝」后的续跑触发 REQ-0006 既有缺陷
  （`reasoning_content must be passed back`，HTTP 400），本次不修，记入 `docs/tests/REQ-AGENT-0012-端到端回归.md`。

### T-13 执行记录（2026-10-21）

- 开关：`agent.mcp.log-raw-payload`、`drone.mcp-server.log-raw-payload`（默认 true，生产建议 false）。
- 真机（8082 + 8083）：agent 打印 JSON-RPC 请求/响应、工具入参/结果；
  server 打印 MCP 请求/响应、上游请求与 `{state,message,data}` 原始响应。
- 两边日志 `grep -c "Bearer "` 均为 **0**。
- 用例：`McpRawLoggingTest`（两端各 2 条），全量 23 + 255 通过。
