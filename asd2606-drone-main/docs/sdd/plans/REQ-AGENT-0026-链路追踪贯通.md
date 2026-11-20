# 技术方案 — REQ-AGENT-0026 链路追踪贯通（P0）

## 1. 方案概述

traceId 由 `drone-agent` 在请求入口生成，写入 `ToolContext`，随 MCP 调用头
`X-Trace-Id` 传到 `drone-mcp-server`，再原样转发给 `drone-server`。
`drone-server` 用 Servlet Filter 写入 MDC，日志格式统一输出。
`ChatResponse` 回传 traceId，供前端与后续回放使用。

## 2. 影响面

| 维度 | 影响 |
|---|---|
| 接口 | `/chat` 响应新增 `traceId`（新增字段，兼容） |
| 请求头 | MCP 调用新增 `X-Trace-Id`；上游新增同名头 |
| 日志 | `drone-server` console pattern 增加 `%X{traceId:-}` |
| 数据模型 | 无 |
| 兼容性 | 旧调用不带 traceId 时行为不变 |

## 3. 改动清单

| 模块 | 文件 | 改动 |
|---|---|---|
| agent | `tool/ToolContext.java` | 新增 `traceId` 字段与 `withTraceId()`；toString 输出 traceId |
| agent | `service/ConversationService.java` | 生成 traceId 后写入上下文；`ChatResponse` 回传 |
| agent | `service/ChatConfirmService.java` | 确认续跑同样带 traceId 并回传 |
| agent | `dto/ChatResponse.java` | 新增 `traceId` 字段，保留旧构造 |
| agent | `mcp/McpToolAdapter.java` | 每次调用加 `X-Trace-Id` |
| mcp | `web/McpEndpoint.java` | 接收 `X-Trace-Id` 并向下传 |
| mcp | `invoke/ToolInvoker.java` | 转发 `X-Trace-Id`，日志带 traceId |
| server | `config/TraceFilter.java` | 读头写 MDC，请求结束清理 |
| server | `application.yml` | console pattern 增加 traceId |
| web | `api/agent.ts` | `AgentChatReply` 增加可选 `traceId`（仅类型） |

## 4. 关键决策

| 决策点 | 选择 | 理由 |
|---|---|---|
| 传递方式 | 请求头 | 跨进程、无侵入、日志/网关可见 |
| 异步线程 | 显式放进 ToolContext | 与既有身份透传一致，避免 ThreadLocal 坑 |
| 生成方 | agent | 已有 TraceIds，保证一次对话一个 id |
| 存储 | P0 不落盘 | 先验证贯通，再在 P1 做 JSONL span |
| 兼容 | 字段/头可选 | 旧客户端与旧调用不受影响 |

## 5. 测试策略

- `McpToolAdapterTest`：断言 `X-Trace-Id` 与 Authorization 同时存在；
- `ToolInvokerTest`：断言上游收到 `X-Trace-Id`；
- `ToolContextTest`：`withTraceId` 不丢身份、toString 不含 Token；
- 手工：一次 `/chat` 后 grep 三个服务日志。

## 6. 风险与回滚

| 风险 | 应对 |
|---|---|
| 上游不识别新头 | HTTP 允许未知头，忽略即可 |
| MDC 清理遗漏 | Filter `finally` 中 remove |
| 日志格式变更影响采集 | 只改 console pattern；如需保留旧格式可回滚 yml |
| 回滚 | 移除 X-Trace-Id 头与字段即可，无数据变更 |
