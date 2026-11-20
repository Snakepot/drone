# ADR-0010 HTML 产物落盘交付，前端用 sandbox iframe 预览

- **状态**：已接受
- **日期**：2026-10-22
- **决策人**：项目负责人
- **记录人**：AI（按规范登记）
- **关联**：[`specs/REQ-AGENT-0015-HTML产物预览与下载.md`](../specs/REQ-AGENT-0015-HTML产物预览与下载.md)

## 背景

助手会生成完整 HTML 报告，但聊天面板是纯文本渲染，用户看到的是源码。
需要在对话框内渲染，并提供下载。

## 决策

### 1. HTML 从回复中抽出、落盘，回复只留摘要

- 后端在持久化助手消息**之前**抽取 HTML，存为 `data/artifacts/{userId}/{id}.html`，
  回复中替换为一行「报告已生成」。
- **不选「整份 HTML 留在回复里由前端解析」**：
  - 10–15KB 的 HTML 会长期留在会话上下文，后续每一轮都重复计费；
  - 依赖模型稳定输出代码围栏，漏写就退化成源码。

### 2. 预览用 sandbox iframe，不用 `v-html`

- 模型输出属于**不可信内容**；报告还自带 `<style>`，注入主文档会污染全局主题。
- `<iframe sandbox="">` 禁用脚本、表单、顶层跳转与同源；报告样式被隔离在 iframe 内。
- 服务端同时下发 `Content-Security-Policy: sandbox` 与 `X-Content-Type-Options: nosniff`。
- **不选 `v-html + DOMPurify`**：新增依赖、样式冲突、XSS 面更大。

### 3. 产物走鉴权接口，前端用 Blob 加载

- 报告含业务数据，不能公开 URL。
- JWT 在 `Authorization` 头里，`<iframe src>` 不会自动携带，所以前端用 axios 取 Blob，
  再 `createObjectURL` 给 iframe 预览、给 `saveBlob` 下载。
- 归属按 `userId` 目录隔离；他人访问与不存在都返回 404。

### 4. 保留 7 天

- 产物是「临时交付物」，不是长期档案；`ttl: 7d`，启动与写入时清理。
- 本期不做历史回看（消息本来就不重拉）。

## 后果

- 正面：会话上下文不再膨胀；预览安全；下载是真实文件；零前端依赖。
- 负面：多一个存储目录与接口；刷新页面后产物卡片消失（本期不做历史）。
- 明确**不选**：内联渲染、`v-html + sanitizer`、公开静态目录、SSE 产物事件（后续需要再加）。
