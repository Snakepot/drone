# AGENTS.md

This file provides guidance to the AI agent when working with code in this repository.

## 仓库构成

五个独立工程，各自构建，根目录**没有**聚合 pom / workspace：

| 目录           | 是什么                                                              | 端口 / 包名                 |
| -------------- | ------------------------------------------------------------------- | --------------------------- |
| `drone-server` | 后端主服务（Spring Boot 3.3.3 + Java 17 + Maven + MyBatis + MySQL） | 8080 / `cn.tedu.drone`      |
| `drone-agent`  | 智能助手独立服务（同栈）                                            | 8081 / `cn.tedu.droneagent` |
| `drone-mcp-server` | 把 `drone-server` 现有接口暴露为 MCP 工具（助手操作业务用）      | 8082 / `cn.tedu.drone.mcp` |
| `drone-web`    | 管理端（Vue 3 + Vite + TS + Element Plus + Pinia）                  | 5173                        |
| `drone-app`    | 飞手端（uni-app，同时出 H5 与微信小程序）                           | —                           |

`tools/drone-simulator/simulator.py` 是设备模拟器，扮演厂商侧往 MQTT 发遥测。

## 开发流程（SDD，强制）

流程与门禁全文见 [`docs/sdd/README.md`](docs/sdd/README.md)；编码细则：Java 见 [`docs/sdd/java-standards.md`](docs/sdd/java-standards.md)、前端（TS / Vue）见 [`docs/sdd/frontend-standards.md`](docs/sdd/frontend-standards.md)。落地到日常操作：

- **【强制】先规范、后代码。** 动手前先读 `docs/sdd/constitution.md` 与当前需求的三份文件 `specs/`、`plans/`、`tasks/`（同一需求 ID 前缀）。没读就写代码，等于没走流程。
- **一次只做一条任务**，不擅自扩大范围。发现「顺手也该改」的地方，记下来提新需求，不要夹带进当前改动。
- **规格有缺口就停下来问**，不许自行假设后继续；也不许「代码里先这么写，规范回头补」。
- **不得修改** `constitution.md`，也不得改 `docs/sdd/templates/` 的章节结构。
- 新需求开始前，按 `docs/sdd/templates/` 建 `specs/` → `plans/` → `tasks/` 三份文件，评审通过再动代码。
- 【例外】本仓库是**存量项目**，尚未回填规范。按「每次改动补规范」逐步接入：**改动前先补该需求的规范文件，再改代码**。

## 命令

```bash
cd drone-server && mvn compile          # 无 mvnw，用系统 mvn
cd drone-agent  && mvn test             # 助手单测（含 MCP 客户端）
cd drone-mcp-server && mvn test         # MCP 服务单测（目录/协议/转发）
cd drone-web    && npm run type-check   # 前端无测试，以 type-check 为准
cd drone-web    && npm run lint         # oxlint + eslint
cd drone-app    && npm run type-check
```

`drone-web` / `drone-app` 的 node 版本要求严格（`drone-web` 为 `^22.18 || >=24.12`，`.nvmrc` 是 24），版本不对时先切 node 再说别的。

## 必须知道的架构约定

**统一响应体**：后端一律返回 `{state, message, data}`，**业务失败也是 HTTP 200**（只有鉴权失败才用 401/403）。前端必须判 `state`（成功码 `0` 或 `200`），不能只看 HTTP 状态码。

**权限不在 JWT 里**：Token 只装 `sub`(用户 ID) 和 `username`；角色与权限由 `AuthInterceptor` 每次请求查库补全（刻意如此：改权限下次请求立即生效）。所以——
- 不要试图从 Token 里读权限。
- `drone-agent` **没有自己的登录**。它用同一把密钥 + 同一个 issuer 验签 `drone-server` 签发的 Token，再调 `/internal/user/{id}/authorities` 取 RBAC。两边的 `drone.jwt.*` 必须一致，`drone.internal.token` 与 `agent.internal.token` 必须一致，否则表现为「明明登录了却 401」。

**内部接口**：`/internal/**` 不走用户 JWT，用共享密钥（请求头 `X-Internal-Token`）鉴权，**不得暴露到网关之外**。

**一套接口服务两个终端**：管理端与小程序共用同一套接口，靠权限码区分（`task:list` 给管理端、`task:execute` 给飞手）。**不要为某个端另建一套接口**；仅当同一动作的入参/出参本质不同时才加专用端点。

**ThreadLocal**：`UserContext` 存当前登录人，请求结束必须清理；异步请求（`/chat/stream` 那条 SSE）还要在 `afterConcurrentHandlingStarted` 里清 —— 请求线程进异步后会带身份回线程池，不清理就是越权。

**前端接口地址**：`drone-web` 与 `drone-app` 的 H5 都走 `/api` + devServer 代理（**后端没配 CORS**，直连会被挡）；小程序无 CORS 概念，直连 `http://localhost:8080`，需在开发者工具里勾「不校验合法域名」。`drone-web` 的 `vite.config.ts` 里 `/api/agent` 那条代理**必须排在 `/api` 之前**（按声明顺序匹配，顺序反了会被 `/api` 吃掉转发到 8080）。

## 代码风格

注释是**中文、面向原理的教学式注释**（常见 `<h2>` 分节 javadoc）。这是本仓库既定风格，**不要精简、不要翻译、不要改成英文**。

**读者定位是初级工程师**（细则见 [`docs/sdd/java-standards.md`](docs/sdd/java-standards.md) 第 13 节、[`docs/sdd/frontend-standards.md`](docs/sdd/frontend-standards.md) 第 13 节）：

- 注释**既讲「为什么」也讲「怎么执行」**：只讲 why 不够，新人可能连这段在干什么都还没看懂
- 关键流程**按步骤编号**（第一步 / 第二步…）；反直觉处要写清「为什么不那样做会怎样」
- 复杂正则、下标与边界计算必须解释含义
- **日志同样是给初级工程师看的**：关键节点要有「一进一出」、能串成一条完整链路；
  每条日志要自解释在干什么（不是只打一个值）；**异步动作必须标明是异步的** ——
  否则「日志里没有」和「这件事没发生」分不清

## 数据与迁移

- 没有 Flyway/Liquibase，**迁移 SQL 手工按顺序执行**。都在 `drone-server/docs/sql/`：`schema.sql` + `data.sql` 建库，`migration/*.sql` 是增量，每个文件头部写明自己的执行顺序与前提。改表结构**新增**迁移文件，不要改老文件。
- `drone-server/docs/*.md` 是设计决策记录（任务、告警、RBAC、大屏、业务分析…）。改业务行为前先读对应文档，改完同步更新。

## Git 约定

- 直接提交到 `main` 并推送 `origin`（Gitee）。
- 提交信息用中文，格式 `动作：细节`（如 `落地业务分析第三步（预聚合日表）…`）。**不用 `feat:` / `fix:` 这类前缀。**
  - 【例外说明】这与 `docs/sdd/java-standards.md` 第 11 节要求的 Conventional Commits **不一致**：本仓库是存量风格，暂时沿用；**新项目按 java-standards.md 执行**。两者共同的要求是：提交信息必须能追溯到需求 ID。
- 提交前确认没把 `target/`、`node_modules/`、真实密钥带进去。

## 环境变量

| 变量                   | 用途                                                                |
| ---------------------- | ------------------------------------------------------------------- |
| `JWT_SECRET`           | `drone-server` 与 `drone-agent` 必须填同一个值                      |
| `DRONE_INTERNAL_TOKEN` | 两服务间共享密钥，两边必须一致                                      |
| `DEEPSEEK_API_KEY`     | `drone-agent` 调模型用；未配置时服务仍能启动，`/chat` 返回提示      |
| `DRONE_SERVER_URL`     | `drone-agent` 调 `drone-server` 内部接口的地址                      |
| `MQTT_ENABLED=false`   | 不关心设备时关掉，免去连本地 broker 的日志噪音                      |
| `DRONE_UPLOAD_ROOT`    | 附件落盘目录（默认 `./data/upload`，此目录已在 .gitignore，勿提交） |

注意 
每次回复之前都要说 您辛苦了