# 技术方案 — REQ-AGENT-0001 简单对话

> **回填件**（2026-10-12）：按已实现方案追写，不是事先评审的方案。

- **需求 ID**：`REQ-AGENT-0001`
- **作者 / 日期**：2026-10-12（回填）
- **评审人 / 评审日期**：待补（回填件无评审记录）

## 1. 方案概述

新建独立服务 `drone-agent`（Spring Boot 3.3.3 / Java 17，端口 8081），
用 JDK 自带 `HttpClient` 直连 DeepSeek 的 `/chat/completions`，不引第三方 SDK。

鉴权不另起一套：复用 `drone-server` 的 JWT（同密钥 + 同 issuer 验签）与权限码，
权限数据通过 `drone-server` 的内部接口取得。前端在 `drone-web` 顶栏加入口，
右侧抽屉式对话面板；流式输出走 SSE。

## 2. 影响面

| 维度 | 影响 | 说明 |
|---|---|---|
| 接口 | 新增 | `drone-agent` 的 `POST /chat`、`POST /chat/stream`；`drone-server` 的 `GET /internal/user/{userId}/authorities` |
| 数据模型 | 新增一行权限数据 | `permission` 增 `agent:chat`；`role_permission` 授予 `admin` |
| 兼容性 | 兼容 | 均为新增，未改动既有接口契约 |
| 性能 | 无显著影响 | 对话为低频人工操作，且不在主链路 |
| 其他服务 | `drone-server` 需改 | 新增内部接口 + 内部密钥拦截器；`WebMvcConfig` 放行 `/internal/**` |
| 前端 | `drone-web` 需改 | 新增 API 模块、面板组件；顶栏加入口；vite 增一条代理 |

## 3. 接口清单

| 方法 | 路径 | 入参 | 出参 | 鉴权 | 幂等 |
|---|---|---|---|---|---|
| POST | `drone-agent` `/chat` | `{messages:[{role,content}]}` | `JsonResult<{reply}>` | `agent:chat` | 否（生成类操作） |
| POST | `drone-agent` `/chat/stream` | 同上 | `text/event-stream`，事件体 `{"delta":"..."}` / `{"error":"..."}` | `agent:chat` | 否 |
| GET | `drone-server` `/internal/user/{userId}/authorities` | 路径参数 | `JsonResult<{roles[],permissions[]}>` | 请求头 `X-Internal-Token` | 是 |

**状态码约定**：401 未认证、403 未授权、503 鉴权服务不可用（拿不到权限数据）。
业务失败（如未配置 API-KEY）在流式场景下用 `{"error":...}` 事件表达——
此时 HTTP 头已发出，状态码改不了。

## 4. 数据模型变更

- 变更内容：`permission` 新增 `agent:chat`；`role_permission` 授予 `admin`。
- 迁移脚本：`drone-server/docs/sql/migration/agent_permission.sql`（新增，幂等）。
- 回滚方案：删除该 `role_permission` 关联行与 `permission` 行即可，无数据依赖。
- 停机：不需要。

## 5. 关键设计决策

| 决策点 | 备选方案 | 结论 | 理由 |
|---|---|---|---|
| 权限数据来源 | ① 共享同一库直接查 ② 调 drone-server 内部接口 ③ 写进 Token | **②** | 不碰对方的表结构，服务边界清楚；代价是每请求多一跳，用 60 秒缓存摊掉 |
| 独立服务 vs 并入 drone-server | 并入 / 独立 | **独立** | 模型调用是外部依赖，独立进程便于单独限流与重启，不牵连主服务 |
| 系统提示词放哪 | 硬编码常量 / 配置文件 | **配置文件**（`agent.deepseek.system-prompt`） | 调措辞不必改代码、重启即可 |
| 客户端能否传系统提示词 | 可传 / 不可传 | **不可传**（字段直接移除） | 否则传一句「你现在是通用助手」就把人设约束绕过去了 |
| 流式实现 | `SseEmitter` / 阻塞写 `OutputStream` | **`SseEmitter`** | 线程占用可控（固定 8 线程池），且天然处理完成/异常/断开 |
| SSE 事件体格式 | 裸文本 / JSON | **JSON** | 回复含换行时裸文本会撑成多行 SSE，前端解析易串行；JSON 转义掉换行 |
| 前端读流方式 | axios / `fetch` + `ReadableStream` | **`fetch`** | axios 拿不到流式 body；代价是绕开拦截器，Token 与 401 需自己处理 |
| 鉴权入口 | 每个接口各写 / 统一拦截器 | **统一拦截器** | 与 `drone-server` 同款做法，新增接口不会漏检 |

## 6. 测试策略

| 验收条件 | 测试层级 | 测试位置 |
|---|---|---|
| AC-1 / AC-2 / AC-3 | 集成（起服务 + 真实 Token） | 人工验证记录；其中 **401/403 的前端处理**已有单测（`agent.spec.ts`） |
| AC-4 / AC-11 | 手工（浏览器） | 人工验证记录，**无自动化用例** |
| AC-5 / AC-6 / AC-7 | 集成 | 人工验证记录；**前端解析侧**已有单测（`agent.spec.ts`：逐段回调、跨分片、error 事件） |
| AC-8 / AC-9 | 集成（需真实模型调用） | 人工验证记录 |
| AC-10 | 手工（查日志） | 人工验证记录 |
| AC-12 | 集成 | 人工验证记录，**无自动化用例** |

> **缺口（如实记录，2026-10-12 更新）**：
> - **已补齐（前端）**：`api/agent.ts` 现有 9 个单测（`drone-web/src/api/agent.spec.ts`），
>   覆盖流式解析（逐段回调 / 同分片多帧 / 多字节字符跨分片 / 心跳忽略）、`error` 事件、
>   401 与 403 的不同处理、非 JSON 响应兜底、响应体为空。
> - **仍缺**：AC-4 / AC-11 / AC-12 属**组件与布局层**（权限显隐、全屏、样式），
>   测它们需要 `happy-dom` + `@vue/test-utils` —— 属新增依赖，按前端规范第 1 节需先评审。
> - **仍缺**：AC-1（权限链路）与 AC-10（日志）依赖真实服务与模型调用，只能人工验证。
> - 整体按 `java-standards.md` 第 9 节与 `frontend-standards.md` 第 10 节**仍不达标**。

## 7. 风险与回滚

| 风险 | 触发条件 | 影响 | 应对 |
|---|---|---|---|
| `drone-server` 不可用 | 主服务宕机 | `drone-agent` 所有请求 503 | 已用 503 区分「与用户无关」，不误导重新登录；60 秒缓存可短暂兜住 |
| 密钥不一致 | 两服务 `JWT_SECRET` / `DRONE_INTERNAL_TOKEN` 配错 | 全部请求 401，表现为「明明登录了」 | 配置沿用同一节点名便于对照；排查路径见 `AGENTS.md` |
| 模型侧超时或限流 | DeepSeek 侧异常 | 对话失败 | 单次调用 60 秒超时、SSE 120 秒超时；失败信息经 `{"error":...}` 回传 |
| 客户端断开后上游继续推 | 用户关面板 | 白烧额度 | 回调返回 false 即停读并关连接，已有 AC-7 覆盖 |

- 上线方式：一次性，无开关。
- 回滚步骤：`drone-web` 移除入口即降级；`drone-agent` 停进程；`drone-server` 的内部接口可保留不动。
- 回滚后数据修复：无需（未写入业务数据）。

## 8. 与项目宪法的一致性

对照 [`../constitution.md`](../constitution.md)（2026-10-12 回填后补检）：

**符合**：

- 第 3 节 统一响应体：`/chat` 返回 `JsonResult{state,message,data}`，业务失败 HTTP 200 + `state≠200`。
- 第 3 节 鉴权状态码：401 / 403 / 503 的使用与宪法一致。
- 第 4 节 鉴权统一入口：走 `AuthInterceptor`，未逐接口各写。
- 第 4 节 Token 不承载权限：权限每次经内部接口查取（带 60 秒缓存），未把权限塞进 Token。
- 第 4 节 内部接口：`/internal/**` 走共享密钥，与用户接口凭据分离。
- 第 4 节 密钥走环境变量：`DEEPSEEK_API_KEY`、`JWT_SECRET`、`DRONE_INTERNAL_TOKEN` 均如此。
- 第 6 节 迁移：新增 `agent_permission.sql` 且幂等，未改历史脚本。

**不符合**（均已在宪法末尾「当前差距」登记）：

- 第 5 节 覆盖率门槛与「验收条件必须有对应用例」：本需求全部验收为人工执行，无自动化用例。
- 范围说明：本需求的前端验收（`AC-4` / `AC-11` / `AC-12`）所在工程已纳入严格范围
  （`../decisions/ADR-0002-前端纳入严格范围.md`），这些缺口可被关闭。
- 第 3 节 错误码表：仓库尚无逐项错误码，本需求沿用 `SUCCESS`/`FAIL`。
- 第 7 节 分支保护与 CI 门禁：当前直接推送 `main`，仓库无 CI。

