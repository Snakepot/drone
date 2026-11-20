# 任务拆解 — REQ-AGENT-0015 HTML 产物预览与下载

- **需求 ID**：`REQ-AGENT-0015`
- **负责人 / 日期**：AI / 2026-10-22

## 任务表

| 编号 | 任务 | 产出 | 完成标志 | 依赖 | 状态 |
|---|---|---|---|---|---|
| T-1 | 规范三件套与 ADR-0010 | docs | 评审通过 | - | 已完成 |
| T-2 | 产物抽取与存储 | `artifact` 包 + `ArtifactProperties` | 抽取/存储/归属/TTL 用例通过 | T-1 | 已完成 |
| T-3 | 响应与编排接入 | `ChatResponse` + `ConversationService` | 回复清洗、会话不含 HTML | T-2 | 已完成 |
| T-4 | 产物读取接口 | `GET /chat/artifacts/{id}` | 预览/下载/404 用例通过 | T-3 | 已完成 |
| T-5 | Skill 围栏格式 | SKILL.md | 模型输出可被抽取 | T-2 | 已完成 |
| T-6 | 前端卡片 | API + `AgentArtifactCard` + 面板 | 预览/下载/放大可用 | T-4 | 已完成 |
| T-7 | 全量测试与冒烟 | 测试与记录 | 后端/前端全绿，真机可预览下载 | T-6 | 已完成 |

## 验收条件覆盖

| 验收条件 | 任务 |
|---|---|
| AC-1 / AC-2 / AC-8 | T-3 |
| AC-3 / AC-4 / AC-5 | T-4 |
| AC-6 | T-6 |
| AC-7 | T-2 |

## 执行规则

- 一次只做一条任务；完成后更新状态并给证据。
- 发现问题先改规范/方案，不夹带。

## 执行记录（2026-10-22）

- 后端：抽取/存储/接口/会话接入完成；`mvn test` → **292 passed**。
- 前端：`agent` API + `AgentArtifactCard`（sandbox iframe + 下载 + 放大）+ 面板接入；
  type-check、lint 通过，`npm run test` → **22 passed**。
- 真机：日报回复已清洗（无 HTML），产物 13,409 字节；预览/下载/404 响应头符合预期；
  产物内容确由 `assets/daily-report.html` 模板生成。
- 冒烟中修复：技能资料被压缩器裁成 10 字（改为豁免）、轮次 6→8、DSML 兜底、
  下载文件名空格编码 `+` → `%20`。
- 详见 `docs/tests/REQ-AGENT-0015-端到端回归.md`。
