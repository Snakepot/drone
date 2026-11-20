# 上下文质量评估集（Context Eval）

用手工运行的方式，拿一组真实问题量「上下文工程」是否有效，而不是只看单测。

## 会量什么

| 指标 | 说明 |
|---|---|
| 通过率 | 工具选择 + 回答断言都满足的用例比例 |
| 工具选择 | 是否调用了预期工具、是否调用了禁用工具（支持 `drone__*` 通配） |
| 回答断言 | 必须出现 / 禁止出现的文本（事实、拒答话术） |
| 延迟 | HTTP 端到端耗时（平均、P95） |
| 上下文 token | 最后一轮请求的 `prompt_tokens`（来自账单日志） |
| 缓存命中率 | 各轮 `prompt_cache_hit_tokens / prompt_tokens` 的平均值 |
| 长期记忆注入 | 账单里出现「长期记忆」的次数（REQ-0021 之后预期为 0） |
| 前缀指纹 | 同配置下应只有一个；出现多个说明前缀抖了 |

## 安全前提

- **只对着可丢弃的 agent 实例跑**：建议单独端口 + 独立 `AGENT_MEMORY_ROOT`；
- 脚本会真实调用模型并产生费用；
- 会向会话写入测试内容，请勿对生产实例运行；
- 使用 `--memory-root` 时会在该目录下写一个临时归档，跑完自动删除。

## 运行

```bash
# 1) 起一个隔离实例（示例：8083）
cd drone-agent
MQTT_ENABLED=false mvn spring-boot:run \
  -Dspring-boot.run.arguments="--server.port=8083 \
  --agent.kb.scan-on-startup=false \
  --agent.memory.root=./data/agent-memory-eval \
  --agent.artifact.root=./data/artifacts-eval" \
  > /tmp/agent_eval.log 2>&1

# 2) 跑评估（默认跳过日报等重用例）
python3 tools/context-eval/run_eval.py \
  --agent-url http://127.0.0.1:8083 \
  --server-url http://127.0.0.1:8080 \
  --username 13800001005 --password 123456 \
  --log /tmp/agent_eval.log \
  --memory-root ./drone-agent/data/agent-memory-eval --memory-user 5 \
  --out /tmp/context-eval-report.json

# 3) 需要日报等重用例时
python3 tools/context-eval/run_eval.py ... --include-heavy
```

`--log` 可选：不传时只能评估回答内容，工具与 token 指标会显示为「未取到」。
日志解析依赖以下中文标记，改日志措辞时需同步：

- `【工具编排】开始：traceId=…，conversationId=…`
- `要调用的工具是「…」…traceId=…`
- `【上下文账单】… 前缀指纹=…；… prompt_tokens=…，缓存命中=…`

## 用例字段

```json
{
  "id": "C01",
  "category": "设备",
  "question": "现在有多少设备？只回答数量。",
  "expect_tools_any": ["drone__device_list"],
  "expect_no_tools": false,
  "forbid_tools": ["drone__*"],
  "must_contain": ["台"],
  "must_not_contain": ["没有权限"],
  "requires": "memory",
  "heavy": false
}
```

- `expect_tools_any`：调用其中任意一个即算通过；
- `expect_no_tools`：一个工具都不应调用；
- `forbid_tools`：支持 `*` 通配；
- `requires: "memory"`：只有传了 `--memory-root` 才执行；
- `heavy: true`：只有传了 `--include-heavy` 才执行。

## 已知局限

- 断言是关键词级的，不等于语义正确；它用来防回归，不替代人工评审；
- 缓存命中率受供应商 TTL 影响，跨轮次才有参考价值；
- 只用 `admin` 账号时覆盖不到角色裁剪；需要时用 `--username` 换飞手/机务再跑一遍。
