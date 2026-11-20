# 规格 — REQ-AGENT-0026 链路追踪贯通（P0）

- **需求 ID**：`REQ-AGENT-0026`
- **提出人 / 日期**：需求方 / 2026-10-22
- **范围**：只做 traceId 透传，不做 trace 落盘与回放 UI（P1/P2）

## 1. 背景与目标

`drone-agent` 已在一次 `/chat` 内生成 traceId 并写日志，但该 id **不跨服务**：
`drone-mcp-server` 与 `drone-server` 的日志无法与 agent 的同一请求对上，
因此无法还原「一次请求经过哪些服务、每跳多久」。

目标：同一个 traceId 贯穿 `web → agent → drone-mcp-server → drone-server`，
并随 `/chat` 响应回传给前端，为后续 trace 回放提供关联键。

## 2. 范围

### 做（In Scope）

- agent 生成 traceId 后写入 `ToolContext`，显式传到执行器线程；
- `McpToolAdapter` 在每次 MCP 调用带 `X-Trace-Id`；
- `drone-mcp-server` 接收并转发 `X-Trace-Id` 给 `drone-server`；
- `drone-server` 用 Filter 写入 MDC，日志格式输出 traceId；
- `ChatResponse` 增加 `traceId` 字段；
- 关键单元测试：请求头、上游请求头。

### 不做（Out of Scope）

- 不落盘 trace、不做 span 树、不做瀑布图；
- 不引入 OpenTelemetry；
- 不改动已有鉴权与业务语义。

## 3. 验收条件

| 编号 | 前置 | 操作 | 预期 |
|---|---|---|---|
| AC-1 | 三个服务已启动 | 发一次 `/chat` | 响应 `traceId` 非空 |
| AC-2 | 同上 | grep 三个服务日志 | 同一 traceId 能在 agent / MCP / server 日志中命中 |
| AC-3 | 调需要转发用户身份的 MCP 工具 | 查看 MCP 请求头 | 同时包含 `Authorization` 与 `X-Trace-Id` |
| AC-4 | 调不需要转发身份的 MCP 工具（高德） | 查看请求头 | 有 `X-Trace-Id`，无用户 Token |
| AC-5 | 无 traceId 的旧调用 | 调 `/chat` 之外的接口 | 不报错；server MDC 为空 |
| AC-6 | 完整测试 | 跑 `drone-agent` / `drone-mcp-server` 测试 | 全部通过 |

## 4. 约束

- traceId 必须**显式传参**，不能依赖 ThreadLocal（工具跑在执行器线程）；
- 日志与请求头不得包含 JWT 原文；
- 旧调用方不传 `X-Trace-Id` 时必须向后兼容。
