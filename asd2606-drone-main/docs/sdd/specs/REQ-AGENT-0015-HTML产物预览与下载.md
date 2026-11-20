# 规格 — REQ-AGENT-0015 日报等 HTML 产物：对话框内预览 + 下载

> 本文件只回答**做什么、达到什么效果**。方案见 `plans/`。

- **需求 ID**：`REQ-AGENT-0015`
- **提出人 / 日期**：需求方 / 2026-10-22
- **负责人**：AI（按 SDD 执行）

## 1. 背景与目标

运营日报流程会让模型输出完整 HTML，但聊天面板是纯文本渲染，用户看到的是 HTML 源码。
目标：**HTML 报告在对话框内安全渲染（预览），并提供下载按钮**；同时避免整份 HTML
长期留在会话上下文里重复计费。

## 2. 范围

### 做（In Scope）

- 后端从模型回复中抽取完整 HTML 文档，存为文件，回复里只留摘要与一行提示。
- `ChatResponse` 增加 `artifacts`；新增受鉴权的产物读取接口（预览 / 下载）。
- 前端新增产物卡片：sandbox iframe 预览、下载、放大。
- 日报 Skill 要求 HTML 放在 ` ```html ` 围栏内，并带 `<title>`（用作文件名）。

### 不做（Out of Scope）

- 不做 Markdown / 通用富文本渲染。
- 不做 SSE 路径的产物交付（前端当前不用流式）。
- 不做产物历史回看（消息不重拉，刷新后消失）。
- 不引入前端 sanitizer 依赖。

## 3. 验收条件

| 编号 | 前置 | 操作 | 预期 |
|---|---|---|---|
| AC-1 | 模型输出 ```html 完整文档 | 走一次 `/chat` | `reply` 不含 HTML 源码，只剩摘要 + 一行「报告已生成」 |
| AC-2 | 同上 | 查看响应 | `artifacts` 含 `id/fileName/sizeBytes` |
| AC-3 | 产物存在、本人登录 | `GET /chat/artifacts/{id}` | 200，`text/html`，带 `Content-Security-Policy: sandbox` |
| AC-4 | 他人或不存在 | 同上 | 404，不区分「不存在」与「无权限」 |
| AC-5 | 产物存在 | `?download=1` | `Content-Disposition: attachment`，文件名正确 |
| AC-6 | 前端收到 artifacts | 打开聊天面板 | 卡片内 iframe 正常渲染；下载可保存；放大可用 |
| AC-7 | 回复里没有 HTML / 超过 512KB | `/chat` | 不产出 artifacts，回复原样返回 |
| AC-8 | 有产物 | 查看会话持久化内容 | 存的是**去掉 HTML 的回复** |

### 非功能要求

- 单文件上限 512KB；TTL 7 天；读取接口响应 `Cache-Control: private, max-age=300`。
- 产物只存 `data/artifacts/`，已被 `.gitignore` 覆盖。
- 预览禁用脚本（iframe `sandbox=""` + 响应 CSP `sandbox`）。

## 4. 约束与依赖

- 依赖现有 `/chat` 非流式链路与 `agent:chat` 鉴权。
- 产物按 `userId` 归属，目录即权限边界。

## 5. 待定项

| 编号 | 问题 | 结论 | 决定人/日期 |
|---|---|---|---|
| Q-1 | 内联渲染还是抽成文件 | 抽成文件（避免上下文重复计费） | 方案评审 / 2026-10-22 |
| Q-2 | 前端用 v-html 还是 iframe | iframe + sandbox | 方案评审 / 2026-10-22 |
| Q-3 | 产物保留多久 | 7 天 | 方案评审 / 2026-10-22 |
