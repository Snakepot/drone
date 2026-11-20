# 任务拆解 — REQ-AGENT-0001 简单对话

> **回填件**（2026-10-12）：任务按**实际完成顺序**追记，不是事先拆解。
> 状态列反映回填时的真实状态。

- **需求 ID**：`REQ-AGENT-0001`
- **负责人 / 日期**：2026-10-12（回填）

## 任务表

| 编号 | 任务 | 涉及模块/层 | 产出 | 完成标志 | 依赖 | 状态 |
|---|---|---|---|---|---|---|
| T-1 | 建 `drone-agent` 工程骨架 | drone-agent | pom + 启动类 + `application.yml` | `mvn compile` 通过，服务能启动 | - | 已完成 |
| T-2 | 接入 DeepSeek 调用（非流式） | drone-agent / service | `ChatService.chat()`、`ChatController` `POST /chat` | 配好 API-KEY 后能拿到回复 | T-1 | 已完成 |
| T-3 | `drone-server` 开放内部 RBAC 接口 | drone-server | `/internal/user/{id}/authorities` + 内部密钥拦截器 | 无密钥 401、正确密钥返回角色与权限 | - | 已完成 |
| T-4 | `drone-agent` 复用 JWT 与权限码 | drone-agent | `JwtUtil`/`AuthInterceptor`/`AuthoritiesClient`/`RequirePermission` | 无 Token 401、无权限 403、有权限 200 | T-3 | 已完成 |
| T-5 | 新增权限码 `agent:chat` | drone-server / SQL | `agent_permission.sql` | 执行后 `admin` 拥有 `agent:chat` | T-3 | 已完成 |
| T-6 | 管理端对话面板 | drone-web | `api/agent.ts`、`AgentChatPanel.vue`、顶栏入口 | 能发问、能显示回复 | T-2 T-4 | 已完成 |
| T-7 | 前端代理到 8081 | drone-web | `vite.config.ts` 增 `/api/agent`（置于 `/api` 之前）、`.env.development` | 面板请求落到 drone-agent | T-6 | 已完成 |
| T-8 | 模型调用入参/出参日志 | drone-agent / service | `ChatService` 加 `@Slf4j` 与前后两处 DEBUG | 日志含完整请求体与响应体，且不含 API-KEY | T-2 | 已完成 |
| T-9 | 流式输出（SSE） | drone-agent | `ChatService.streamChat()`、`POST /chat/stream` | 逐块收到 `delta`；客户端断开即停 | T-2 | 已完成 |
| T-10 | 异步请求 ThreadLocal 清理 | drone-agent / interceptor | `AuthInterceptor` 实现 `AsyncHandlerInterceptor` | 异步请求不再残留身份 | T-9 | 已完成 |
| T-11 | 前端流式渲染 | drone-web | `agent.ts` 改 `fetch` 读流；面板增量渲染 | 首段到达即渲染，带打字动画 | T-9 T-6 | 已完成 |
| T-12 | 面板全屏 | drone-web | 面板加全屏切换 + 限宽居中样式 | AC-11 通过 | T-6 | 已完成 |
| T-13 | 用系统提示词约束业务范围 | drone-agent | `agent.deepseek.system-prompt`；移除客户端 `systemPrompt` 字段 | AC-8 / AC-9 / AC-12 通过 | T-2 | 已完成 |

## 验收条件覆盖

| 验收条件 | 覆盖任务 |
|---|---|
| AC-1 / AC-2 / AC-3 | T-4 |
| AC-4 | T-6 |
| AC-5 / AC-6 / AC-7 | T-9 |
| AC-8 / AC-9 | T-13 |
| AC-10 | T-8 |
| AC-11 | T-12 |
| AC-12 | T-13 |

## 与流程不符之处（如实记录）

按 `../README.md` 第 4 节，任务应**先于编码**产出、每条完成即更新状态。本文件是回填，
因此：

- 未做事前拆解与粒度审查（T-9 体量明显偏大，按规则应拆成「服务端流式」「控制器 SSE」「断开处理」三条）。
- 执行过程中未按门禁 G1 检查，规格与方案是事后补的。
- **未按 `java-standards.md` 第 9 节为验收条件补自动化测试**，是当前最大的欠账。

后续任务请按 `tasks.md` 模板**事先拆解**、逐条更新状态，不要再出现这种回填件。
