# REQ-AGENT-0012 端到端回归记录

- **日期**：2026-10-21
- **环境**：本机；`drone-server`（8080，管理员已有数据）、`drone-mcp-server`（8082）、`drone-agent` 单测
- **说明**：`drone-agent` 的 8081 进程是开发中一直运行的旧实例，**没有动它**；
  另起了一套独立实例做全链路冒烟：`drone-mcp-server` 8082 + `drone-agent` 8083
  （`agent.kb.scan-on-startup=false`、独立记忆目录）。

## 一、上游不可达时仍能启动（AC-1）

```bash
mvn spring-boot:run -Dspring-boot.run.arguments="--drone.mcp-server.upstream-url=http://127.0.0.1:1 --drone.mcp-server.refresh.enabled=false"
curl 127.0.0.1:8082/health
```

结果：

```json
{"status":"UP","tools":70,"fingerprint":"b875af213eb8"}
```

`tools/list` 同样返回 70 个。**启动不依赖 drone-server**。

## 二、真实调用（AC-3 / AC-4 / AC-5 / AC-8）

| 场景 | 结果 |
|---|---|
| admin（13800001005）调 `task_list` | `isError=false`，返回任务分页数据（1350 字符） |
| pilot（13800001001）调 `zone_delete` | `isError=true`，文案「当前账号没有执行该操作的权限。」 |
| 不带 `Authorization` 调 `task_list` | `isError=true`，文案「当前请求缺少用户身份…」；**未发出上游请求** |

服务端日志（节选）：

```text
【MCP-Server】调用上游：tool=task_list，user=5(未验签)，GET /task/list
【MCP-Server】上游返回：tool=task_list，HTTP 200，耗时=64ms，isError=false，结果 1350 字符
【MCP-Server】上游 403：tool=zone_delete，耗时=10ms；当前账号无此权限
【MCP-Server】拒绝调用：tool=task_list，原因=缺少 Authorization；未向上游发送任何请求
```

对日志做 `grep "Bearer "`：**0 条**，未出现 Token。

## 三、完整自然语言链路（AC-3 / AC-6）

在 8083 上用 admin 账号走 `/chat`：

**1）查询类（成功）**

> 用户：「帮我看看最近的两条任务」
>
> 助手：返回两条执行中任务的详情（任务号、设备、航线、计划/实际时间、旁证状态）。

日志链路（同一 traceId，能对上）：

```text
【工具编排】开始：…可用工具 71 个 [search_knowledge、drone__zone_toggle、…]
【MCP】调用远程工具：tool=drone__task_list，server=drone，remote=task_list，线程=tool-invoke-2
【MCP】远程工具返回：tool=drone__task_list，耗时=26ms，结果 1350 字符
【MCP-Server】调用上游：tool=task_list，user=5(未验签)，GET /task/list
【MCP-Server】上游返回：tool=task_list，HTTP 200，耗时=19ms …
```

模型还先调了 `drone__user_me` 确认身份——符合「先查后写」的提示词约束。

**2）危险操作确认（暂停通过，续跑被既有缺陷阻断）**

> 用户：「删除 id 为 39 的禁飞区」→ 助手复述参数并请求确认
> 用户：「确认」→ 编排层暂停，生成 `pendingId=7de80e28…`，工具 `drone__zone_delete`

确认前**没有执行**任何写操作；随后用 `approve=false` 拒绝，并复查 `zone_detail(39)`：
记录仍在（数据未变）。

**当时 `approve=false` 的续跑返回 HTTP 500**（`reasoning_content` 未回传）。
该缺陷已于 **2026-10-22** 在 REQ-AGENT-0006 下修复：续跑改为原样回放模型原始的
assistant 帧（含原始 `tool_call_id` 与 `reasoning_content`），并复验通过。

## 四、agent 侧发现（AC-9）

临时联调用例（`DroneMcpLiveSmokeTest`，跑完即删）指向真实 8082：

```text
【MCP】发现完成：alias=drone，远程 70 个，按配置保留 70 个，耗时 244ms
【MCP】工具发现全部完成：注册 70 个远程工具 [drone__zone_toggle, …, drone__alert_list]
```

工具名全部带 `drone__` 前缀，含 `drone__task_list`、`drone__task_dispatch`。

## 五、未覆盖

- 危险工具的「批准/拒绝」分支已在修复后复验（2026-10-22）：均返回 200，无 400；
  拒绝后复查 `zone_detail(39)` 记录仍在。
- 后台刷新成功路径已触发（日志出现 `catalog-refresh` 线程重新生成 70 个），
  但**未构造「线上接口已变化」的场景**，差异提示未实测。
- 未在 8081 原实例上复验（未重启用户的进程）。
