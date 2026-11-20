# 技术方案 — REQ-AGENT-0015 日报等 HTML 产物：对话框内预览 + 下载

> 输入是 `specs/REQ-AGENT-0015-HTML产物预览与下载.md`。

- **需求 ID**：`REQ-AGENT-0015`
- **作者 / 日期**：AI / 2026-10-22
- **评审人 / 评审日期**：需求方 / 2026-10-22（方案已确认）

## 1. 方案概述

模型回复里的完整 HTML 文档由后端抽取、落盘到 `data/artifacts/{userId}/{id}.html`，
回复中只保留摘要与一行提示；`ChatResponse` 返回 `artifacts` 列表。
前端拿到 `artifacts` 后，用带 JWT 的 axios 取回 HTML，生成 Blob URL，
放进 **sandbox iframe** 预览，并提供下载与放大；不把 HTML 注入主文档。

## 2. 影响面

| 维度 | 影响 | 说明 |
|---|---|---|
| 接口 | 新增 | `GET /chat/artifacts/{id}`（预览/下载），受 `agent:chat` 保护 |
| 数据模型 | 无 | 产物存文件，不落库 |
| 兼容性 | 兼容 | `ChatResponse` 增加字段；无产物时为空列表 |
| 性能 | 正向 | 会话上下文不再包含整份 HTML |
| 其他服务 | 无 | 只涉及 drone-agent 与 drone-web |

## 3. 接口清单

| 方法 | 路径 | 入参 | 出参 | 鉴权 |
|---|---|---|---|---|
| GET | `/chat/artifacts/{id}` | path id；可选 `download=1` | HTML 文件流 | JWT + `agent:chat` + 归属 |

## 4. 数据模型变更

无。文件布局：

```text
data/artifacts/{userId}/{artifactId}.html
data/artifacts/{userId}/{artifactId}.json   # fileName / createdAt / conversationId
```

## 5. 关键设计决策

| 决策点 | 备选 | 结论 | 理由 |
|---|---|---|---|
| HTML 放哪 | 留在回复 / 抽文件 | **抽文件** | 避免每轮重复计费；回复可读性更好 |
| 前端渲染 | v-html + sanitizer / iframe | **sandbox iframe** | 报告自带样式与文档结构；iframe 隔离更彻底，零新依赖 |
| 产物鉴权 | 公开 URL / 鉴权接口 | **鉴权接口 + 归属目录** | 报告含业务数据 |
| 文件传输 | 返回 URL 让浏览器直连 / 前端取 Blob | **前端取 Blob** | JWT 在 Authorization 头，iframe 不会自动携带 |

## 6. 测试策略

| 验收条件 | 层级 | 位置 |
|---|---|---|
| AC-1 / AC-2 / AC-7 / AC-8 | 单元 | `ArtifactExtractorTest`、`ConversationServiceTest` |
| AC-3 / AC-4 / AC-5 | 单元 | `ChatArtifactControllerTest` |
| AC-6 | 手工 | 网页面板 |

## 7. 风险与回滚

| 风险 | 触发 | 影响 | 应对 |
|---|---|---|---|
| 模型不写围栏 | 输出裸 HTML | 抽不到 | 抽取器兼容裸文档；Skill 写死格式 |
| 报告含脚本 | 模型输出 `<script>` | XSS | iframe `sandbox=""` + CSP |
| 文件堆积 | 长期使用 | 磁盘 | TTL 7 天，启动/写入清理 |

- 回滚：删除产物配置、前端卡片即可；不影响聊天主链路。

## 8. 与项目宪法的一致性

- §4 安全：产物鉴权 + 归属校验 + `nosniff` + CSP sandbox；不把模型输出注入主 DOM。
- §5 测试：单测用临时目录与直接调用，不依赖真实网络。
- §7 配置：路径、上限、TTL 全部外置。
