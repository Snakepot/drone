# 技术方案 — REQ-AGENT-0011 智能助手通过 MCP 调用高德 MCP Server

> 输入是 `specs/REQ-AGENT-0011-MCP工具接入.md`。

- **需求 ID**：`REQ-AGENT-0011`
- **作者 / 日期**：AI / 2026-10-21
- **评审人 / 评审日期**：需求方 / 2026-10-21（方案已确认）

## 1. 方案概述

在 `drone-agent` 新增 `cn.tedu.droneagent.mcp` 包：手写一个 Streamable HTTP 的 MCP 客户端，
启动时按配置逐个连接 MCP Server、`initialize`、`tools/list`，把发现的工具包装成现有的 `Tool`，
与本地工具合并成一份 `ResolvedToolCatalog`，再交给既有的 `ToolRegistry` / `ToolGovernance`。
工具名统一加 `别名__` 前缀，避免多服务器撞名。高德不可达时默认降级，不阻断启动。

## 2. 影响面

| 维度 | 影响 | 说明 |
|---|---|---|
| 接口 | 无 | 对前端/其他服务无新增或变更接口 |
| 数据模型 | 无 | 不落库、不落盘 |
| 兼容性 | 不兼容（工具层） | `get_flight_weather` 被移除，模型侧工具名变化；无外部调用方依赖工具名 |
| 性能 | 有 | 启动最多增加 `startup-budget`(8s)；每轮请求多约 15 个工具定义（≈2k tokens） |
| 其他服务 | 无 | 不依赖也不修改 `drone-server` |

## 3. 接口清单

无新增对外接口。内部新增（Java 层）：

| 类型 | 签名 | 说明 |
|---|---|---|
| `McpTransport` | `JsonNode request(JsonNode rpc)` / `void notify(JsonNode rpc)` | 传输抽象 |
| `McpClient` | `List<McpToolInfo> listTools()` / `JsonNode callTool(String, String)` | 协议层 |
| `McpToolset` | `List<Tool> tools()` / `List<ToolDescriptor> descriptors()` | 聚合层 |

## 4. 数据模型变更

- 变更内容：无。
- 迁移脚本：无。
- 回滚方案：`agent.mcp.enabled=false` 即回到「只有本地工具」的状态。
- 是否需要停机：否。

## 5. 关键设计决策

| 决策点 | 备选方案 | 结论 | 理由 |
|---|---|---|---|
| 客户端实现 | 官方 Java SDK / 手写 | **手写** | 只用 4 个 RPC；SDK 引入 reactor 等依赖且仍需特判高德行为；仓库既有 HTTP 客户端均手写 |
| 协议版本 | 只实现 2026-07-28 / 兼容 2025-03-26 | **以 2025-03-26 为准，宽容新版本** | 高德返回 2025-03-26；新规范声明老客户端仍可用 |
| 工具元数据 | 写进 `agent.tool.catalog` / 用服务端下发的 | **用服务端的** | 避免第二份真相；本地只保留描述覆盖能力 |
| 命名 | 原名 / 别名前缀 | **`别名__原名`** | 多服务器可能重名；符合函数名正则 |
| 发现时机 | Bean 构造 / `ApplicationRunner` | **Bean 构造** | Runner 在端口开放后执行，会短暂出现注册表不一致 |
| 发现失败 | 启动失败 / 降级 | **默认降级，可配 fail-fast** | 地图服务故障不应拖垮助手 |
| 暴露工具数 | 全部 15 个 / 白名单 | **白名单机制保留，默认留空 = 全部 15 个** | 需求方 2026-10-21 明确要全部；需要收敛时往 include 加名字即可 |

## 6. 测试策略

| 验收条件 | 测试层级 | 测试位置 |
|---|---|---|
| AC-1 / AC-8 | 单元（桩服务器） | `McpToolsetTest`、`McpClientTest` |
| AC-2 | 手工冒烟（真实 key） | 见第 7 节验证 |
| AC-3 / AC-4 | 单元 | `McpToolsetTest` |
| AC-5 | 单元 | `McpToolAdapterTest` |
| AC-6 | 人工核对日志 | 冒烟记录 |
| AC-7 | 静态检查 | `grep -r get_flight_weather` |
| AC-9 | 单元 | `StreamableHttpTransportTest` |

## 7. 风险与回滚

| 风险 | 触发条件 | 影响 | 应对 |
|---|---|---|---|
| 高德不可达 | 网络/服务故障 | 启动多等 8s，工具缺失 | 降级 + WARN；修复后重启即恢复 |
| 工具描述过长 | 白名单放宽 | prompt 成本上升 | 默认白名单；监控 token |
| 唤端链接被误当动作 | 模型误解 `maps_schema_*` | 用户以为已下单 | prompt 写明「只给链接」 |
| 密钥泄漏 | 日志/异常打印 URL | 凭据泄露 | 统一脱敏函数 + 测试 |

- 上线方式：开关 `agent.mcp.enabled`（默认开）。
- 回滚步骤：置 `agent.mcp.enabled=false` 并重启；或回退版本。
- 回滚后数据修复：无。

## 8. 与项目宪法的一致性

- §2 依赖方向：MCP 客户端在 `drone-agent` 内部，不访问 `drone-server` 的库。
- §4 安全：密钥外置、日志脱敏、服务端判定，未突破安全边界。
- §5 测试：单测使用 JDK `HttpServer` 桩件，不依赖真实外部环境；真实调用归冒烟。
- §7 配置：新增配置项集中在 `agent.mcp.*`，无硬编码地址与密钥。
