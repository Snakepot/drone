# REQ-AGENT-0016 端到端回归记录：流式输出与思考过程

- **日期**：2026-10-22
- **环境**：本机；`drone-server` 8080、`drone-mcp-server` 8082、`drone-agent` 8083（独立实例，
  会话根 `./data/agent-memory-smoke`、产物根 `./data/artifacts-smoke`，跑完已清理）
- **账号**：admin `13800001005`

## 一、事件协议（AC-1 / AC-2 / AC-3）

> 用户：「现在有多少设备？只回答数量。」

`POST /chat/stream`（带 `Authorization: Bearer …`）实际收到的 SSE 顺序：

```text
data:{"type":"reasoning","delta":"The"}           ← 27 个思考增量
...
data:{"type":"status","text":"正在执行 1 个工具：[drone__device_list]"}
data:{"type":"content","delta":"6"}               ← 4 个正文增量
data:{"type":"content","delta":" "}
data:{"type":"content","delta":"台"}
data:{"type":"content","delta":"。"}
data:{"type":"done","reply":"6 台。","reasoningMs":1077,"artifacts":[]}
```

- 思考增量先于正文，正文在工具执行后才开始 —— 与面板「先思考、再工具、最后正文」的顺序一致；
- `status` 在**工具执行前**发出，且列出工具名；
- `done.reply` 是权威值（"6 台。"），前端用它覆盖流式正文；
- `reasoningMs=1077` 为**最后一轮**（第 2 轮）「请求发出 → 首个正文增量」的耗时。

## 二、思考过程改为中文（AC-9，增量）

首轮冒烟发现 reasoning 是英文（`"The user asks how many devices..."`），加规则后复测：

> 用户：「现在有多少设备？只回答数量。」

```text
data:{"type":"reasoning","delta":"用户问设备数量。调用设备列表查询。"}
data:{"type":"status","text":"正在执行 1 个工具：[drone__device_list]"}
data:{"type":"content","delta":"6 台。"}
data:{"type":"done","reply":"6 台。","reasoningMs":727,"artifacts":[]}
```

- 思考共 17 字，其中 15 个汉字（其余为标点/数字），**不再出现英文整句**；
- 带 73 个工具定义时仍保持中文，未被英文工具名带偏；
- 落实方式：`prompts/assistant/system.md` v8 的「其他」节新增「思考过程也用中文」，
  所有路径（`/chat`、`/chat/stream`、工具续跑）共用；
- 直连模型做过对照：无指令 → 英文；加指令 → 中文；加指令 + tools → 中文。

## 三、工具链路与身份（本次修复点）

```text
【工具编排】第 1 轮：模型请求调用 1 个工具
【工具编排】第 1 轮：工具「drone__device_list」执行成功，结果已回填（265 字）
【工具编排】第 2 轮：模型没有再请求工具，直接作答，循环结束
```

- 工具经 `drone-mcp-server` 转发到 `drone-server`，需要**用户 JWT**；
- 修复前调用报「需要用户身份」：`streamAsk` 在异步流线程上读 `UserContext`，而
  `afterConcurrentHandlingStarted` 已把它清空；
- 修复后由 `ChatController.stream` 在**请求线程**构造 `ToolContext`（角色 / 权限 / bearerToken）
  并显式传入，工具执行成功。

## 四、非流式回归（/chat 未被破坏）

> 用户：「你好，一句话介绍你自己」

```json
{"state":200,"data":{"reply":"我是「无人机管理平台」内置的智能助手……","pending":null,"artifacts":[]}}
```

`/chat` 与 `/chat/confirm` 仍走原链路（确认流程本期不做流式）。

## 五、中断与客户端断开

- 客户端断开 → 监听回调返回 `false` → `ChatService.streamStep` 停止读取模型流；
- 已生成的部分正文**照常写回会话**（单测「客户端中途断开时，已生成的那部分仍记进会话」），
  避免「接着说」接不上。

## 六、未覆盖

- 未在浏览器里人工点击验收思考块的折叠/用时（需要 happy-dom 才能做组件测试）；
  面板逻辑通过 type-check、lint 与 `agent.spec.ts` 的事件分发用例覆盖。
- 思考过程**不入库、不回放**：刷新后消失，与既有消息行为一致（本期不做）。

## 七、测试

- `drone-agent`：**300 passed**（新增 `StreamAssemblerTest` 4 条、流式工具编排 2 条、
  中断保留部分回复 1 条）。
- `drone-web`：type-check、lint 通过；**24 passed**（`agent.spec.ts` 13 条，含 typed event、
  半包、多字节与错误分支）。
