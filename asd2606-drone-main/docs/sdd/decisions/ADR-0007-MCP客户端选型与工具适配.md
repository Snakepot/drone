# ADR-0007 MCP 客户端手写实现，工具按别名前缀并入既有注册表

- **状态**：已接受
- **日期**：2026-10-21
- **决策人**：项目负责人
- **记录人**：AI（按规范登记）
- **关联**：[`specs/REQ-AGENT-0011-MCP工具接入.md`](../specs/REQ-AGENT-0011-MCP工具接入.md)、[ADR-0004](./ADR-0004-工具调用与无Key联网搜索.md)、[ADR-0005](./ADR-0005-危险工具治理与人工确认.md)

## 背景

助手需要接入高德 MCP Server，并保留再接其他 MCP Server 的能力。落地前有三个必须先定的问题：

1. **用什么实现**：官方 Java SDK，还是手写 JSON-RPC 客户端？
2. **按哪个协议版本实现**：当前规范最新是 2026-07-28（无状态核心），而高德实际返回 2025-03-26。
3. **怎么和既有工具框架合并**：`ToolRegistry` 要求「行为 ↔ 元数据」严格 1:1，而 MCP 工具是运行时发现的。

## 决策

### 1. 手写客户端，不引官方 Java SDK

- 只用到 4 个 RPC：`initialize`、`notifications/initialized`、`tools/list`、`tools/call`。
- 官方 SDK 会引入 reactor 等较大依赖，且仍需针对高德做特殊处理（无 `Mcp-Session-Id`、
  响应可能是 JSON 也可能是 SSE、密钥在 query string 里）。
- 本仓库既有的 HTTP 客户端（DeepSeek、内部鉴权、嵌入、Milvus）全部是手写 `java.net.http.HttpClient`，
  手写与本仓库的排障方式一致：日志能精确落在每个请求上。
- **代价（如实登记）**：协议升级（例如 2026-07-28 的 MRTR、Extensions）需要自己跟。
  因此 `McpClient` 只暴露 `listTools` / `callTool`，未来换 SDK 只需替换实现类。

### 2. 以 2025-03-26 生命周期为准，对新版本保持宽容

- 高德 `initialize` 返回 `2025-03-26`，且不返回会话头。
- 客户端行为：`initialize` 失败不视为致命；拿到 `Mcp-Session-Id` 就回带，没有就不带；
  `notifications/initialized` 返回 202 空体，按通知处理、不解析响应体。
- 这样对 2026-07-28 的无状态服务器（移除握手）也不会报错。

### 3. 工具名加 `别名__` 前缀，启动时与本地工具合并

- 多服务器可能都有 `search` 这类通用名，加前缀后天然不冲突。
- 发现结果同时生成「行为」与「元数据」，成对交给 `ToolRegistry`，1:1 校验继续成立。
- 发现放在 Bean 构造期而不是 `ApplicationRunner`：后者执行时端口已开放，会出现「服务已可访问但工具列表不全」的窗口。

### 4. 发现失败默认降级

- 地图服务不可用不应拖垮整个助手；`fail-fast=true` 时才让启动失败。
- 降级时该服务器的工具与描述**成对缺席**，注册表不会出现半套。

## 后果

- 正面：无新增依赖；协议细节可控；日志可精确到每个 RPC；新增 MCP Server 只改配置。
- 负面：协议演进需自行跟进；SSE 与分页等边界要自己测。
- 明确**不选**：
  - 不选官方 SDK（依赖重、仍需特判）；
  - 不选 `ApplicationRunner` 延迟发现（注册表短暂不一致）；
  - 不选「远程工具元数据写进 `agent.tool.catalog`」（会产生第二份真相，且与服务端实际能力漂移）。
- 本期**不做**：`resources`/`prompts`/`sampling`、OAuth、stdio 传输。需要时优先补 `McpTransport` 实现，而不是改上层。
