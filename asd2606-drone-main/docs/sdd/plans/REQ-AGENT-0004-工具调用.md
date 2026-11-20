# 技术方案 — REQ-AGENT-0004 工具调用（查飞行天气）

> 输入是 [`specs/REQ-AGENT-0004-工具调用.md`](../specs/REQ-AGENT-0004-工具调用.md)。本文件回答**怎么做**，并证明不会破坏既有东西。

- **需求 ID**：`REQ-AGENT-0004`
- **作者 / 日期**：开发 / 2026-10-14
- **评审人 / 评审日期**：待评审

## 1. 方案概述

在**非流式** `/chat` 路径上，把「调一次模型」升级为「调模型 → 若它请求工具则执行 → 回填结果 → 再调模型」的小循环。
新增一个可替换的**天气工具**（无 Key 的 Open-Meteo），用 OpenAI 兼容的 `tools` 协议与 DeepSeek 交互。
编排放在**新增的独立类**里：`ChatService` 仍只负责「一次模型调用」，`ConversationService` 仍只负责「一次对话」，工具循环是第三件事。
工具调用的中间消息**不写入会话记忆**，会话里只留用户消息与最终答复。

## 2. 影响面

| 维度 | 影响 | 说明 |
|---|---|---|
| 接口 | **无新增 / 无契约变更** | `POST /chat` 出参不变（`{state,message,data:{reply}}`），仅**行为增强**；流式路径不动 |
| 数据模型 | **无** | 不落库、不改表、不改落盘格式 |
| 兼容性 | **兼容** | 由开关 `agent.tool.enabled` 控制；关掉即回到纯对话，行为与现在完全一致 |
| 性能 | **有影响** | 一次工具往返新增**两次 HTTP**（地理编码 + 取天气，超时上限 5s）+ 一次额外模型调用；仅命中工具时才发生 |
| 其他服务 | **无** | `drone-server` 不受影响；工具调用纯在 `drone-agent` 内 |

## 3. 接口清单

| 方法 | 路径 | 入参 | 出参 | 鉴权要求 | 幂等 |
|---|---|---|---|---|---|
| POST | `/chat` | `{conversationId, message}`（不变） | `{state,message,data:{reply}}`（不变，reply 可能基于工具结果生成） | `agent:chat` | 否 |

> **无新增接口**。`/chat` 契约本身不变。
> 【补记 2026-10-14】工具调用只在**非流式**路径实现，为让它在界面上真正生效，
> **前端面板由 `/chat/stream` 改调 `/chat`**（非流式：等待动画 → 整段显示；
> 逐字流式体验本期不保留）。流式前端代码**保留备用**，后端 `/chat/stream` 不动。

## 4. 数据模型变更

- 变更内容：**无**。不新增表、不新增迁移脚本、不改记忆落盘格式。
- 迁移脚本：无
- 回滚方案：无数据需回滚
- 是否需要停机 / 执行窗口：不需要

## 5. 关键设计决策

| 决策点 | 备选方案 | 结论 | 理由 |
|---|---|---|---|
| 天气数据源 | ①需 Key 的搜索（SerpApi/Brave）②DuckDuckGo Lite（无 Key，通用搜索）③**Open-Meteo（无 Key，天气专用）** | **③，且抽象为 `WeatherProvider` 接口** | 需求方要求**无 Key**；②经 T-8 实测**被反爬挡住、不可用**；①需 Key 被否。③实测可用，代价是只覆盖天气 |
| 工具循环放哪 | ①塞进 `ChatService` ②塞进 `ConversationService` ③**新增 `ToolCallingService`** | **③** | 三个类各守一件事：`ChatService`=一次模型调用，`ConversationService`=一次对话，工具循环=独立职责。独立后**可单测**、可被其他入口复用 |
| 中间消息是否入会话 | ①全写入 ②**只写最终答复** | **②** | 工具消息会污染上下文、放大记忆体积、且对后续追问无价值 |
| 模型选择 | ①单独配工具模型 ②**复用当前模型，不支持则报错** | **②** | 用户已定；避免「静默换模型」导致行为不可预期 |
| 工具结果如何截断 | ①原样回填 ②**截断为前 N 项 + 截断摘要** | **②** | 天气/搜索结果动辄很长，原样回填会撑爆 token 预算（与 `REQ-AGENT-0003` 的注入预算同源考虑） |
| 失败如何体现 | ①抛异常使请求失败 ②**把失败文本作为工具结果回填** | **②** | 满足 AC-4：天气接口挂了也不该让用户看到 500，而应让模型如实说明 |

### 5.1 工具定义（发给模型的 `tools`）

```json
{
  "type": "function",
  "function": {
    "name": "get_flight_weather",
    "description": "查询指定城市的当前天气（气温、风速、降水），用于判断是否适合无人机起飞。",
    "parameters": {
      "type": "object",
      "properties": {
        "place": { "type": "string", "description": "城市名，如 北京、上海" }
      },
      "required": ["place"]
    }
  }
}
```

> 【实现细节】入参解析做了**键名容错**：模型若不严格按 Schema 走，
> `place` / `location` / `city` / `query` 取第一个有值的即可，不必因此判失败。

### 5.2 调用时序（对应需求的 7 步）

```
① 组装上下文（含系统提示词 + 工具定义）
② 第 1 次调模型
③ 模型返回 message.tool_calls（含 name=get_flight_weather, arguments={"place":"北京"}）
④ 服务端执行工具（地理编码 → 取天气）→ 得到结果文本
⑤ 追加两帧消息：assistant(含 tool_calls) + tool(tool_call_id, content=结果)
⑥ 第 2 次调模型（带工具结果）
⑦ 模型返回最终自然语言答复 → 作为 reply 返回并写入会话
```

若 ③ 未返回 `tool_calls`，直接取 `content` 作为答复，**不进入循环**（即 AC-2 / AC-3 的路径）。

## 6. 测试策略

| 验收条件 | 测试层级 | 测试位置 |
|---|---|---|
| AC-1 | 单元（模型/搜索均打桩） | `ToolCallingServiceTest` |
| AC-2 | 单元 | `ToolCallingServiceTest`（模型不返回 tool_calls） |
| AC-3 | 单元 | `ToolCallingServiceTest` |
| AC-4 | 单元 | `ToolCallingServiceTest` + `WeatherToolTest`（后端抛错） |
| AC-5 | 单元 | `ToolCallingServiceTest`（桩模型反复要工具，断言轮次封顶） |
| AC-6 | 单元 | `ToolCallingServiceTest`（断言日志脱敏，或对脱敏工具做单测） |
| AC-7 | 单元 | `ChatServiceTest`（沿用现有未配置用例） |
| AC-8 | 单元 | `ConversationServiceTest`（断言会话只增两条） |

- 天气解析：`OpenMeteoWeatherProviderTest` 用**固定的 JSON 样本**断言解析，**不发真实网络请求**（符合宪法第 5 节 AIR）。

## 7. 风险与回滚

| 风险 | 触发条件 | 影响 | 应对 |
|---|---|---|---|
| 当前模型**不支持** tools | （T-8 已实测**支持**，本条风险基本消除） | AC-1 拿不到 `tool_calls` | 若将来换模型后不支持：**直接报错提示**，回 `specs/plans` 调整 |
| 天气接口不可达 / 地点找不到 | Open-Meteo 出网受限，或城市名无匹配 | 拿不到天气 | `WeatherProvider` 可替换；统一**降级**为「查不到」回填（AC-4） |
| DeepSeek 返回格式差异 | `tool_calls` 字段位置/命名与预期不符 | 解析异常 | 解析做**容错**并记录原始响应（脱敏后） |
| token 成本上升 | 回填内容过长 | 单次请求变贵变慢 | 工具内**截断** + 编排层兜底截断（见 5.2） |

- 上线方式：**开关**（`agent.tool.enabled`，默认 `true`）
- 回滚步骤：把 `agent.tool.enabled` 置 `false` 或回退该次提交；不涉及数据
- 回滚后数据是否需要修复：**不需要**

## 8. 与项目宪法的一致性

- **第 1 节 技术栈**：不引入新依赖（JDK `HttpClient`）+ 不引入 Redis/新 ORM；版本不新增。**一致**。
- **第 2 节 结构/依赖方向**：新代码置于 `drone-agent` 内的新包 `tool/`，`web → service → tool` 单向；不访问 `drone-server` 库。**一致**。
- **第 3 节 接口契约**：`/chat` 响应契约不变；无新增错误码。**一致**。
- **第 4 节 安全红线**：工具入参与结果**脱敏后才入日志**；不外泄 `Authorization`；天气接口**无 Key** 因而不新增密钥面。**一致**。
- **第 5 节 测试门槛**：AC-1~AC-8 均有单测；天气解析用固定样本，**不依赖真实网络**。**一致**。
- **第 6 节 数据与迁移**：无表结构改动。**一致**。
- **第 7 节 交付/门禁**：提交信息将引用 `REQ-AGENT-0004`。**一致**。

## 9. 需求 ID → 代码位置 → 测试位置（映射表）

| 需求 ID | 代码位置（预期） | 测试位置（预期） |
|---|---|---|
| REQ-AGENT-0004 | `drone-agent/.../tool/**`、`service/ToolCallingService.java`、`service/ChatService.java`（扩展）、`service/ConversationService.java`（接入）、`config/ToolProperties.java` | `tool/**Test`、`service/ToolCallingServiceTest`、`service/ChatServiceTest`、`service/ConversationServiceTest` |

## 10. 补记：T-8 端到端验证发现与处置（2026-10-14）

按第 7 节风险表的「无 Key 搜索被限流 / 改版」一栏，T-8 用真实链路验证时**真的踩到了**：

- **编排链路本身通过**：真实 DeepSeek 返回 `tool_calls`（一次返回两个，按 Q-5 串行执行），
  模型据工具结果作答；最终答复是**自然语言、无原始 JSON**。也由此**证实当前模型支持 function calling**。
- **原搜索后端不可用**：DuckDuckGo 的 lite / html 端点都返回 **HTTP 202 + 首页**（反爬），
  正文无任何结果锚点 → 拿不到数据。**AC-1 用原方案不可达**。

**处置（已按 `README.md` 第 6 节走规范变更，规范与代码同一次改动同步）**：

- Q-1 re-open 后**改定 Open-Meteo**（无 Key、天气专用、实测 HTTP 200 可用）；
- 工具语义由「通用搜索」收窄为「查飞行天气」，工具名 `web_search` → `get_flight_weather`；
- 代码替换：`SearchProvider`/`DuckDuckGoSearchProvider`/`WebSearchTool`/`SearchResult`
  → `WeatherProvider`/`OpenMeteoWeatherProvider`/`WeatherTool`/`WeatherReport`；
- 配置项 `search-timeout` → `request-timeout`。

## 11. 编排模式说明：ReAct 的函数调用版（回答「`ToolCallingService` 到底是什么模式」）

> 本节是**对已实现编排的说明**（`REQ-AGENT-0004` 的产物），不是新功能。

**经典 ReAct**：模型交替产出 `Thought → Action → Observation`，
其中 Action 是**自由文本**，要靠解析文本才知道「它想干什么」。

**我们用的是它的现代变体——Function Calling**：Action 从自由文本挪进了结构化的
`tool_calls` 字段，Observation 从文本挪进了 `role=tool` 消息。**循环骨架完全一样**：

```
思考（模型决定要不要用工具） → 行动（服务端执行工具） → 观察（结果回填） → 再思考 …… → 直接作答
```

`ToolCallingService` 的循环逐句对得上 ReAct：

| ReAct 概念 | 代码里的对应 |
|---|---|
| Thought | `chatService.chatStep(...)` 返回的 `ModelTurn`：要么给 `content`，要么给 `toolCalls` |
| Action | `turn.hasToolCalls()` → `runTool(call)` |
| Observation | `messages.add(ModelMessage.tool(callId, content))` 把结果按 `call_id` 回填 |
| 终止 | `!turn.hasToolCalls()` → 该轮正文即最终答复 |
| step budget | `maxRounds` 用满后**收回工具**，逼模型基于已有结果收尾（对应 AC-5） |

**为什么选函数调用版而不是「文本 ReAct」**：文本要正则/容错解析，脆；
函数调用有 JSON Schema 校验、参数结构确定，且与 DeepSeek 的兼容协议一致。

**已知局限（当前这套的边界）**：

- **单 agent**：没有 Planner/Executor 分离，模型边想边做；
- **轮次封顶**：`max-rounds=3` 是硬止损，不是「想清楚为止」；
- **无反思**：没有 Reflexion（失败后自我复盘重试）那一层。

**可选的进化方向**：`Plan-and-Execute`（先出计划再执行，适合长任务）、`Reflexion`（带自评重试）。
取舍：二者都显著增加模型调用次数与复杂度，**当前场景（查天气）不值得**，
等出现「多步、长链路」的真实需求再说。

> 相关：`REQ-AGENT-0005` 计划在**同轮内并行执行多个只读工具**，
> 改的是本节的「行动（Action）」这一步，**不动** ReAct 的因果顺序（跨轮仍串行）。
