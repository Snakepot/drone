# 任务拆解 — REQ-AGENT-0005 工具框架增强（管理 / 并行 / 追踪）

> 输入是 [`plans/REQ-AGENT-0005-工具框架增强.md`](../plans/REQ-AGENT-0005-工具框架增强.md)。
> 【强制】一条任务必须能在**一次提交**内完成，且有明确完成标志。

- **需求 ID**：`REQ-AGENT-0005`
- **负责人 / 日期**：开发 / 2026-10-14

## 任务表

| 编号 | 任务 | 涉及模块/层 | 产出 | 完成标志 | 依赖 | 状态 |
|---|---|---|---|---|---|---|
| T-1 | 立规范三件套 | `docs/sdd/**` | `specs/`、`plans/`、`tasks/` | 评审通过 | - | 待评审 |
| T-2 | 工具元数据外置 + `ToolRegistry` | `tool/`、`config/`、`resources/application.yml` | `ToolDescriptor`、`ToolRegistry`；`Tool` 去 `definition()`；`WeatherTool` 只留 `name()`+`invoke()`；yml 加 `agent.tool.catalog` | `ToolRegistryTest` 通过（重名 / 对不上 → 启动失败）；AC-7 断言并入该用例 | T-1 | 已完成（2026-10-14；与 T-3 一并，因编译耦合） |
| T-3 | 编排层改用 `ToolRegistry` | `service/ToolCallingService.java` | 依赖 `ToolRegistry` 取定义与调用，不再自建 `Map` | 既有 `ToolCallingServiceTest` 全绿 | T-2 | 已完成（2026-10-14，`mvn test` → 122 passed） |
| T-4 | 同轮并行执行 + 单工具超时 | `service/ToolCallingService.java`、`config/` | 全只读则并发；有非只读则串行；保持结果原序；`Future.get` 超时兜底 | `ToolCallingServiceTest` 并发用例（`CyclicBarrier` 证明并发）与超时用例通过 | T-3 | 已完成（2026-10-14；与 T-5 一并，因编译耦合） |
| T-5 | 配置项与装配 | `config/ToolProperties.java`、`config/ToolConfig.java`、`resources/application.yml` | `parallel-enabled` / `concurrency` / `invoke-timeout`；执行器 Bean（停机 `shutdown`） | `ToolPropertiesTest` 通过；缺省值可启动 | T-4 | 已完成（2026-10-14，`mvn test` → 126 passed） |
| T-6 | 耗时日志 + `traceId` | `service/ToolCallingService.java`、`service/ChatService.java`、`service/ConversationService.java` | 每次模型/工具/整轮记耗时；`/chat` 生成 `traceId` 并显式贯穿 | 日志可见 `elapsed` 与同一 `traceId`；既有用例全绿 | T-4 | 已完成（2026-10-14，`mvn test` → 128 passed；真实链路验证见 T-7） |
| T-7 | 文档与端到端验证 | `drone-agent/docs/**`、临时实例 | 部署说明补工具元数据与新配置；真实链路验证 | 文档配置表与实现一致；真实链路日志含耗时与 `traceId` | T-5, T-6 | 已完成（2026-10-14，部署说明 §5.3/§5.4；真实 DeepSeek + Open-Meteo 日志含 `traceId=084b6d3f` 与各步耗时） |

**完成标志怎么写**：写成可验证的结果，例如「接口 X 返回 Y，且测试 Z 通过」；不要写「完成开发」「处理好」。

## 验收条件覆盖

| 验收条件 | 覆盖任务 |
|---|---|
| AC-1 | T-2 |
| AC-2 | T-4 |
| AC-3 | T-4 |
| AC-4 | T-6 |
| AC-5 | T-4, T-5 |
| AC-6 | T-2 |
| AC-7 | T-2 |
| AC-8 | T-6（沿用既有脱敏断言） |

> 【强制】规格里的每条 AC 都要落到至少一个任务上，漏掉的必须在备注里说明原因。

## 执行规则（AI 与人都适用）

- 【强制】一次只做一条任务，做完一条再开下一条。
- 【强制】每条任务完成后更新「状态」列，并给出可验证的证据（测试结果、命令输出）。
- 【强制】执行中发现规格或方案有问题，**停止当前任务**，先回 `specs/` 或 `plans/` 改并评审。
- 【强制】不得擅自扩大范围；发现「顺手也该改」的地方，记下来作为新需求提，不要夹带。

### 备注：迁移影响（T-2 会动既有代码）

- `Tool.definition()` 被移除后，`WeatherTool` 与 `ChatServiceToolTest` 里用到的
  `WeatherTool.NAME / DESCRIPTION / PARAMETERS_JSON` 都会变动——**属于本需求内的既定改动**，
  不算夹带；改完要保证 `REQ-AGENT-0004` 的既有用例仍绿。
