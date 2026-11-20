# REQ-AGENT-0030 端到端回归记录：请求限流

- **日期**：2026-10-26
- **环境**：本机；`drone-server` 8080、`drone-mcp-server` 8082、
  `drone-agent` 8083（隔离实例，跑完已清理）
- **账号**：admin `13800001005`

## 一、用户并发（AC-2）

启动参数：`user-concurrency=1`，同时发两个 `/chat`：

```text
第二个：{"state":429,"message":"你已有请求正在处理，请等其中一个完成后再试。","data":null}
第一个：{"state":200,"data":{"reply":"你好！我是无人机管理平台的智能助手…"}}
```

```text
【限流】拒绝请求：原因=USER_CONCURRENCY
```

- 被拒请求**没有调用模型**、没有写会话；
- 第一个请求正常完成；
- 返回统一响应体，`state=429`（HTTP 200）。

## 二、QPS / 全局（AC-1 / AC-3 / AC-5）

- QPS 令牌桶由单测覆盖：同刻第二次拒绝，时钟推进 1 秒后恢复；
- 全局并发跨用户共享上限，单测覆盖；
- 关闭开关时永不拒绝。

## 三、流式（AC-4）

限流检查在创建 emitter 之前：超限时发一条 `error` 事件并结束，不进入编排。
（本轮未单独跑流式超限；代码路径与 `/chat` 共用同一个 `tryAcquire`。）

## 四、释放（AC-7）

- `/chat`：`finally` 中释放；
- `/chat/stream`：`onCompletion` / `onError` / `onTimeout` 三处释放；
- `Permit.close()` 用 CAS 保证幂等，单测覆盖「重复 close 后仍可再次获取」。

## 五、测试

- `drone-agent`：**384 passed**
  - `RateLimiterTest` 4 条：QPS 恢复、用户并发、全局并发、关闭开关；
  - 既有 380 条全部保持通过。
- 配置默认值：用户 2 QPS / burst 4 / 并发 2；全局 20 / 40 / 20。

## 六、未覆盖

- 未做分布式限流（单实例内存）；
- 未做按接口/工具细分；
- 未做排队（刻意直接拒绝，避免堆积）。
