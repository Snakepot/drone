# REQ-AGENT-0015 端到端回归记录：HTML 产物预览与下载

- **日期**：2026-10-22
- **环境**：本机；`drone-server` 8080、`drone-mcp-server` 8082、`drone-agent` 8083（独立实例，
  产物根目录 `./data/artifacts-smoke`，跑完已清理）
- **账号**：admin `13800001005`

## 一、生成（AC-1 / AC-2 / AC-8）

> 用户：「出一下今天的 HTML 版运营日报」

响应：

```json
{
  "state": 200,
  "data": {
    "reply": "今日没有新建任务……（摘要，514 字）",
    "artifacts": [{
      "id": "8c1ae774-60d2-4bd4-a70c-8fae210a31ca",
      "fileName": "运营日报 · 2026-10-22.html",
      "sizeBytes": 13409
    }]
  }
}
```

- `reply` 里**不含** `<html`，只剩摘要与一行提示；
- 会话里持久化的也是清洗后的回复（单测 `capturesArtifactBeforePersisting` 钉住）。

## 二、读取（AC-3 / AC-4 / AC-5）

```text
GET /chat/artifacts/{id}                → 200
  Content-Type: text/html;charset=UTF-8
  X-Content-Type-Options: nosniff
  Content-Security-Policy: sandbox
  Content-Disposition: inline; filename*=UTF-8''…（空格已编码为 %20）
GET /chat/artifacts/{id}?download=1     → 200，attachment
GET /chat/artifacts/does-not-exist      → 404
```

## 三、模板与渲染

- 产物内容包含模板特征（`class="kpis"`、`Daily Operations` 等），说明模型确实读取并使用了
  `assets/daily-report.html`；
- 用 Chrome 打开落盘文件截图检查：卡片式日报正常渲染（KPI、任务分布条、人员设备、告警表、
  风险建议），无重叠、无未替换的 `{{占位符}}`；
- 前端卡片用 `sandbox=""` 的 iframe 预览 + `saveBlob` 下载，未引入新依赖。

## 四、冒烟中发现并修复的问题

1. **技能模板被工具结果压缩器压成 10 个字符**
   - 现象：模型说「模板读取回来是空的」，只能按正文版式重画。
   - 根因：`read_skill_resource` 的结果按通用预算（单次 2000 字）压缩，12KB 模板被裁掉，
     中间还触发了一次模型摘要（白烧一次调用）。
   - 修复：`use_skill` / `read_skill_resource` **豁免**压缩预算（它们是有上限的「说明书」）；
     新增单测 `skillResourcesBypassCompression`。
2. **轮次预算再次不足**
   - 现象：日报实测用了 10 次工具调用，6 轮时被收回工具，模型输出伪工具文本（DSML）。
   - 修复：`max-rounds` 6 → 8；并在最终答复里检测 `DSML`，替换为兜底话术
     （单测 `replacesPseudoToolText`）。
3. **下载文件名里的空格变成 `+`**
   - 根因：`URLEncoder` 把空格编码为 `+`，而 RFC 5987 的 `filename*` 里 `+` 是字面量。
   - 修复：编码后把 `+` 换成 `%20`；接口单测断言含 `%20` 且不含 `+`。

## 五、未覆盖

- 未在网页面板里做人工点击验收（需要登录 + 打开抽屉）；前端逻辑通过 type-check、lint 与
  API 单测覆盖，后端链路已用真实 HTTP 验证。
- 产物**历史回看**未做：刷新页面后卡片消失（本期不做）。

## 六、测试

- `drone-agent`：**292 passed**（新增抽取/存储/接口/会话/压缩豁免/DSML 用例）。
- `drone-web`：type-check、lint 通过；**22 passed**（新增 `fetchArtifact` 2 条）。
