# 任务拆解 — REQ-AGENT-0007 编排模式优化（稳 / 省 / 可控）

> 输入是 [`plans/REQ-AGENT-0007-编排模式优化.md`](../plans/REQ-AGENT-0007-编排模式优化.md)。
> 【强制】一条任务必须能在**一次提交**内完成，且有明确完成标志。

- **需求 ID**：`REQ-AGENT-0007`
- **负责人 / 日期**：开发 / 2026-10-14

## 任务表

| 编号 | 任务 | 涉及模块/层 | 产出 | 完成标志 | 依赖 | 状态 |
|---|---|---|---|---|---|---|
| T-1 | 立规范三件套 | `docs/sdd/**` | `specs/`、`plans/`、`tasks/` | 评审通过 | - | 待评审 |
| T-2 | 预算与配置 | `config/ToolProperties.java`、`service/ToolCallingService.java`、`resources/application.yml` | `optimize-enabled` / `max-result-chars` / `max-total-result-chars` / `max-retries`；超预算**有序收尾**。（**步数预算沿用现有 `agent.tool.max-rounds`，不另加 `max-steps`** —— 与方案第 5 节「不各加一套步数」对齐） | `ToolCallingServiceTest`（AC-4/AC-6）与 `ToolPropertiesTest` 通过 | T-1 | 已完成（2026-10-14，`mvn test` → 130 passed） |
| T-3 | 受控重试 | `service/ToolCallingService.java` | 仅**失败**时把失败信息回填给模型再决断，上限 1 次 | `ToolCallingServiceTest`（AC-1/AC-2）通过 | T-2 | 已完成（2026-10-14） |
| T-4 | 分级压缩 | `tool/ToolResultCompressor.java`、`service/ToolCallingService.java` | 先结构化裁剪，仍超长再模型摘要 | `ToolResultCompressorTest` + `ToolCallingServiceTest`（AC-3）通过 | T-2 | 已完成（2026-10-14） |
| T-5 | 可观测 | `service/ToolCallingService.java` | 日志可读「步数 / 重试次数 / 压缩次数」 | `ToolCallingServiceTest`（AC-5/AC-7）通过 | T-3, T-4 | 已完成（2026-10-14，`【工具编排】完成` 含步数/失败轮数/压缩条数） |
| T-6 | 文档与端到端验证 | `drone-agent/docs/**`、临时实例 | 部署说明补新配置；真实链路验证 | 文档与实现一致；真实验证重试/压缩路径 | T-5 | 已完成（2026-10-14，部署说明 §5.5；真实 DeepSeek 验证「失败→回填→模型决断」与计数） |

**完成标志怎么写**：写成可验证的结果，例如「接口 X 返回 Y，且测试 Z 通过」；不要写「完成开发」「处理好」。

## 验收条件覆盖

| 验收条件 | 覆盖任务 |
|---|---|
| AC-1 | T-3 |
| AC-2 | T-3 |
| AC-3 | T-4 |
| AC-4 | T-2 |
| AC-5 | T-5 |
| AC-6 | T-2 |
| AC-7 | T-5（沿用既有脱敏断言） |

> 【强制】规格里的每条 AC 都要落到至少一个任务上，漏掉的必须在备注里说明原因。

## 执行规则（AI 与人都适用）

- 【强制】一次只做一条任务，做完一条再开下一条。
- 【强制】每条任务完成后更新「状态」列，并给出可验证的证据（测试结果、命令输出）。
- 【强制】执行中发现规格或方案有问题，**停止当前任务**，先回 `specs/` 或 `plans/` 改并评审。
- 【强制】不得擅自扩大范围；发现「顺手也该改」的地方，记下来作为新需求提，不要夹带。

### 备注：依赖与边界

- **依赖顺序**：`REQ-AGENT-0005`（并行 + 追踪）→ `REQ-AGENT-0007`（本需求）→ `REQ-AGENT-0006`（治理）。
  三者都改 `ToolCallingService`，后做的要基于前者的结果。
- **共用配置**：预算与 `max-rounds` 同处 `agent.tool.*`，实现时**对齐语义**，不得各加一套“步数”。
  → 本需求**不新增 `max-steps`**（原 T-2 草稿里列了，属笔误），`max-rounds` 即「步数预算」。
- **不碰**：ReAct 骨架、`/chat` 契约、工具治理（属 `REQ-AGENT-0006`）。
