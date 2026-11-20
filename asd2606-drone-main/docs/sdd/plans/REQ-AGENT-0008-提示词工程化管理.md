# 技术方案 — REQ-AGENT-0008 提示词工程化管理（架子）

> 输入是 [`specs/REQ-AGENT-0008-提示词工程化管理.md`](../specs/REQ-AGENT-0008-提示词工程化管理.md)。

- **需求 ID**：`REQ-AGENT-0008`
- **作者 / 日期**：开发 / 2026-10-16
- **评审人 / 评审日期**：待评审

## 1. 方案概述

给提示词一个**自己的家**：新建 `drone-agent/prompts/` 目录，**一条提示词一个 `.md`**；
每条 md 用 **YAML frontmatter** 携带元信息（名字 / 版本 / 用途 / 变量 / 变更记录），
frontmatter 之后就是**正文**（真正发给模型的文本）。

启动时由一个 **`PromptCatalog`** 把目录里的 md 全部读进来、解析、**校验**（空正文、名字不符、重名、
`vars` 与正文占位符对不上 —— 任一不合格就**启动失败**），并**打印「加载了哪些提示词、各自版本」**。
代码侧只按名字取用（`system()`、`render("summary", vars)`），**不再出现提示词正文**。

## 2. 影响面

| 维度 | 影响 | 说明 |
|---|---|---|
| 接口 | **无变更** | `/chat`、`/chat/confirm` 契约不动 |
| 数据模型 | **无** | 不落库；提示词是随 jar 打包的**资源文件** |
| 构建 | **无** | 提示词在 `src/main/resources/` 下，**随默认资源打包**，不用改 `pom.xml` |
| 配置 | **减少** | `application.yml` 里的 `agent.deepseek.system-prompt` **删掉** |
| 兼容性 | **兼容** | 对外行为不变（内容原样迁移）；改措辞从此只改 md |
| 性能 | **无运行时开销** | 校验都在启动期；运行时只是按名字取字符串 + 替换占位符 |

## 3. 接口清单

| 方法 | 路径 | 入参 | 出参 | 鉴权 | 幂等 |
|---|---|---|---|---|---|
| POST | `/chat` | 不变 | 不变 | `agent:chat` | 否 |

> **无新增接口**。

## 4. 数据模型变更

- 变更内容：**无**（表结构、记忆格式都不动）
- 迁移脚本：无
- 回滚方案：**无数据需回滚**；提示词文件回退即可
- 是否需要停机 / 执行窗口：需要一次**重启**

## 5. 关键设计决策

| 决策点 | 备选方案 | 结论 | 理由 |
|---|---|---|---|
| 目录位置 | ①**`src/main/resources/prompts/`** ②`drone-agent/prompts/` + pom 映射 | **①** | 不用改 `pom.xml`；resources 本来就随 jar 打包，部署自包含 |
| 目录组织 | ①全部平铺在 `prompts/` 下 ②**按业务分子目录** | **②** | 提示词会越加越多；按业务（`assistant/`、`tool/`…）分层，**取用名 = 相对路径**（`assistant.system`），一眼知道归属与用途 |
| 提示词名 | ①只用 frontmatter 里的 `name` ②**`name` 必须等于文件相对路径** | **②** | 免得「文件挪了位置、名字没改」这类不一致；启动期校验，对不上就起不来 |
| 文件格式 | ①`.yml` 节点 ②**一条一个 `.md`（frontmatter + 正文）** | **②** | md 是提示词的**自然载体**；正文不被 YAML 转义规则干扰（多行、含 `{}`、含 `#` 都不怕） |
| frontmatter 解析 | ①引 YAML 库（Jackson-YAML / 显式声明 snakeyaml） ②**手写轻量解析** | **②** | Jackson **本身不认 YAML**；为一段 frontmatter 引依赖不划算。只支持三种写法（`key: value`、`key:` + `- 项`、`[a, b]`），够用且能报出精确的行号与原因 |
| 占位符 | ①引模板引擎（Thymeleaf/Mustache 等） ②**`{{变量}}` 正则 + `render()`** | **②** | 只需要「填值」；模板引擎的能力（循环/条件）提示词用不上，反而增加心智负担 |
| 校验时机 | ①运行时遇到才报错 ②**启动期 fail fast** | **②** | 与工具元数据（`ToolRegistry`）一致：写坏了就起不来，别等用户提问才发现 |
| 版本管理 | ①只靠 git ②**frontmatter `version` + `changelog`，并在启动日志打 `name@version`** | **②** | git 是文件级历史；提示词还需要**自己**的版本号，才能在「换了提示词后行为变了」时一句话对上（AC-6） |
| 日志 | ①把提示词打进日志便于排查 ②**记「加载过程」，但绝不打正文** | **②** | AC-6 要的是**过程可见**（开始扫哪、扫到哪些文件、各加载成哪条、什么版本、要哪些变量），排障时能一句话对上「现在用的是哪版」；正文含业务口径，属**不该外泄**的内容（AC-7） |

### 5.1 目录与文件长什么样

```
drone-agent/src/main/resources/prompts/
├── assistant/            # 业务：助手对话行为
│   └── system.md         #   名字 = assistant.system
└── tool/                 # 业务：工具链路
    └── summary.md        #   名字 = tool.summary
```

`assistant/system.md`（前两块是 frontmatter，之后到文件末尾就是**正文**）：
```md
---
name: assistant.system
version: 1
purpose: 助手的人设、业务范围、工具用法与拒答口径
vars: []
changelog:
  - v1 2026-10-16 从 application.yml 的 agent.deepseek.system-prompt 原样迁入
---

你是「无人机管理平台」内置的智能助手，服务对象是平台的管理员、机务和飞手。
（……正文原样，见「10. 迁移内容」）
```

`tool/summary.md`：
```md
---
name: tool.summary
version: 1
purpose: 把工具结果压到指定字数以内（模型摘要）
vars: [maxChars]
changelog:
  - v1 2026-10-16 从 ChatService.summarize 的 Java 字符串迁入；maxChars 由拼接改为占位符
---

你是文本压缩助手。把用户给的内容压缩到 {{maxChars}} 字以内，只保留关键信息与数值，不要编造，也不要额外解释。
```

### 5.2 取用方式（代码侧）

```java
promptCatalog.text("assistant.system")                        // 系统提示词正文
promptCatalog.render("tool.summary", Map.of("maxChars", n))   // 填占位符；缺变量直接抛错
```

> 取用一律按**名字**（= 相对路径）；所以**加一条提示词 = 加一个 md 文件**，代码不用动。

## 6. 测试策略

| 验收条件 | 测试层级 | 测试位置 |
|---|---|---|
| AC-1 | 人工/grep | `plans/` 记录的检索命令（`grep` 提示词特征串） |
| AC-2 | 单元 | `PromptCatalogTest`（空正文 / 缺 name / 缺 version / name 与文件名不符 / 重名 → 抛错） |
| AC-3 | 单元 | `PromptCatalogTest`（`vars` 与正文占位符不一致 → 抛错） |
| AC-4 | 单元 | `PromptCatalogTest`（`render` 填值；缺变量抛错） |
| AC-5 | 单元 + 人工 | `PromptCatalogTest`（改内容即生效——从资源加载，非缓存硬编码） |
| AC-6 | 单元 | `PromptCatalogTest`（能取到 name + version） |
| AC-7 | 单元 | 断言日志/异常里不含正文（沿用脱敏断言的做法） |
| AC-8 | **人工回归** | 按第 6.1 的清单做对话回放 |

### 6.1 行为回归清单（AC-8）

> 提示词内容本轮不改，所以这几条只用来**确认搬家没搬歪**：

1. 问「帮我写篇年终总结」→ 婉拒，且**不解释**「年终总结是什么」；
2. 问「北京明天能飞吗」→ **先调用天气工具**再作答；
3. 工具查不到 → 如实说查不到，**不出现编造的数值**；
4. 工具结果里是「用户拒绝了这次操作」→ 确认已取消，**不反复劝**。

## 7. 风险与回滚

| 风险 | 触发条件 | 影响 | 应对 |
|---|---|---|---|
| jar 里读不到 prompts | pom `<resources>` 配错 | **启动失败**（读不到就报错，不会静默用空提示词） | 加一个「上下文能起来 + 能取到 system」的测试兜住 |
| frontmatter 写歪 | 手工编辑出错 | 启动失败 | 这是**设计如此**：错误信息指明哪条、缺什么 |
| 正文被 frontmatter 的 `---` 截断 | 正文里出现独占一行的 `---` | 正文被截短 | 解析规则写明「只认**开头**的 frontmatter」，并在文档里提示正文别以 `---` 开头 |
| 版本号忘了改 | 改了正文没动 `version` | 日志对不上实际内容 | 在 `changelog` 里人工维护；`plans`/提交信息要求同步（不做自动校验，避免僵化） |

- 上线方式：**改造 + 重启**
- 回滚步骤：回退该次提交；提示词正文没有语义变化，**无数据需修复**
- 回滚后数据是否需要修复：**不需要**

## 8. 与项目宪法的一致性

- **第 1 节 技术栈**：不引入新依赖（Jackson 已在用；frontmatter 手写解析）。**一致**。
- **第 2 节 结构/依赖方向**：新代码在 `drone-agent` 的 `prompt/`、`config/`；prompts 是资源。**一致**。
- **第 3 节 接口契约**：对外接口不变。**一致**。
- **第 4 节 安全红线**：提示词正文不进日志（AC-7）；无新增密钥面。**一致**。
- **第 5 节 测试门槛**：AC-2~AC-7 均有单测；AC-8 为人工回归（已在 `plans/` 写明原因）。**一致**。
- **第 6 节 数据与迁移**：无表结构改动。**一致**。
- **第 7 节 交付/门禁**：提交信息引用 `REQ-AGENT-0008`。**一致**。

## 9. 需求 ID → 代码位置 → 测试位置（映射表）

| 需求 ID | 代码位置（预期） | 测试位置（预期） |
|---|---|---|
| REQ-AGENT-0008 | `resources/prompts/assistant/system.md`、`resources/prompts/tool/summary.md`、`prompt/PromptCatalog.java`、`prompt/PromptDefinition.java`、`config/PromptConfig.java`、`service/ChatService.java`（改）、`config/DeepSeekProperties.java`（去掉 systemPrompt）、`resources/application.yml`（去掉 system-prompt） | `prompt/PromptCatalogTest`、`service/ChatServiceTest`（改） |

## 10. 迁移内容（正文原样）

- **`prompts/assistant/system.md` 正文** = 现 `application.yml` 里 `agent.deepseek.system-prompt` 的全部内容，**一字不改**；
- **`prompts/tool/summary.md` 正文** = 现 `ChatService.summarize` 里那句的原文，仅把拼接的 `maxChars` 换成 `{{maxChars}}`。

> 措辞优化**不在本需求**（Q-2），另立需求；本需求只保证「搬家不搬歪」。
