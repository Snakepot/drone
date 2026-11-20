# 任务拆解 — REQ-AGENT-0004 工具调用（联网搜索天气）

> 输入是 [`plans/REQ-AGENT-0004-工具调用.md`](../plans/REQ-AGENT-0004-工具调用.md)。
> 【强制】一条任务必须能在**一次提交**内完成，且有明确完成标志。

- **需求 ID**：`REQ-AGENT-0004`
- **负责人 / 日期**：开发 / 2026-10-14

## 任务表

| 编号 | 任务 | 涉及模块/层 | 产出 | 完成标志 | 依赖 | 状态 |
|---|---|---|---|---|---|---|
| T-1 | 立规范三件套与选型 ADR | `docs/sdd/**` | `specs/`、`plans/`、`tasks/`、`ADR-0004` | 评审通过 | - | 已完成 |
| T-2 | 工具契约与无 Key 天气实现 | `drone-agent/tool/**` | `Tool`、`WeatherProvider`、`OpenMeteoWeatherProvider`、`WeatherTool`、`ToolResult` 等 | 固定 JSON 样本解析测试通过；**无真实网络请求** | T-1 | 已完成（2026-10-14；后端由 DuckDuckGo **改定 Open-Meteo**，见「补记」） |
| T-3 | `ChatService` 支持工具协议 | `service/ChatService.java` | 请求体带 `tools`；新增方法返回含 `tool_calls` 的完整消息，并支持回填工具结果 | `ChatServiceToolTest` 通过；**流式方法未被改动** | T-1 | 已完成（2026-10-14，`mvn -Dtest=ChatServiceToolTest,ChatServiceTest test` → 9 passed） |
| T-4 | 工具调用编排循环 | `service/ToolCallingService.java` | 循环：轮次上限 + 失败回填 + 结果截断 | `ToolCallingServiceTest` 覆盖 AC-1/2/3/5 通过 | T-2, T-3 | 已完成（2026-10-14，`ToolCallingServiceTest` 6 例全绿） |
| T-5 | 接入 `/chat` 非流式路径 + 提示词放行飞行天气 | `service/ConversationService.java`、`resources/application.yml` | `ask` 走工具编排；system prompt 增补飞行天气 | `ConversationServiceTest` 断言会话只增两条（AC-8）通过 | T-4 | 已完成（2026-10-14，全量 `mvn test` → 119 passed，含上下文加载） |
| T-6 | 配置项与装配 | `config/ToolProperties.java`、`config/MemoryConfig.java`（或新配置类） | `agent.tool.*`：enabled / max-rounds / timeout | 缺省值可启动；`enabled=false` 时行为与现状一致 | T-4 | 已完成（2026-10-14，全量 `mvn test` → 121 passed；`ToolPropertiesTest` + 关闭开关用例） |
| T-7 | 日志、脱敏与文档补齐 | `tool/**`、`service/**`、`drone-agent/docs/**` | 工具调用一进一出日志（脱敏）；部署说明增补配置项 | 日志中无 Key/Authorization；文档配置表与实现一致 | T-5, T-6 | 已完成（2026-10-14，部署说明新增第 5 节；日志仅打脱敏后的 body/query） |
| T-8 | 端到端接口验证 | 临时实例 | 真实调用 `/chat` 问飞行天气 | 观察到「第 1 次调模型 → 工具调用 → 第 2 次调模型 → 答复」且答复无原始 JSON | T-7 | 已完成（2026-10-14，真实 DeepSeek + 真实 Open-Meteo；北京 18.3℃/4.1km/h，答复为自然语言） |
| T-9 | 前端面板改用非流式 `/chat` | `drone-web/src/**` | `api/agent.ts` 增 `chat()`；`AgentChatPanel.vue` 改走 `/chat`；为慢请求放宽超时到 60s | `agent.chat.spec.ts` 通过；`npm run type-check` / `lint` / `test` 零失败 | T-8 | 已完成（2026-10-14；type-check + lint 干净，前端 18 用例全绿） |
| T-10 | 工具调用链路日志细化（面向初级工程师） | `service/ToolCallingService`、`service/ChatService`、`tool/OpenMeteoWeatherProvider`、`tool/WeatherTool` | 逐轮叙述 + 模型一进一出 + 天气「两步」 + 回填明细（DEBUG） | 真实链路日志能**完整还原流程**；日志无 Key / `Authorization` | T-9 | 已完成（2026-10-14，真实 DeepSeek + Open-Meteo 验证） |

**完成标志怎么写**：写成可验证的结果，例如「接口 X 返回 Y，且测试 Z 通过」；不要写「完成开发」「处理好」。

## 验收条件覆盖

| 验收条件 | 覆盖任务 |
|---|---|
| AC-1 | T-4, T-8, T-9 |
| AC-2 | T-4, T-5 |
| AC-3 | T-4 |
| AC-4 | T-2, T-4 |
| AC-5 | T-4 |
| AC-6 | T-2, T-7 |
| AC-7 | T-3（沿用现有未配置用例） |
| AC-8 | T-5 |

> 【强制】规格里的每条 AC 都要落到至少一个任务上，漏掉的必须在备注里说明原因。

## 执行规则（AI 与人都适用）

- 【强制】一次只做一条任务，做完一条再开下一条。
- 【强制】每条任务完成后更新「状态」列，并给出可验证的证据（测试结果、命令输出）。
- 【强制】执行中发现规格或方案有问题，**停止当前任务**，先回 `specs/` 或 `plans/` 改并评审。
- 【强制】不得擅自扩大范围；发现「顺手也该改」的地方，记下来作为新需求提，不要夹带。

### 备注：已知但不夹带的既有问题

- `dto/ChatMessage.java` 的 javadoc 仍引用已被删除的 `ChatService#normalizeHistory`，属既有文档漂移。
  **不在本需求内顺手改**，另记新需求处理。

### 补记：T-8 端到端验证的发现与处置（2026-10-14）

用**真实** DeepSeek + **真实**天气源跑了两轮（一次性探针，均已删除）：

- **编排链路成立**：模型确实返回 `tool_calls`（首轮实测一次返回**两个**，按 Q-5 串行执行），
  再据工具结果作答；最终答复是**自然语言、无原始 JSON**。也由此证实当前模型**支持 function calling**。
- **首轮否决了原搜索后端**：`lite` / `html.duckduckgo.com` 均返回 **HTTP 202 + 首页**（反爬），
  结果锚点命中 0 → 原「无 Key 通用搜索」方案不可用。
- **处置**：经决策人复议改定 **Open-Meteo**，并**复验通过**：
  真实取到北京 **18.3℃ / 风速 4.1 km/h**，模型据此给出自然语言答复。
- 规范（`specs/` Q-1、`plans/` 第 5/7/10 节、`ADR-0004`）与代码已**同一次改动同步**。
