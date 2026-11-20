# 代码评审清单（drone 仓库）

> 按领域查；每条都对应仓库里的规则。**审到哪条、引哪条**，不要凭印象。

## 1. SDD 合规（先看这个）

- [ ] 改动是否关联一个 `REQ-<模块>-<四位序号>`？
- [ ] `docs/sdd/specs|plans|tasks/` 三份文件是否已存在并评审通过？
- [ ] 改动是否**只做当前任务**？有没有夹带「顺手改」的功能/重构/格式化？
- [ ] `specs/` 的每条 AC 是否都能在测试里找到对应用例？（做不到是否在 `plans/` 写了原因）
- [ ] 接口/数据模型有变更时，`plans/` 的「接口清单」「数据模型变更」是否同步？
- [ ] 发现规格缺口时，是否是**先改 specs/plans 再改代码**，而不是「代码先这么写」？

依据：`AGENTS.md`「开发流程（SDD，强制）」、`constitution.md` §7。

## 2. Java 后端

### 2.1 结构与分层
- [ ] 依赖方向单向：`web → service → dao`；dao 不引用 service，service 不引用 controller 类。
- [ ] 业务模块分包（`device/task/route/...`），不在顶层 `controller/` 堆所有模块。
- [ ] Controller 不写业务、不直接调 dao；Service 不感知 HTTP（无 `HttpServletRequest`/`ResponseEntity`）。
- [ ] 禁止把 Entity 直接当接口出参；入参 DTO、出参 VO。

依据：`java-standards.md` §1、§3；`constitution.md` §2。

### 2.2 接口契约
- [ ] 统一响应体 `{state,message,data}`；**业务失败 HTTP 200 + `state≠200`**；鉴权失败才用 401/403。
- [ ] 分页用统一结构（`PageResult`），并有**每页上限**。
- [ ] 查询不用 POST；写接口标明是否幂等。
- [ ] 对外入参在**服务端**校验（不依赖前端）。

依据：`java-standards.md` §4；`constitution.md` §3。

### 2.3 异常与日志
- [ ] 无 `catch` 空实现（吞异常）。
- [ ] 无 `System.out` / `printStackTrace`；日志用 SLF4J **占位符**。
- [ ] 日志不带密码/令牌/身份证/完整手机号/`Authorization`。
- [ ] 关键流程有**一进一出**，每条日志**自解释**在干什么。
- [ ] **异步动作的日志标明是异步的**（线程池/回调/定时任务）。
- [ ] 失败日志写清「接下来怎么处理」（重试/丢弃/返回什么）。

依据：`java-standards.md` §5（尤其「日志读者定位是初级工程师」四条）。

### 2.4 安全
- [ ] 非公开接口在**统一入口**鉴权（`AuthInterceptor`），没有逐接口各写一套。
- [ ] 按 ID 操作资源时**校验归属**（防水平越权）。
- [ ] 权限判定在服务端；前端隐藏不算边界。
- [ ] 文件上传校验类型白名单与大小；路径不由用户输入直接拼接。
- [ ] `/internal/**` 走共享密钥（`X-Internal-Token`），不暴露到网关外。
- [ ] Token 只装身份（`sub`/`username`），**不把角色/权限塞进 Token**。

依据：`java-standards.md` §8；`constitution.md` §4。

### 2.5 配置与密钥
- [ ] 无硬编码地址/账号/密钥/开关；密钥走环境变量，配置里只留 `${VAR:}` 占位。
- [ ] `git diff` 里没有真实密钥、生产地址、真实用户数据。
- [ ] 提示词正文只在 `resources/prompts/**/*.md`（不在 yml / Java 字符串里）。
- [ ] 工具元数据只在 `agent.tool.catalog`（代码里不硬编码 description/参数）。

依据：`java-standards.md` §7；`AGENTS.md`「环境变量」；`REQ-AGENT-0005`/`0008`。

### 2.6 数据与迁移
- [ ] 表结构变更**新增**迁移脚本（`drone-server/docs/sql/migration/`），不改老文件。
- [ ] 迁移脚本幂等、头部写清执行顺序与前提，有回滚方案。
- [ ] 表必备 `id/create_time/update_time`；`lower_snake_case`；无外键；无物理删除。
- [ ] 无 `SELECT *`；无循环查库（N+1）；SQL 用 `#{}`。

依据：`java-standards.md` §6；`constitution.md` §6。

### 2.7 测试
- [ ] 单测 AIR（自动、独立、可重复）；**不依赖真实数据库/MQ/第三方**。
- [ ] 需要真实依赖的，归**集成测试**并单独标记（如 `*IT` + `@Tag("integration")`）。
- [ ] 断言具体（值/异常类型与消息），不是「不抛异常」。
- [ ] 修 Bug 先有**能复现的失败用例**。
- [ ] 测试命名表达「行为 + 预期」，不用 `test1`。

依据：`java-standards.md` §9。

### 2.8 注释
- [ ] 中文、面向初级工程师：**既讲为什么，也讲怎么执行**（步骤/边界）。
- [ ] 复杂正则、下标与边界计算有解释。
- [ ] 反直觉处写明「不那样做会怎样」。
- [ ] 不写「本需求」「added for X」这类会腐烂的话。

依据：`AGENTS.md`「代码风格」；`java-standards.md` §13。

## 3. 前端（Vue / uni-app）

- [ ] 所有 HTTP 调用走 `api/` 模块 + `api/request.ts`；组件/store 里没有直接 `axios`/`fetch`。
- [ ] **判断成败看 `state`**，不是只看 HTTP 状态码。
- [ ] 401 处理、错误提示集中在 `request.ts`，调用方不重复弹。
- [ ] 新增页面用 `meta.permission` 声明权限码，且与后端 RBAC 一致。
- [ ] **前端拦截只是体验**，没有「前端拦了后端就不用拦」的假设。
- [ ] 颜色/间距用 `assets/theme.css` 变量，不硬编码色值；深浅色都看一眼。
- [ ] `drone-web` 的 `/api/agent` 代理排在 `/api` **之前**。
- [ ] `drone-app` 用 `uni.request`（小程序无 `XMLHttpRequest`），不用 axios。
- [ ] 提交前 `npm run type-check` 与 `npm run lint` 零失败。
- [ ] `api/`/`utils/`/`composables/` 的纯逻辑有 Vitest 用例；不依赖真后端。
- [ ] 模板里的条件渲染/嵌套三元有注释说明业务状态。

依据：`frontend-standards.md` §6、§8、§9、§10、§11、§12、§13。

## 4. 提交与交付

- [ ] 提交信息能追溯到需求 ID（本仓库约定：中文 `动作：细节` + REQ；新项目按 Conventional Commits）。
- [ ] 一个提交只做一件事；格式化/无行为改动单独提交。
- [ ] 不含 `target/`、`node_modules/`、`dist/`、`data/`、IDE 配置、真实密钥、大文件。
- [ ] 提交前 `mvn -q compile` / 测试 / 前端检查通过（或明确说明未跑及原因）。

依据：`AGENTS.md`「Git 约定」；`java-standards.md` §10、§11；`frontend-standards.md` §11。

## 5. 本仓库踩过的真实坑（高信号，优先扫）

这些不是理论，是实际出过问题的：

1. **自造 `tool_call_id`**：确认续跑时新造 id，网关按 id 关联推理内容 → 必然 400。
   → 重构工具调用消息时，**id 必须原样复用**。
2. **阈值设 0**：不相关的问题也返回 top-k，**「查不到」永远不会发生**。
   → 阈值要有实测依据，并与评估集一起调。
3. **空权限被翻成「不过滤」**：`ContainsAny` 空列表 → 空表达式 → 谁都能看。
   → 空值用「匹配零条」显式表达（`None`），构造时拒绝空列表。
4. **`@SpringBootTest` 触发真实外部调用**：启动钩子在测试里也执行，单测真的调了嵌入 API、写了向量库。
   → 外部副作用要有开关，测试环境关掉并加断言。
5. **yml 嵌套数组绑进 `Map`**：`required: [place]` 变成 `{"0":"place"}` → 模型 400。
   → 复杂结构写成 JSON 字符串，并在启动期校验。
6. **ThreadLocal 跨线程**：工具在线程池里跑，`UserContext` 取不到。
   → 身份在请求线程取好、**显式传参**。
7. **配置与方案不一致**：方案定集合 `A`，yml 还是草稿名 `B`，不知道往哪写。
   → 改名要**全链路对齐**：方案、配置、文档、测试断言。
8. **异步归档不标异步**：日志里没有，分不清「没发生」还是「还没轮到」。
   → 异步日志必须写明是异步。

## 6. 输出自检

- [ ] 每个发现都有 `file:line` 与规则出处。
- [ ] 区分了【发现】与【推断】。
- [ ] 只报了**真正执行过**的验证命令；没跑的单列「未执行 + 原因」。
- [ ] Nit 不超过 5 条，且标了「可选」。
- [ ] 没有擅自重写代码（用户要求除外）。
- [ ] **报告已按 `assets/report-template.md` 落到** `docs/reviews/<YYYY-MM-DD>-<对象>.md`；
      用户要求不落盘时，已注明「未生成报告」。
- [ ] 报告与聊天摘要**内容一致**；状态列（待修 / 已修 / 接受）如实标注。
- [ ] 报告里没有「没跑过的命令、没读过的文件、猜出来的行号」。
