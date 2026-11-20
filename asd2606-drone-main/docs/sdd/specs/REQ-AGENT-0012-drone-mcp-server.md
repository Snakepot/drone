# 规格 — REQ-AGENT-0012 新建 drone-mcp-server，把现有业务接口暴露为 MCP 工具

> 本文件只回答**做什么、达到什么效果**。不写实现方案（那是 `plans/` 的事）。

- **需求 ID**：`REQ-AGENT-0012`
- **提出人 / 日期**：需求方 / 2026-10-21
- **负责人**：AI（按 SDD 执行）

## 1. 背景与目标

`drone-agent` 已具备 MCP 客户端能力（REQ-AGENT-0011），当前只接了高德。需求方要求再建
一个独立 MCP 服务 **`drone-mcp-server`**，把 `drone-server` **已实现**的业务接口暴露成工具，
让助手能用自然语言完成平台业务操作（查任务、派发、打卡、处理告警等）。

硬约束：**不新增业务接口、不发明工具**——工具集合必须与 `drone-server` 现有 endpoint 一一对应；
操作必须以**当前登录用户**的身份执行，权限与审计不能被绕过。

## 2. 范围

### 做（In Scope）

- 新建 `drone-mcp-server`（Spring Boot、端口 8082、仅绑回环）。
- 工具目录由 `drone-server` 的 OpenAPI 快照派生：**70 个**工具，排除基础设施接口（见下）。
- MCP 协议：`initialize` / `notifications/initialized` / `tools/list` / `tools/call`。
- 每调用透传用户 JWT；缺失即拒绝，不请求上游。
- `drone-agent` 侧：携带 token、每调用请求头、`forward-authorization` 配置、发现阶段有界重试。
- 危险工具默认清单与角色授权配置；系统提示词 v6；文档与冒烟记录。

### 不做（Out of Scope）

- **不新增任何业务接口**；不改 `drone-server` 的业务代码。
- 不暴露：登录/登出、健康检查、导入/导出/模板、附件上传/下载、`/internal/**`、WebSocket。
- 不做 MCP 的 resources / prompts / sampling。
- 不按用户裁剪 `tools/list`（授权在调用时由 `drone-server` 判定）。
- 不做二进制内容传输（上传/下载）。

## 3. 验收条件

| 编号 | 前置（Given） | 操作（When） | 预期（Then） |
|---|---|---|---|
| AC-1 | `drone-server` **未启动** | 启动 `drone-mcp-server` | 启动成功；`tools/list` 仍返回 70 个工具 |
| AC-2 | 快照与当前接口一致 | 调 `tools/list` | 工具集合 = 快照中的 70 个业务操作；排除清单内接口一个都不出现 |
| AC-3 | 持有 admin 的 Token | 调 `drone__task_list` | 返回任务分页数据 |
| AC-4 | 持有 pilot 的 Token | 调 `drone__zone_delete` | 返回 `isError:true`，文案表明无权限；不泄露内部细节 |
| AC-5 | 请求不带 `Authorization` | 调任意 `drone__*` 工具 | 返回 `isError:true`「缺少用户身份」，且**没有发出上游请求** |
| AC-6 | 危险清单包含 `drone__zone_delete` | 模型请求该工具 | 编排层暂停并请求人工确认，确认后才执行 |
| AC-7 | 任意一次调用 | 查看两边日志 | 不含 JWT 原文；能用工具名与耗时串起链路 |
| AC-8 | 上游返回 401 / 403 / `state≠200` | 调工具 | 三种情况文案可区分：登录失效 / 无权限 / 业务失败原因 |
| AC-9 | `drone-agent` 先启动，MCP 服务晚 3 秒就绪 | 启动 agent | 在有界重试内发现全部工具（不需重启） |
| AC-10 | 修改了 `drone-server` 接口 | 执行快照同步脚本 | 快照更新；单测仍断言 70 个且命名唯一 |

### 非功能要求

- 启动：**不发起任何外部请求**；冷启动 < 3s（不含 JVM）。
- 调用：上游超时 10s（< agent 的 12s < 编排层 15s）。
- 容量：单实例，按 1 个 agent、并发 ≤ 4 估算。
- 安全：默认只绑 `127.0.0.1`；JWT 不落日志、不进 query；工具参数做类型与路径校验。
- 兼容：MCP 协议版本 `2025-03-26`；与 REQ-AGENT-0011 的客户端一致。
- 体积：70 个工具定义约 15k tokens/轮，属已知成本（需求方确认全部暴露）。

## 4. 约束与依赖

- 依赖：`drone-server` 的公开业务接口与 `/v3/api-docs`（仅用于生成快照，不在启动时读取）。
- 合规：新增第五个工程、且服务间调用不走 `/internal/**`，与宪法 §2 字面冲突 → ADR-0008。
- 运行：必须与 `drone-agent` 同网络可达；不得暴露到公网。

## 5. 待定项

| 编号 | 待定问题 | 结论 | 决定人 / 日期 |
|---|---|---|---|
| Q-1 | 工具定义手写还是自动派生 | 自动派生 + 构建期快照 | 方案评审 / 2026-10-21 |
| Q-2 | 身份用服务账号还是用户 JWT | 用户 JWT 透传 | 方案评审 / 2026-10-21 |
| Q-3 | 启动时是否联网拉 OpenAPI | 不联网，读 classpath 快照 + 可选后台刷新 | 需求方 / 2026-10-21 |
