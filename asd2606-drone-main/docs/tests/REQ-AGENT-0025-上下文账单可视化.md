# REQ-AGENT-0025 端到端回归记录：上下文账单可视化

- **日期**：2026-10-23
- **环境**：本机；`drone-server` 8080、`drone-mcp-server` 8082、
  `drone-agent` 8083（隔离实例，`agent.bill.root=./data/bills-smoke`，跑完已清理）、
  `drone-web` dev server 5174（proxy → 8083）
- **账号**：admin `13800001005`、pilot `13800001001`

## 一、SSE 下发（AC-1 / AC-2）

> 流式提问「现在有多少设备？只回答数量。」

`done` 事件实际负载：

```json
{"type":"done","reply":"6 台。","reasoningMs":865,"artifacts":[],
 "bill":{"promptTokens":23332,"contextTokens":11803,"cachedTokens":22912,
         "hitRate":98.2,"estimateTotal":11803,
         "kindTokens":{"SYSTEM":1006,"SESSION":13,"TOOL_DEFINITIONS":10612,
                       "TOOL_RESULT":106,"TOOL_CALL":44},
         "toolNames":["drone__device_list"],"rounds":2}}
```

- 两轮模型调用的 prompt 合计 23,332；
- 上下文大小取最后一轮 11,803；
- 分类含工具定义（10,612）与消息类别；
- `rounds=2` 与工具编排轮次一致。

## 二、落盘与接口（AC-3 / AC-5 / AC-6）

JSONL：`data/bills-smoke/bills-2026-10-23.jsonl`，首条 822 字节，一轮一行，含 `round`。

汇总接口（admin，`scope=all`）：

```json
{"requests":1,"promptTokens":23332,"cachedTokens":22912,"hitRate":98.2,
 "avgContextTokens":11803,"fingerprints":1,
 "byKind":[{"kind":"TOOL_DEFINITIONS","tokens":21224}, …],
 "byDay":[{"day":"2026-10-23","promptTokens":23332,"cachedTokens":22912}],
 "topTools":[{"tool":"drone__device_list","calls":1}]}
```

- `requests=1`：同一 trace 的两轮算一个请求；
- `fingerprints=1`：前缀稳定。

pilot 调 `scope=all`：

```json
{"state":403,"message":"查看全部账单需要管理员权限","data":null}
```

## 三、页面（AC-1 / AC-2 / AC-3 / AC-4）

用 Chrome（Playwright，channel=chrome）实际登录并操作：

1. `/agent/bills`：KPI 显示「请求数 1、输入 23.3k、平均上下文 11.8k、命中率 98.2%、指纹 1」；
   按天趋势、分类构成、工具 Top 10 三张图渲染；表格出现 `bill-vis-smoke` 一行；
2. 聊天面板发送同一问题后，助手消息下出现：
   `本次 11.9k token · 缓存 98.2% ›`；
3. 浏览器控制台无错误；截图：`/tmp/bills-page.png`、`/tmp/chat-bill.png`（临时文件）。

## 四、测试

- `drone-agent`：**355 passed**
  - `BillStoreTest` 5 条：轮次与摘要、用户隔离与 limit、按 trace 聚合、过期清理、关闭开关；
- `drone-web`：`type-check`、`lint` 通过；
- 修复项：账单 API 前端路径初版写成 `/chat/bills`，会命中 drone-server；改为
  `/agent/chat/bills` 后页面才有数据（由浏览器实测发现）。

## 五、未覆盖

- 历史消息不补账单（刷新后只显示新产生的「本次消耗」）；
- 未做饼图/分页/CSV 导出（当前为按天趋势 + 分类 + Top10 + 最近 100 条）；
- 未做多实例共享（JSONL 单实例）；
- 未做人民币计费与配额告警（P3）。

## 六、UI 修复记录（用户反馈「意图不明确、图有重叠」）

反馈现象：三张图只有标题（按天消耗 / 分类构成 / 工具调用），看不出在回答什么问题；
按天图的图例压在横轴日期上；图表区高度依赖最小高度，窄卡片里容易挤。

修复内容：

1. **标题改成问题式 + 说明备注**：
   - 「每天花了多少」：按天统计实际输入 token 与缓存命中部分；
   - 「花在了哪里」：区间累计的系统提示词 / 工具定义 / 会话 / 工具结果占比；
   - 「哪些工具最常被用」：按请求次数排序，同一请求内多轮只算一次。
2. **布局**：图表外套固定 260px 高度容器；网格卡片 `min-width: 0`；
   分类与工具图使用 `containLabel`，纵轴标签不再溢出。
3. **按天图**：图例固定顶部、网格顶部留 44px；日期只显示 `MM-DD`，
   `hideOverlap` + `interval: auto`，30 天也不会挤成一团。
4. **分类/工具图**：`inverse: true` 让最大项排在最上面；数值轴用 k/M 缩写。

验证：造 7 天账单数据后用 Chrome 截图检查——说明文字完整，图例不再压住柱子/日期，
日期不重叠，纵轴标签未截断，三张卡片无重叠；浏览器控制台无错误。
