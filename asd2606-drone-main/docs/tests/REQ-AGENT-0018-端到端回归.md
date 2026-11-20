# REQ-AGENT-0018 端到端回归记录：工具按角色与权限裁剪

- **日期**：2026-10-23
- **环境**：本机；`drone-server` 8080、`drone-mcp-server` 8082、`drone-agent` 8083（独立实例，
  跑完已清理）
- **账号**：admin `13800001005`、maintainer `13800001004`、pilot `13800001001`

## 一、入口权限（前置）

`/chat` 要求 `agent:chat`，原迁移只授予 admin。新增
`drone-server/docs/sql/migration/agent_permission_roles.sql`（幂等，排在
`agent_permission.sql` 之后）：

- 授予 `pilot` 与 `maintainer`；
- **`screen` 不授予**：大屏 token 存在开放房间的浏览器里，对话可能触及设备与任务细节，
  保持只读大屏的最小暴露面。

执行后校验：

```text
admin
maintainer
pilot
```

## 二、三角色真机对比（AC-1 / AC-2 / AC-3）

同一个问题：「现在有多少设备？只回答数量。」

| 角色 | 可见工具 | 工具定义 token | 实际 prompt_tokens | 相对 admin |
|---|---|---|---|---|
| admin | 73/73 | 10,516 | 12,428 | — |
| maintainer | 46/73 | 6,774 | 8,590 | **−30.9%** |
| pilot | 21/73 | 3,341 | 5,136 | **−58.7%** |

真实日志（pilot 第一轮）：

```text
【工具裁剪】traceId=dc14d592，可见 21/73：角色拦下 10、权限拦下 42；
隐藏=[drone__zone_*、drone__user_add/update/toggle、drone__task_create/dispatch/withdraw/refuse、
drone__system_*、drone__point_update/delete、drone__alert_*、drone__analytics_*、…（共 52 个）
【上下文账单】… 工具定义 3341×21；模型实际 prompt_tokens=5136，估算偏差 -0.7%
```

maintainer 第一轮：

```text
【工具裁剪】traceId=d98b765c，可见 46/73：角色拦下 10、权限拦下 17
【上下文账单】… 工具定义 6774×46；模型实际 prompt_tokens=8590，估算偏差 -0.3%
```

- pilot 仍能正常调用 `drone__device_list`（`工具结果分布 {drone__device_list=1}`），
  说明裁剪没有误伤其可用工具；
- admin 可见数不变，账单与改造前一致（偏差 −0.3%）；
- 未配置规则的地图、知识检索、Skill 元工具对所有角色可见（AC-4）。

## 三、估算修正（REQ-AGENT-0017 的系数）

裁剪后工具数变少，原来的「0.35/字符」估算偏差从 −2% 扩大到 **−9%**。
用两个真实请求联立反推，发现「带工具」本身有约 **450 token** 的固定模板开销，
且字符系数应为 **0.34**。修正后：

| 场景 | 修正前偏差 | 修正后偏差 |
|---|---|---|
| admin 73 工具 | −1.5% | −0.3% |
| pilot 21 工具 | −7.9% | −0.7% |
| maintainer 46 工具 | — | −0.3% |

## 四、未覆盖

- `screen` 未做真机对比（按上述取舍不授予 `agent:chat`）；
- 未做「按对话意图动态加载工具组」（如日报只给分析类工具），留作后续需求；
- 未改执行侧授权：`ToolGovernance.allows` 仍在每次执行前复核（既有用例保持通过）。

## 五、测试

- `drone-agent`：**324 passed**
  - `ToolVisibilityTest` 6 条：先匹配先用、任一权限、通配、fail-closed、坏配置启动失败；
  - `ToolVisibilityConfigTest` 4 条：直接解析仓库真实 `application.yml`，按 pilot / screen /
    maintainer 权限断言可见性（防「配置改坏了测试还绿」）；
  - `ToolCallingServiceTest` 2 条：权限不足不下发、危险工具对非授权角色不可见；
  - `TokenEstimatorTest` 更新工具 JSON 系数与固定开销断言。
