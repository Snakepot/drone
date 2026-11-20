# 任务拆解 — REQ-AGENT-0006 危险工具治理（权限 / 二次确认 / 审计）

> 输入是 [`plans/REQ-AGENT-0006-危险工具治理.md`](../plans/REQ-AGENT-0006-危险工具治理.md)。
> 【强制】一条任务必须能在**一次提交**内完成，且有明确完成标志。

- **需求 ID**：`REQ-AGENT-0006`
- **负责人 / 日期**：开发 / 2026-10-14

## 任务表

| 编号 | 任务 | 涉及模块/层 | 产出 | 完成标志 | 依赖 | 状态 |
|---|---|---|---|---|---|---|
| T-1 | 立规范三件套与 ADR | `docs/sdd/**` | `specs/`、`plans/`、`tasks/`、`ADR-0005` | 评审通过 | - | 待评审 |
| T-2 | 治理配置绑定与启动校验 | `config/ToolProperties.java`、`resources/application.yml`、`tool/ToolGovernance.java` | `dangerous`、`role-tools` 的配置绑定；「名单里的工具名必须在 catalog 中」的校验（实现落在 `ToolGovernance`） | 校验失败即启动失败 | T-1 | 已完成（2026-10-14） |
| T-3 | 授权门 | `tool/ToolGovernance.java` | `allows(工具, 角色集合)`：该角色是否允许用这个工具 | `ToolGovernanceTest` 通过（AC-1） | T-2 | 已完成（2026-10-14；**尚未接入编排**，接入在 T-5） |
| T-4 | 待确认动作存储 | `tool/PendingActionStore.java` | 内存 + TTL 5 分钟 + 归属校验 + 消费即失效 | `PendingActionStoreTest` 通过（AC-4/AC-5） | T-2 | 已完成（2026-10-14；**尚未接入编排**，接入在 T-5） |
| T-5 | 编排接入「危险 → 待确认」 | `service/ToolCallingService.java`、`dto/`、`controller/ChatController.java`、`service/ConversationService.java` | 危险工具不立即执行，返回 `pending`；`/chat` 出参加可选 `pending`；未授权回填「无权限」 | `ToolCallingServiceTest` 通过（AC-2/AC-7/AC-9） | T-3, T-4 | 已完成（2026-10-14；**确认入口待 T-6**） |
| T-6 | 确认接口与续跑 | `service/ChatConfirmService.java`、`controller/ChatController.java`、`dto/ConfirmRequest.java` | `POST /chat/confirm`：执行 + 续跑 ReAct 循环；幂等 | `ChatConfirmServiceTest` 通过（AC-3/AC-5） | T-5 | 已完成（2026-10-14） |
| T-7 | 审计 | `tool/ToolAuditLog.java` | 危险工具执行落 `<root>/tool-audit.log`（参数脱敏；写失败不回退） | `ToolAuditLogTest` 通过（AC-6） | T-5 | 已完成（2026-10-14） |
| T-8 | 前端确认交互 | `drone-web/src/**` | `api/agent.ts` 加 `confirmChat()`；面板弹确认框，拒绝也交回后端 | `agent.confirm.spec.ts` 通过；type-check/lint 干净（AC-2 前端侧） | T-6 | 已完成（2026-10-14；前端 20 用例全绿） |
| T-9 | 文档与端到端验证 | `drone-agent/docs/**`、临时实例 | 部署说明补 §5.6 与治理配置；链路验证 | 文档与实现一致；验证「危险 → 确认 → 执行 → 审计」 | T-7, T-8 | 已完成（2026-10-14，探针验证：确认前未执行、确认后执行 1 次、审计 `result=OK`） |

**完成标志怎么写**：写成可验证的结果，例如「接口 X 返回 Y，且测试 Z 通过」；不要写「完成开发」「处理好」。

## 验收条件覆盖

| 验收条件 | 覆盖任务 |
|---|---|
| AC-1 | T-3 |
| AC-2 | T-5, T-8 |
| AC-3 | T-6 |
| AC-4 | T-4 |
| AC-5 | T-4, T-6 |
| AC-6 | T-7 |
| AC-7 | T-5 |
| AC-8 | T-5, T-7 |
| AC-9 | T-5 |

> 【强制】规格里的每条 AC 都要落到至少一个任务上，漏掉的必须在备注里说明原因。

## 执行规则（AI 与人都适用）

- 【强制】一次只做一条任务，做完一条再开下一条。
- 【强制】每条任务完成后更新「状态」列，并给出可验证的证据（测试结果、命令输出）。
- 【强制】执行中发现规格或方案有问题，**停止当前任务**，先回 `specs/` 或 `plans/` 改并评审。
- 【强制】不得擅自扩大范围；发现「顺手也该改」的地方，记下来作为新需求提，不要夹带。

### 备注：前置依赖与边界

- **前置**：`REQ-AGENT-0005` 的 `ToolRegistry` 与配置化元数据是 T-2 的基础，
  因此 **0006 排在 0005 之后**实现（本任务表依赖 0005 的产物）。
- **安全红线**：任何情况下**不得**让危险工具在无确认时执行；
  「关闭治理」只能靠**把工具撤下**，不得做成「自动执行」的开关（见方案第 5 节）。
- **数据边界**：无表结构改动；待确认动作是内存态，重启即失效（可接受）。

### 缺陷修复记录（2026-10-22）

**现象**：用户确认/拒绝危险操作后，续跑调用模型返回 HTTP 400
`The reasoning_content in the thinking mode must be passed back to the API.`

**根因**：
1. `pause()` 存的是「调用之前」的消息快照，模型原始那条 `assistant(tool_calls)` 帧没有入档；
2. `resume()` 现造了一条 assistant 帧，`tool_call_id = confirm_<uuid>`；
3. 深度思考模式下，模型服务把 `reasoning_content` 与**原始 tool_call id** 绑定，
   id 对不上即拒绝（此前 curl 二分验证：id 改一个字符就复现）。

**修复**：
- `ModelMessage` / `ModelTurn` 增加 `reasoningContent`，`ChatService` 在请求体中回传；
- 票据 `PendingAction` 增加 `assistantFrame` 与 `dangerousCallId`；
- `resume()` **原样回放**原始 assistant 帧，再为帧内**每个** tool_call 回填结果；
  同轮未执行的工具回填「因危险操作等待确认，未执行」。

**验证**：
- 新增回归用例 `resumeReplaysOriginalAssistantFrame`（修复前失败：id 为 `confirm_...`）、
  `resumeAnswersEveryCallInPausedRound`、`ChatServiceToolTest#includesReasoningContent`；
- `mvn test` → **258 passed**；
- 真机（8082 + 8083）：`approve=false` 与 `approve=true` 均返回 200，
  日志出现「原样回放 assistant 帧（callId=call_00_…，reasoning N 字符）」，全程无 400。
