# 代码评审报告 — DeepSeekProperties

> **评审对象**：`drone-agent/src/main/java/cn/tedu/droneagent/config/DeepSeekProperties.java`
> **评审范围**：1 个文件（存量代码评审，被审对象**无改动**）
> **评审人 / 日期**：AI / 2026-10-20
> **规则依据**：`AGENTS.md` · `docs/sdd/constitution.md` · `docs/sdd/java-standards.md` · `docs/sdd/frontend-standards.md`
> **结论**：**需修改**（1 条 MAJOR + 1 条 MINOR，改动都很小）

## 1. 摘要

参数本身与 yml 绑定、`apiKey` 走环境变量这两点是对的；系统提示词不在本类也标注清楚。
但有**一条安全红线**：`record` 自动生成的 `toString()` 会把 `apiKey` **明文**拼进去——
当前代码没有任何地方打印它（已 grep 确认），属**潜在**泄露，而不是已发生的事故。
另外，同目录另外三个配置类都有「绑定测试」，唯独它没有。

| 级别 | 数量 | 含义 |
|---|---|---|
| BLOCKER | 0 | 安全 / 数据 / 编译 |
| MAJOR | 1 | 规则或契约违规、AC 缺测试 |
| MINOR | 1 | 低影响偏离 |
| NIT | 0 | 纯风格，可选 |

## 2. 发现

| ID | 级别 | 位置 | 问题 | 规则依据 | 建议改法 | 状态 |
|---|---|---|---|---|---|---|
| CR-001 | MAJOR | `DeepSeekProperties.java:18-24` | `record` 自动生成的 `toString()` 含 `apiKey` 明文。任何 `log.info("{}", props)`、调试器或配置转储都会把密钥打到日志里 | `java-standards` §5【强制】禁止打印敏感信息：…**任何密钥** | 覆写 `toString()`，把 key 掩码（如 `apiKey=***`）；或不让 key 作为 record 组件 | 待修 |
| CR-002 | MINOR | `src/test/java/cn/tedu/droneagent/config/` | 缺少 `agent.deepseek.*` 的**绑定测试**；同目录的 `ToolPropertiesTest` / `MemoryPropertiesTest` / `KnowledgePropertiesTest` 都有，唯独它没有。yml 键名拼错时绑定会**悄悄失效**（`model` 变 `null` → 调用时 400） | `java-standards` §9【强制】（关键路径要有测试）+ 仓库内同类配置的一致性 | 加一个 `DeepSeekPropertiesTest`：断言 yml 默认值能绑上、`configured()` 在无 key 时为 false | 待修 |

> CR-001 的补充事实：本次 grep 过 `src/main/java`，**当前没有任何地方整体打印 `props`**
> （`ApiEmbeddingModel` 只打 `props.model()`），所以是**潜在**风险。

## 3. 建议（无对应【强制】，不阻塞）

| ID | 位置 | 建议 | 理由 |
|---|---|---|---|
| SG-001 | `DeepSeekProperties.java:26-28` | `configured()` 同时校验 `baseUrl` 与 `model` | 现在只要 key 非空就算「已配置」；`model` 为空时请求体是 `"model": null`，供应商回 400，报错信息对新人很难懂。要么扩校验，要么启动期 fail-fast |
| SG-002 | `DeepSeekProperties.java:19` | 在 compact 构造器里去掉 `baseUrl` 末尾的 `/` | `ChatService` 里有 **4 处** `props.baseUrl() + "/chat/completions"`；写了结尾斜杠会变成 `//chat/completions`，部分网关会拒 |
| SG-003 | `DeepSeekProperties.java:22` | 明确 `reasoningEffort` 的允许取值（注释或校验） | 它是自由字符串，写错会原样透传给供应商，表现为 400，而不是启动报错 |
| SG-004 | `DeepSeekProperties.java:22-23` | 注释说明 `thinkingEnabled=false` 时 `reasoningEffort` 是否仍有意义 | 两个参数语义相关；不写清楚，后来人会以为「设了 effort 就等于开了思考」 |

## 4. 验证记录

| 命令 | 结果 | 说明 |
|---|---|---|
| `cd drone-agent && mvn -q compile` | **成功**（无输出） | 编译通过 |
| `mvn test -Dtest=DroneAgentApplicationTests` | **Tests run: 1, Failures: 0, Errors: 0** | 完整上下文能起，`agent.deepseek.*` 能绑定到该 record |
| `mvn test`（全量） | **未执行** | 本次评审无改动；全量结果属历史数据，不计入本次结论 |
| `grep -rn "props" src/main/java \| grep log` | 仅 `props.model()` | 确认当前**没有**整体打印配置对象的日志点 |

## 5. 未覆盖与风险

- **未覆盖**：`ChatService` 里 4 处 URL 拼接与请求体构造（本次只核对了拼接方式，未逐行评审该类）。
- **需人工确认**：无。
- **已知遗留**：CR-001 属**潜在**泄露——只要将来有人整对象打日志就会触发；建议在「本轮一起修」。

## 6. 变更清单

```
（无改动：评审时 git status 为空）
```

## 7. 下一步

- [ ] 修 **CR-001**（覆写 `toString` 掩码，约 5 行）
- [ ] 补 **CR-002**（`DeepSeekPropertiesTest`，约 20 行）
- [ ] **SG-001** / **SG-002** 建议一起做（各 1~3 行）

---

> 本报告是**记录**，不是营销材料：不写「亮点」，不夸大；没验证的就写没验证。
> 报告与代码同仓库，随代码一起评审与回滚。
