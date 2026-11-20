# 技术方案 — REQ-AGENT-0005 工具框架增强（管理 / 并行 / 追踪）

> 输入是 [`specs/REQ-AGENT-0005-工具框架增强.md`](../specs/REQ-AGENT-0005-工具框架增强.md)。本文件回答**怎么做**，并证明不会破坏既有东西。

- **需求 ID**：`REQ-AGENT-0005`
- **作者 / 日期**：开发 / 2026-10-14
- **评审人 / 评审日期**：待评审

## 1. 方案概述

三件事同一次改：

1. **工具元数据外置 + 注册收口**：把 name / description / 参数 Schema / 能否并行从**代码**移进**配置文件**
   （`agent.tool.catalog`）；新增 `ToolRegistry`，启动时把「配置（元数据）」与「Bean（行为）」按**工具名**
   合并并校验，对不上就启动失败。`Tool` 接口只留 `name()` + `invoke(...)`（不再有 `definition()`）。
2. **同轮并行**：一轮里若所有 `tool_calls` 都是**只读**工具，并发执行（有界线程池 + 每工具超时），
   **结果顺序保持与 `tool_calls` 一致**；只要有一个非只读，该轮**退回串行**。
3. **追踪**：编排层与模型/工具调用日志补**耗时**；每次 `/chat` 生成 **`traceId`**，
   **显式传参**贯穿（不靠 ThreadLocal —— 本项目已踩过跨线程取不到值的坑）。

## 2. 影响面

| 维度 | 影响 | 说明 |
|---|---|---|
| 接口 | **无新增 / 无契约变更** | `/chat` 不变；前端不动 |
| 数据模型 | **无** | 不落库；新增的是**配置文件内容**（`agent.tool.catalog`） |
| 配置 | **新增** | `agent.tool.catalog`（工具元数据列表）+ `agent.tool.parallel-enabled` / `concurrency` / `invoke-timeout` |
| 兼容性 | **兼容** | 关闭并行即回到串行；`enabled=false` 仍回纯对话 |
| 性能 | **改善** | 一轮 N 个只读工具：总耗时从 `Σ` 降到 `max` |
| 其他服务 | **无** | 纯 `drone-agent` 内 |

## 3. 接口清单

| 方法 | 路径 | 入参 | 出参 | 鉴权 | 幂等 |
|---|---|---|---|---|---|
| POST | `/chat` | 不变 | 不变（reply 可能基于并行工具结果） | `agent:chat` | 否 |

> **无新增接口**。

## 4. 数据模型变更

- 变更内容：**无**（表结构、记忆落盘格式均不变）
- 迁移脚本：无
- 回滚方案：**无数据需回滚**；配置回退即可
- 是否需要停机 / 执行窗口：需要一次**重启**（配置与 Bean 变更）

### 4.1 配置文件长什么样（新）

```yaml
agent:
  tool:
    enabled: true
    max-rounds: 3
    request-timeout: 5s
    parallel-enabled: true        # 有只读工具并发执行
    concurrency: 4                # 并发上限
    invoke-timeout: 15s           # 单工具执行超时
    catalog:                      # 工具元数据（与代码行为按 name 关联）
      - name: get_flight_weather
        description: 查询指定城市的当前天气（气温、风速、降水），用于判断是否适合无人机起飞。
        parallel-safe: true       # 只读，可并行
        parameters:
          type: object
          properties:
            place: { type: string, description: 城市名，如 北京、上海 }
          required: [place]
```

> 元数据从 `WeatherTool` 的常量**搬到这里**；`WeatherTool` 只保留 `name()` 与 `invoke()`。

## 5. 关键设计决策

| 决策点 | 备选方案 | 结论 | 理由 |
|---|---|---|---|
| 元数据放哪 | ①代码常量（现状）②**配置文件** | **②** | 需求方明确要求；改描述/参数不必改代码、重启即可（AC-7） |
| 注册收口 | ①`ToolCallingService` 内拼 Map ②**独立 `ToolRegistry`** | **②** | 校验（重名、元数据与行为对不上）有唯一归属；编排层只管用（需求方已确认要） |
| `Tool` 接口 | ①保留 `definition()` ②**只留 `name()` + `invoke()`** | **②** | 元数据已外置，`definition()` 无内容可返；避免「两处真相」 |
| 并行怎么实现 | ①`CompletableFuture` 组合 ②**有界固定线程池 + `Future.get(timeout)`** ③虚拟线程（Java 17 无） | **②** | 代码最简单、超时好控；Java 17 无虚拟线程 |
| 谁可以并行 | ①全部并行 ②**仅当本轮全部为 `parallel-safe`** | **②** | 有副作用的工具并发风险高；保守但安全，且满足 AC-2 |
| 结果顺序 | ①按完成顺序 ②**按 `tool_calls` 原序** | **②** | `tool` 消息必须与 `tool_call_id` 对应，顺序错会扰乱模型 |
| `traceId` 怎么传 | ①MDC + 线程池装饰器 ②**显式参数贯穿** | **②** | 本项目「ThreadLocal 跨线程取不到」是已知坑；显式传参最稳，代价是方法签名多一个参数 |
| 超时落点 | ①只在 HTTP 客户端设 ②**编排层 `Future.get(timeout)` 兜底** | **②** | 防止某个工具实现忘了设超时，把整轮拖死 |

## 6. 测试策略

| 验收条件 | 测试层级 | 测试位置 |
|---|---|---|
| AC-1 | 单元 | `ToolRegistryTest`（重名 / 元数据与行为对不上 → 抛错） |
| AC-2 | 单元（**用 `CyclicBarrier` 证明并发**，不用计时，避免 flaky） | `ToolCallingServiceTest`（新增并行用例） |
| AC-3 | 单元 | `ToolCallingServiceTest`（一个工具超时/抛错，其他照常） |
| AC-4 | 单元 | `ToolCallingServiceTest` + `ChatServiceToolTest`（断言日志含 `elapsed` 与 `traceId`，或对计时/生成工具单测） |
| AC-5 | 单元 | `ToolCallingServiceTest`（关闭并行 → 串行，与现状一致） |
| AC-6 | 单元 | `ToolRegistryTest`（加一条配置 + 一个桩 Tool 即可注册成功） |
| AC-7 | 单元 | `ToolDescriptorTest`（description 取自配置，不来自代码） |
| AC-8 | 单元 | 沿用现有脱敏断言 |

- 全部用**桩工具 / 固定配置**，**不依赖真实网络**（宪法第 5 节 AIR）。

## 7. 风险与回滚

| 风险 | 触发条件 | 影响 | 应对 |
|---|---|---|---|
| 工具并发不安全 | 某工具被标 `parallel-safe` 但实际有副作用 | 数据竞态 | 默认**只标只读工具**；`parallel-enabled` 可一键关；保守策略（非全只读即串行） |
| 线程池拖住请求 | 工具卡死 | 整轮变慢 | `invoke-timeout` + `Future.get` 超时兜底 |
| 配置与行为对不上 | 改了 catalog 忘了改代码（或反之） | 运行期缺工具 | **启动期校验**（AC-1）直接失败，不留到运行时 |
| 日志量上升 | 每次调用都记耗时 | 噪音 | INFO 只留耗时摘要；明细仍 DEBUG |
| 换配置后行为变化 | `concurrency` / `invoke-timeout` 调错 | 并发过高或频繁超时 | 给保守默认（4 / 15s），并在部署文档说明 |

- 上线方式：**配置开关**（`parallel-enabled`）
- 回滚步骤：`parallel-enabled=false`（或整体 `agent.tool.enabled=false`）后重启；**无需数据修复**
- 回滚后数据是否需要修复：**不需要**

## 8. 与项目宪法的一致性

- **第 1 节 技术栈**：不引入新依赖（JDK `HttpClient` + `java.util.concurrent`）。**一致**。
- **第 2 节 结构/依赖方向**：新代码仍在 `drone-agent` 的 `tool/`、`config/`、`service/`；单向依赖。**一致**。
- **第 3 节 接口契约**：`/chat` 契约不变，无新增错误码。**一致**。
- **第 4 节 安全红线**：日志脱敏红线不变；权限判定在服务端。**一致**。
- **第 5 节 测试门槛**：AC-1~AC-8 均有单测，不依赖真实外部环境。**一致**。
- **第 6 节 数据与迁移**：无表结构改动。**一致**。
- **第 7 节 交付/门禁**：提交信息引用 `REQ-AGENT-0005`。**一致**。

## 9. 需求 ID → 代码位置 → 测试位置（映射表）

| 需求 ID | 代码位置（预期） | 测试位置（预期） |
|---|---|---|
| REQ-AGENT-0005 | `tool/ToolDescriptor.java`、`tool/ToolRegistry.java`、`tool/Tool.java`（改）、`tool/WeatherTool.java`（改）、`service/ToolCallingService.java`（改）、`service/ChatService.java`（改）、`config/ToolProperties.java`（改）、`config/ToolConfig.java`（改）、`resources/application.yml` | `tool/ToolRegistryTest`、`tool/ToolDescriptorTest`、`service/ToolCallingServiceTest`（扩）、`service/ChatServiceToolTest`（扩）、`config/ToolPropertiesTest`（扩） |
