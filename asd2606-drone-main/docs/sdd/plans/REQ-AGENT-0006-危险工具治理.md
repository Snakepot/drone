# 技术方案 — REQ-AGENT-0006 危险工具治理（权限 / 二次确认 / 审计）

> 输入是 [`specs/REQ-AGENT-0006-危险工具治理.md`](../specs/REQ-AGENT-0006-危险工具治理.md)。
> 决策前提见 [`../decisions/ADR-0005-危险工具治理与人工确认.md`](../decisions/ADR-0005-危险工具治理与人工确认.md)。

- **需求 ID**：`REQ-AGENT-0006`
- **作者 / 日期**：开发 / 2026-10-14
- **评审人 / 评审日期**：待评审

## 1. 方案概述

给工具加**三道门**，只读工具不受影响：

1. **授权门**：配置文件维护「角色 → 允许的工具」（`agent.tool.role-tools`）；执行前用当前登录人的
   `LoginUser.roles` 比对（角色已由 `AuthInterceptor` + `AuthoritiesClient` 备好）。
2. **确认门**：配置文件维护「危险工具清单」（`agent.tool.dangerous`）；命中的工具**不立即执行**，
   而是把「要做什么」做成一枚**待确认动作**（**内存，TTL 5 分钟**）连同 `pendingId` 返回给前端；
   用户确认后经**新增的 `POST /chat/confirm`** 执行，并**续跑 ReAct 循环**给出最终答复。
3. **审计门**：危险工具每次执行落一条审计到 `<AGENT_MEMORY_ROOT>/tool-audit.log`。

## 2. 影响面

| 维度 | 影响 | 说明 |
|---|---|---|
| 接口 | **新增 + 扩展** | 新增 `POST /chat/confirm`；`POST /chat` 出参**新增可选字段** `pending`（向后兼容） |
| 数据模型 | **无** | 待确认动作是**内存态**、不落库；审计是**追加文件**，不是表 |
| 配置 | **新增** | `agent.tool.role-tools`（角色→工具）、`agent.tool.dangerous`（危险清单） |
| 兼容性 | **兼容** | 只读工具与既有行为不变；老客户端忽略 `pending` |
| 性能 | **有影响** | 危险操作多一次「确认」往返（用户确认才算，天然慢） |
| 其他服务 | **依赖（复用）** | 复用 `drone-server` 的角色数据，**不新增**接口 |

## 3. 接口清单

| 方法 | 路径 | 入参 | 出参 | 鉴权 | 幂等 |
|---|---|---|---|---|---|
| POST | `/chat` | `{conversationId, message}`（不变） | `{state,message,data:{reply, pending?}}`（**新增可选 `pending`**） | `agent:chat` | 否 |
| POST | `/chat/confirm` | `{conversationId, pendingId, approve}` | `{state,message,data:{reply}}` | `agent:chat` | **是**（同一 `pendingId` 只执行一次） |

`pending` 结构：`{pendingId, tool, summary}`——`summary` 是给人看的动作摘要（如「删除设备：DJI-M300 #12」）。

## 4. 数据模型变更

- 变更内容：**无**（表结构、记忆格式不变）
- **内存态**：`PendingAction{pendingId, userId, conversationId, toolName, argumentsJson, messagesSnapshot, createdAt}`
- **审计文件**：`<root>/tool-audit.log`（一行一条，追加；仿 `MemoryAuditLog`）
- 迁移脚本：无
- 回滚方案：**无数据需回滚**
- 是否需要停机 / 执行窗口：需要一次**重启**（配置与 Bean 变更）

### 4.1 配置长什么样（新）

```yaml
agent:
  tool:
    dangerous: [delete_device, delete_pilot]     # 危险工具清单：命中则每次都要确认
    role-tools:                                   # 角色 → 允许使用的工具
      admin: [get_flight_weather, delete_device, delete_pilot]
      pilot: [get_flight_weather]
```

> `role-tools` 里的工具名、`dangerous` 里的工具名都必须**在 `catalog` 里存在**，否则**启动失败**。

## 5. 关键设计决策

| 决策点 | 备选方案 | 结论 | 理由 |
|---|---|---|---|
| 待确认动作存储 | ①内存 ②本地文件 | **①内存 + TTL 5 分钟** | 需求方定；与 `ADR-0003` 单实例前提一致；重启即失效可接受（用户重发） |
| 确认接口 | ①新增 `/chat/confirm` ②复用 `/chat` 带令牌 | **①** | 语义清晰、与正常对话解耦；幂等好做 |
| 「危险」界定 | ①工具自声明 ②**配置清单** | **②** | 需求方定；改清单不必改代码 |
| 授权粒度 | ①逐工具权限码 ②**角色 → 工具配置** | **②** | 需求方定；与现成的 `LoginUser.roles` 直接对上 |
| 确认后如何继续 | ①存对话快照续跑 ②从头重发 | **①** | 用户确认后要「接得上」，不能把前文丢了；快照随待确认动作一起存内存 |
| 幂等 | ①无 ②**消费即失效** | **②** | 防止「连点两次确认」执行两次副作用 |
| 如何「关闭治理」 | ①开关允许危险工具自动执行 ②**不允许，只能把工具从 catalog 撤下** | **②** | 安全优先：**永远不存在**「危险工具自动执行」的状态 |

## 6. 测试策略

| 验收条件 | 测试层级 | 测试位置 |
|---|---|---|
| AC-1 | 单元 | `ToolGovernanceTest`（角色不在名单 → 拒绝执行） |
| AC-2 | 单元 | `ToolCallingServiceTest`（危险工具 → 不执行、返回 pending） |
| AC-3 | 单元 | `ChatConfirmServiceTest`（确认 → 执行并续跑） |
| AC-4 | 单元 | `PendingActionStoreTest`（过期 → 拒绝） |
| AC-5 | 单元 | `PendingActionStoreTest`（重复确认只执行一次） |
| AC-6 | 单元 | `ToolAuditLogTest`（执行后落一条审计） |
| AC-7 | 单元 | `ToolCallingServiceTest`（只读工具不入确认流程） |
| AC-8 | 单元 | 沿用脱敏断言 |
| AC-9 | 单元 | `ToolCallingServiceTest`（连续两次同类危险操作 → 两次都要确认） |

- 全部用桩工具 / 桩存储，**不依赖真实网络与真实 drone-server**（宪法第 5 节 AIR）。

## 7. 风险与回滚

| 风险 | 触发条件 | 影响 | 应对 |
|---|---|---|---|
| 内存态重启丢失 | 确认前服务重启 | 待确认动作作废 | 用户重发即可；不产生**半执行**（未确认=未执行） |
| 配置写错 | `dangerous`/`role-tools` 里出现 catalog 没有的工具名 | 运行期异常 | **启动期校验**，直接失败 |
| 越权确认 | 别人拿到 `pendingId` 去确认 | 他人替你执行危险操作 | **归属校验**：只能本人确认自己发起的动作 |
| 前端未适配 | 老客户端不认 `pending` | 危险工具无法确认 | 降级为「对话框里给出提示」；**只读工具不受影响** |
| 审计文件增长 | 危险操作多 | 磁盘增长 | 与记忆目录同卷监控；审计只追加、量小 |

- 上线方式：**配置 + 重启**（先只配只读工具的 `role-tools`，危险工具逐步接入）
- 回滚步骤：回退该次提交；或把危险工具从 `catalog` / `role-tools` 撤下（**不会**退化成自动执行）
- 回滚后数据是否需要修复：**不需要**（无半执行状态）

## 8. 与项目宪法的一致性

- **第 1 节 技术栈**：不引入新依赖（JDK 集合 + `HttpClient`）。**一致**。
- **第 2 节 结构/依赖方向**：新代码在 `drone-agent` 的 `tool/`、`service/`、`config/`；不访问 `drone-server` 库。**一致**。
- **第 3 节 接口契约**：新增 `/chat/confirm`；`/chat` **仅新增可选字段**（向后兼容）。**一致**。
- **第 4 节 安全红线**：**权限判定在服务端**；前端隐藏入口不构成边界；`/chat/confirm` 走用户 JWT + `agent:chat`；审计脱敏。**一致**。
- **第 5 节 测试门槛**：AC-1~AC-9 均有单测，不依赖真实外部环境。**一致**。
- **第 6 节 数据与迁移**：无表结构改动。**一致**。
- **第 7 节 交付/门禁**：提交信息引用 `REQ-AGENT-0006`。**一致**。

## 9. 需求 ID → 代码位置 → 测试位置（映射表）

| 需求 ID | 代码位置（预期） | 测试位置（预期） |
|---|---|---|
| REQ-AGENT-0006 | `tool/ToolGovernance.java`、`tool/PendingActionStore.java`、`tool/ToolAuditLog.java`、`service/ToolCallingService.java`（改）、`service/ChatConfirmService.java`、`controller/ChatController.java`（改）、`config/ToolProperties.java`（改）、`resources/application.yml`；前端 `api/agent.ts`、`AgentChatPanel.vue` | `tool/ToolGovernanceTest`、`tool/PendingActionStoreTest`、`tool/ToolAuditLogTest`、`service/ToolCallingServiceTest`（扩）、`service/ChatConfirmServiceTest`、`api/agent.confirm.spec.ts` |
