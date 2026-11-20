# 任务拆解 — REQ-AGENT-0026 链路追踪贯通（P0）

| 编号 | 任务 | 涉及模块 | 完成标志 | 依赖 | 状态 |
|---|---|---|---|---|---|
| T-1 | 规范三件套 | docs/sdd | 本文件与 spec/plan 入库 | - | 已完成 |
| T-2 | ToolContext 增加 traceId | drone-agent | `withTraceId` 可用；旧构造兼容 | T-1 | 已完成 |
| T-3 | ChatResponse 回传 traceId | drone-agent | `/chat` 响应含 traceId | T-2 | 已完成 |
| T-4 | MCP 调用带 X-Trace-Id | drone-agent | 适配器测试通过 | T-2 | 已完成 |
| T-5 | MCP 服务接收/转发 | drone-mcp-server | 上游测试收到头 | T-4 | 已完成 |
| T-6 | server MDC 与日志格式 | drone-server | TraceFilter 测试通过 | T-5 | 已完成 |
| T-7 | 测试与手工验证 | 三端 | 单测通过；三服务 grep 待真机 | T-6 | 部分完成 |

## 执行规则

- 一次只做一条任务；
- 不改业务语义，只加追踪字段/头；
- 不记录 Token 原文；
- 旧调用不传 traceId 时必须正常。
