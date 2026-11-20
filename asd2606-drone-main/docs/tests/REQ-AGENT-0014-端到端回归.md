# REQ-AGENT-0014 端到端回归记录

- **日期**：2026-10-22
- **环境**：本机；`drone-server`（8080）、`drone-mcp-server`（8082）、`drone-agent`（8083 独立实例，
  不动用户的 8081）
- **账号**：admin `13800001005`

## 一、加载与校验（AC-1 / AC-7 / AC-8 / AC-9）

```text
【Skill】加载完成：4 个（[alert-triage, daily-operations-report, dispatch-readiness, shift-handover]）
【Skill】工具引用校验完成：4 个流程
```

模板目录 `_template` 未参与加载；本地工具引用校验通过。

## 二、按权限裁剪（AC-2）

admin 的系统提示词里 L1 为 4 行（名称 + 描述 + 适用场景），正文未进入上下文；
只具备 `task:execute` 的用户只看到 `shift-handover`（由 `ChatServiceSkillTest` 钉住）。

> 说明：真机上**没能用飞手账号验证**——`/chat` 要求 `agent:chat`，初始数据里只授予了 admin。
> 要给飞手用交接班流程，需先给 `pilot` 角色授予 `agent:chat`（见 `migration/agent_permission.sql`）。

## 三、当日运营日报（AC-6）

> 用户：「出一下今天的运营日报」

结果：`state=200`，按固定版式输出「核心指标 / 任务 / 人员 / 设备 / 告警 / 风险与建议」六段，
数字来自真实接口，且给出三条可执行建议（例如：张伟同时挂着 3 条执行中任务；
UAV-001 有 3 条未闭环告警；在线率 0% 与「4 台飞行中」矛盾，需核查遥测）。

调用链（节选）：

```text
【Skill】加载流程：name=daily-operations-report，正文 2015 字符，参考资料 1 份
【工具编排】第 2 轮：工具「drone__dashboard_summary」执行成功
【工具编排】第 3 轮：drone__analytics_overview / drone__analytics_dispatch_block 并发执行
【工具编排】第 4 轮：drone__task_list / drone__alert_list
【工具编排】第 5 轮：模型直接作答
```

### 冒烟中发现并修复的问题

第一次跑只到第 3 轮就收回了工具（`max-rounds: 3`），模型把没执行完的调用写成了
**伪工具文本（DSML）** 返回给用户。修复：

1. `agent.tool.max-rounds` 由 3 提到 **6**（流程要串行查 4–6 个接口）；
2. 提示词补充「互不依赖的查询可同轮并发」「不要在正文输出工具调用标记」「工具被收回时直接作答」；
3. 日报正文注明第 2/3 步、第 4/5 步可并发。

修复后同一问题返回 200，正文无任何标记。

## 四、未覆盖

- **派发前检查 / 告警分诊**：会写入业务数据，未做真机演练（步骤与工具名由单测与文档保证）。
- **L3 资料读取**：`read_skill_resource` 由单测覆盖（含路径穿越与扩展名白名单），未在真机触发。
- **飞手侧可见性**：受 `agent:chat` 权限限制，未能用真实飞手账号验证。

## 五、测试

`mvn test` → **275 passed**（新增 17 条：目录解析、权限过滤、工具安全、工具引用校验、提示词渲染）。
