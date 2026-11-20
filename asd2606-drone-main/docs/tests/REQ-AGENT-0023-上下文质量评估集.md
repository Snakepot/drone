# REQ-AGENT-0023 端到端回归记录：上下文质量评估集

- **日期**：2026-10-23
- **环境**：本机；`drone-server` 8080、`drone-mcp-server` 8082、
  `drone-agent` 8083（**隔离实例**，memory root `./data/agent-memory-eval`，跑完已清理）
- **账号**：admin `13800001005`
- **命令**：

```bash
python3 tools/context-eval/run_eval.py \
  --agent-url http://127.0.0.1:8083 \
  --server-url http://127.0.0.1:8080 \
  --username 13800001005 --password 123456 \
  --log /tmp/agent_eval.log \
  --memory-root ./drone-agent/data/agent-memory-eval --memory-user 5 \
  --out /tmp/context-eval-report.json
```

## 一、结果（AC-1 / AC-2 / AC-3 / AC-7）

```text
ID    类别     结果    工具                                延迟ms   上下文tok   命中%
C01   设备     通过    drone__device_list                   2956     12909     98.4
C02   设备     通过    drone__device_list                   2911     12897     98.4
C03   任务     通过    drone__task_list                     9100     13361     97.2
C04   告警     通过    drone__alert_list,drone__task_detail,use_skill
                                                          26017     15822     94.3
C05   统计     通过    dashboard_summary, analytics_overview/dispatch_block,
                        task_list/detail, alert_list, use_skill, read_skill_resource
                                                          48691     22424     90.3
C06   统计     通过    analytics_dispatch_block 等 5 个     17921     16547     95.5
C07   知识     通过    search_knowledge                     5318     13215     97.2
C08   拒答     通过    -                                     940     12718     98.6
C09   记忆     通过    search_memory                        1926     12860     98.5
C10   -        跳过    需要 --include-heavy
```

汇总：

```text
执行 9，通过 9（100.0%）
延迟：平均 12864 ms，P95 48691 ms
最终上下文：平均 14750 token
累计 prompt：平均 39990 token/用例
缓存命中率：平均 96.5%
前缀指纹：1 个 ['4b41a4976a6c']
无条件注入长期记忆：0 次（预期 0）
```

## 二、结论

- **工具选择全部命中**：设备、任务、告警、统计、知识、记忆各走各的工具；
  拒答用例一个工具都没调；
- **记忆按需检索生效**：C09 调用了 `search_memory` 并答出「张伟」，夹具在跑完后自动删除；
- **上下文稳定**：9 个用例（同一用户）只有 1 个前缀指纹，命中率平均 96.5%；
- **停注入生效**：账单里「长期记忆」出现 0 次；
- **成本信号**：C05 用了 8 次工具、48.7s、22.4k token —— 长流程（统计总览 + 技能）
  是当前最贵的一条，后续可用「按意图收窄工具组」或子代理优化。

## 三、未覆盖

- 未跑 `--include-heavy`（日报），避免一次评估消耗过多 token；
- 未覆盖流式接口、多轮对话、危险操作确认；
- 断言是关键词级，不能替代人工评审；
- 未用 pilot / maintainer 账号跑（需另跑一遍验证工具裁剪）。

## 四、使用注意

- 评估会真实调用模型并写入会话，**只对着隔离实例运行**；
- 日志解析依赖中文标记，改日志措辞需同步脚本正则；
- 结果 JSON 在 `/tmp/context-eval-report.json`，可留档对比。
