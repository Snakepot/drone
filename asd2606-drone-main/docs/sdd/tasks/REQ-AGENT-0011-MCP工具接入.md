# 任务拆解 — REQ-AGENT-0011 智能助手通过 MCP 调用高德 MCP Server

> 输入是 `plans/REQ-AGENT-0011-MCP工具接入.md`。

- **需求 ID**：`REQ-AGENT-0011`
- **负责人 / 日期**：AI / 2026-10-21

## 任务表

| 编号 | 任务 | 涉及模块/层 | 产出 | 完成标志 | 依赖 | 状态 |
|---|---|---|---|---|---|---|
| T-1 | 写规范三件套与 ADR | docs/sdd | spec / plan / tasks / ADR-0007 | 待定项清空，方案已确认 | - | 已完成 |
| T-2 | 新增 `McpProperties` 与 `agent.mcp.*` 配置 | config / resources | 配置类 + yml + 测试覆盖 | 上下文能加载，`ToolPropertiesTest` 通过 | T-1 | 已完成 |
| T-3 | 实现传输层 | mcp | `McpTransport` / `StreamableHttpTransport` | JSON、SSE、202、500、超时、脱敏 7 个用例通过 | T-2 | 已完成 |
| T-4 | 实现协议客户端 | mcp | `McpClient` | initialize 容错、session 回带、分页、调用 4 类用例通过 | T-3 | 已完成 |
| T-5 | 实现工具适配与聚合 | mcp / tool | `McpToolAdapter` / `McpToolset` | 前缀、过滤、重名、isError、异常不外抛 用例通过 | T-4 | 已完成 |
| T-6 | 下线 `get_flight_weather` | tool / config / prompts | 删除 5 个类与 2 个测试、catalog、prompt v5 | 全仓 grep 无残留；`mvn test` 通过 | T-5 | 已完成 |
| T-7 | 装配 `ResolvedToolCatalog` | config | `McpConfig` / `ResolvedToolCatalog` | 离线上下文测试通过 | T-6 | 已完成 |
| T-8 | 补齐全流程日志 | mcp / config | 发现与调用日志 | 按日志清单逐条核对通过 | T-7 | 已完成 |
| T-9 | 手册与真机冒烟 | docs / 运行 | `MCP接入-初级工程师手册.md`、冒烟记录 | 一次真实天气调用可在日志中串起 | T-8 | 已完成 |

## 验收条件覆盖

| 验收条件 | 覆盖任务 |
|---|---|
| AC-1 | T-7 |
| AC-2 | T-9 |
| AC-3 | T-2 / T-5 |
| AC-4 | T-5 |
| AC-5 | T-5 |
| AC-6 | T-8 |
| AC-7 | T-6 |
| AC-8 | T-5 / T-7 |
| AC-9 | T-3 |

## 执行规则（AI 与人都适用）

- 【强制】一次只做一条任务，做完一条再开下一条。
- 【强制】每条任务完成后更新「状态」列，并给出可验证的证据（测试结果、命令输出）。
- 【强制】执行中发现规格或方案有问题，**停止当前任务**，先回 `specs/` 或 `plans/` 改并评审。
- 【强制】不得擅自扩大范围；发现「顺手也该改」的地方，记下来作为新需求提，不要夹带。

## 执行记录（2026-10-21）

- `mvn clean test` → **248 passed**（原 233，移除天气 10 个用例、新增 MCP 25 个）。
- 真实联调（临时用例，跑完即删）：高德 `initialize` 返回 `2025-03-26`、无 session；
  `tools/list` 按白名单保留 2 个；`amap__maps_weather` 调用耗时 103ms、返回 886 字符。
- 全仓 `grep get_flight_weather`：仅提示词 changelog 中保留历史说明。
- 说明：T-2 原计划一并删除 `ToolProperties.requestTimeout`，实际随 T-6 删除天气代码时一起完成
  （否则中间态无法编译）。
