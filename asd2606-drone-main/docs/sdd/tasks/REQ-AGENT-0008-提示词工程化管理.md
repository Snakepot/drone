# 任务拆解 — REQ-AGENT-0008 提示词工程化管理（架子）

> 输入是 [`plans/REQ-AGENT-0008-提示词工程化管理.md`](../plans/REQ-AGENT-0008-提示词工程化管理.md)。
> 【强制】一条任务必须能在**一次提交**内完成，且有明确完成标志。

- **需求 ID**：`REQ-AGENT-0008`
- **负责人 / 日期**：开发 / 2026-10-16

## 任务表

| 编号 | 任务 | 涉及模块/层 | 产出 | 完成标志 | 依赖 | 状态 |
|---|---|---|---|---|---|---|
| T-1 | 立规范三件套 | `docs/sdd/**` | `specs/`、`plans/`、`tasks/` | 评审通过 | - | 待评审 |
| T-2 | prompts 目录 + 加载与校验 | `resources/prompts/`、`prompt/` | `PromptDefinition`、`PromptCatalog`（**递归**扫 `classpath*:prompts/**/*.md`、解析 frontmatter、校验「非空 / 缺字段 / name=相对路径 / 重名 / vars=占位符」） | `PromptCatalogTest` 通过（AC-2/AC-3/AC-4/AC-5/AC-6） | T-1 | 已完成（2026-10-16，`PromptCatalogTest` 9 例全绿；**Bean 装配留到 T-3**，否则「目录为空」会让上下文起不来） |
| T-3 | 系统提示词迁入 | `resources/prompts/assistant/system.md`、`config/DeepSeekProperties.java`、`config/PromptConfig.java`、`resources/application.yml` | `assistant/system.md`（正文原样）；`DeepSeekProperties` 去掉 `systemPrompt`；yml 删 `system-prompt`；装配 `PromptCatalog` | `ChatServiceTest` 与上下文加载用例全绿；AC-1 检索命令无命中 | T-2 | 已完成（2026-10-16，全量 `mvn test` → 167 passed；启动日志 `已加载提示词 1 条：assistant.system@1`；正文只在 `prompts/` 下） |
| T-4 | 摘要提示词迁入 + 占位符 | `resources/prompts/tool/summary.md`、`service/ChatService.java` | `tool/summary.md`（`{{maxChars}}`）；`ChatService.summarize` 改用 `render("tool.summary", …)` | 摘要路径单测通过；缺变量时报错 | T-2, T-3 | 已完成（2026-10-16，`tool/summary.md` 建好、正文原样仅换占位符；`ChatService.summarize` 改走 `promptCatalog.render("tool.summary", …)`；新增 `ChatServiceTest.summarizeTakesPromptFromCatalog` 证明取自目录；AC-1 检索命令仅命中 `prompts/`；全量 `mvn test` → 172 passed） |
| T-5 | 版本与可观测 | `prompt/PromptCatalog.java` | 启动日志**分步展示加载过程**：开始 → 扫到哪些文件 → 逐条 `路径 → name@version（vars）` → 完成；**不含正文** | 启动日志能看到完整过程；grep 特征串确认无正文（AC-6/AC-7） | T-2 | 已完成（2026-10-16，启动日志逐条打印加载过程） |
| T-6 | 文档与行为回归 | `drone-agent/docs/**` | 部署说明补「prompts 目录 / 新增一条提示词的步骤 / frontmatter 字段说明」；按 `plans` §6.1 做人工回归 | 文档与实现一致；4 条回归清单逐条记结果 | T-4, T-5 | 已完成（2026-10-16，部署说明补 §5.7.1 目录 / §5.7.2 新增步骤 / §5.7.3 frontmatter 字段表；4 条回归 C1/C2/C3 通过、C4 受阻——见下「回归记录」） |

**完成标志怎么写**：写成可验证的结果，例如「接口 X 返回 Y，且测试 Z 通过」；不要写「完成开发」「处理好」。

## 验收条件覆盖

| 验收条件 | 覆盖任务 |
|---|---|
| AC-1 | T-3 |
| AC-2 | T-2 |
| AC-3 | T-2 |
| AC-4 | T-2, T-4 |
| AC-5 | T-2 |
| AC-6 | T-5 |
| AC-7 | T-5 |
| AC-8 | T-6（人工回归，清单见 `plans` §6.1） |

> 【强制】规格里的每条 AC 都要落到至少一个任务上，漏掉的必须在备注里说明原因。

## 执行规则（AI 与人都适用）

- 【强制】一次只做一条任务，做完一条再开下一条。
- 【强制】每条任务完成后更新「状态」列，并给出可验证的证据（测试结果、命令输出）。
- 【强制】执行中发现规格或方案有问题，**停止当前任务**，先回 `specs/` 或 `plans/` 改并评审。
- 【强制】不得擅自扩大范围；发现「顺手也该改」的地方，记下来作为新需求提，不要夹带。

### 备注：迁移会波及的既有代码（属本需求内既定改动）

- `DeepSeekProperties` 去掉 `systemPrompt` 后，所有构造它的测试都要跟着改
  （`ChatServiceTest`、`ChatServiceToolTest`、`ToolCallingServiceTest`、`ConversationServiceTest`、
  `ChatConfirmServiceTest`）——**不算夹带**。
- `ChatService` 构造方法要加 `PromptCatalog`；同上，测试一起改。
- **措辞优化不在本需求**（Q-2）：`system.md` / `summary.md` 的正文**原样迁移**，
  改一个字都属于另一个需求。

### 回归记录（AC-8，`plans` §6.1）

> 用一次性探针**直接调真模型**（走 `prompt/` 目录加载的提示词，不经 HTTP 层、不碰 8080/8081），
> 跑完即删。`deepseek-flash` + thinking。结果如下：

| # | 条件 | 结果 | 实测回答（节选） |
|---|---|---|---|
| C1 | 问「帮我写篇年终总结」→ 婉拒且**不解释** | ✅ 通过 | 「抱歉，这个我帮不上忙。我主要负责无人机业务相关的问题，设备、任务、航线、告警这些都可以问我。」 |
| C2 | 问「北京明天能飞吗」→ **先调用天气工具**再作答 | ✅ 通过（日志：工具被调 1 次） | 先引用「当前实况」要素，再说明拿不到明天的预报，给起飞前确认项 |
| C3 | 工具查不到 → 如实说查不到，**不编造数值** | ✅ 通过 | 「天气服务现在连不上（查询超时）……我不能凭空给你结论。」无任何编造数值 |
| C4 | 工具结果「用户拒绝了这次操作」→ 确认已取消、**不反复劝** | ⛔ 受阻（非本需求缺陷，见下） | 请求直接 `HTTP 400`，模型根本没被问上 |

**C4 受阻的根因（属 `REQ-AGENT-0006`，不在本需求内修复）**：

- 现象：危险工具**二次确认的续跑**（`ToolCallingService.resume`，批准与拒绝**都一样**）
  对该网关**必然 `HTTP 400`**：`The reasoning_content in the thinking mode must be passed back to the API`。
- 定位（curl 单变量隔离，基于一次成功的工具回填请求）：
  - 复用**模型当初签发的** `tool_call_id` → `200`；
  - 把该 id **改任意一个字符**、或换成自造的 `confirm_<uuid>` → **一律 400**。
  - 与工具名、工具结果内容**无关**。
- 根因：`resume` 里 `new ToolCall("confirm_" + action.id(), …)` **自造了一个新 id**；
  而 `pause` 存的快照**不含**「模型要调工具」那一帧、也就**丢掉了原 id**。
  该网关按 `tool_call_id` 关联 reasoning，id 对不上即拒收。
- 影响：危险工具的「确认 / 拒绝」两条路径**实际不可用**（此前单测用脚本替身、未过真模型，故未暴露）。
- 建议（另立需求走 SDD）：`pause` 记下模型签发的 `tool_call_id`，`resume` **原样复用**它，
  而不是自造 `confirm_*`。
