# 任务拆解 — REQ-AGENT-0010 内部资料检索（RAG）

> 输入是 [`plans/REQ-AGENT-0010-内部资料检索.md`](../plans/REQ-AGENT-0010-内部资料检索.md)。
> 【强制】一条任务必须能在**一次提交**内完成，且有明确完成标志。

- **需求 ID**：`REQ-AGENT-0010`
- **负责人 / 日期**：开发 / 2026-10-16

> 【前置】**S2 已澄清（2026-10-16）**：Q-1 ~ Q-4 已清空，可开工。

## 任务表

| 编号 | 任务 | 涉及模块/层 | 产出 | 完成标志 | 依赖 | 状态 |
|---|---|---|---|---|---|---|
| T-1 | 立规范三件套 + ADR | `docs/sdd/**` | `specs/`、`plans/`、`tasks/`、`decisions/ADR-0006` | 评审通过 | - | 已完成（2026-10-16，评审通过；S2 已澄清、待定项清空） |
| T-2 | 知识库配置与目录 | `config/KnowledgeProperties.java`、`resources/application.yml` | `agent.kb.*`（开关 / 目录 / top-k / 阈值 / 切块 / embedding / milvus）；`data/knowledge/` 由运行时按需创建（`data/` 已被 gitignore） | `KnowledgePropertiesTest` 通过；全量 `mvn test` → 174 passed | T-1 | 已完成（2026-10-16；**「开关关时不装配检索工具」顺延到 T-8 验证**——工具此刻还不存在，见备注） |
| T-3 | 解析与切块 | `kb/DocumentSection.java`、`kb/Chunk.java`、`kb/DocumentParser.java`、`kb/MarkdownDocumentParser.java`、`kb/Chunker.java` | Markdown 按标题层级解析（含引用路径、代码围栏内的 `#` 不算标题）；超长按**行边界**切、相邻**重叠** | `MarkdownDocumentParserTest`（4 例）、`ChunkerTest`（7 例）通过；全量 `mvn test` → **185 passed** | T-1 | 已完成（2026-10-16；AC-6 的一半——「结构与出处解析正确」） |
| T-4 | **向量存储接口 + 内存实现**（基础设施） | `vector/VectorRecord.java`、`vector/ScoredRecord.java`、`vector/VectorFilter.java`、`vector/VectorStore.java`、`vector/InMemoryVectorStore.java` | 接口（写入 / 检索 / 按条件删除）+ **结构化过滤条件**（内存求值、真库可翻译）+ 内存实现（**维度对齐真库**、余弦排序、可重复） | `InMemoryVectorStoreTest`（10 例）通过；全量 `mvn test` → **195 passed**（AC-8 成立） | T-1 | 已完成（2026-10-16；AC-8 由「单测不连真库」保证，AC-3 的过滤语义在此验证） |
| T-4b | **Milvus 适配**（基础设施） | `vector/FieldDef.java`、`vector/MilvusVectorStore.java`、`vector/MilvusVectorStoreIT.java` | 走 **REST**（`/v2/vectordb/**`，与 gRPC 共用 19530）实现写入 / 检索 / 删除；**幂等建集合**（不存在则建 + COSINE 索引 + load，存在则校验维度与字段）；**过滤条件翻译**成 Milvus 表达式 | `MilvusVectorStoreIT` **5 例通过（真连 Milvus v2.5.8）**；全量 `mvn test` → **201 passed**（IT 默认不跑） | T-4 | 已完成（2026-10-16；**AC-3 权限前置过滤已在真库上验证**：无权限的资料检索阶段就命中不到） |
| T-5 | **嵌入**（基础设施） | `vector/EmbeddingModel.java`、`vector/ApiEmbeddingModel.java` | `EmbeddingModel` 接口（单条 + 批量 + 维度）+ **OpenAI 兼容 `/embeddings` 实现**（base-url / api-key / model 走配置，Key 从环境变量读）；**响应维度校验** | `ApiEmbeddingModelTest`（6 例：请求体 / 响应解析 / **维度不符报错** / 未配置报错 / 空入参 / 默认批量有序）通过；全量 `mvn test` → **201 passed** | T-1 | 已完成（2026-10-16；真调 API 归集成测试） |
| T-6 | **检索**（基础设施原语 + 场景过滤） | `vector/Retriever.java`、`vector/VectorRetriever.java`、`vector/VectorFilter.java`（补 `None`）、`kb/KnowledgeScope.java` | 检索原语（算查询向量 → 带过滤 search → **卡阈值**；filter 为「匹配零条」时**短路、连嵌入都不调**）+ **知识库的权限码过滤构造（fail-closed）** | `VectorRetrieverTest`（6 例）、`KnowledgeScopeTest`（3 例）通过；全量 `mvn test` → **210 passed**；真库 `MilvusVectorStoreIT` 仍 5/5 | T-4, T-5 | 已完成（2026-10-16；AC-3、AC-5 覆盖。**「同文档相邻块折叠」移到场景层**——见备注） |
| T-7 | 入库编排 | `kb/KnowledgeIngestor.java`、`kb/KnowledgeManifest.java`、`kb/PermissionSource.java` | 扫目录→解析→切块→**批量嵌入**→写库→记账；按 **`contentHash` 增量**（不看时间戳）；重建**先按 `doc_id` 删旧块**（防幽灵块）；**台账只在成功后写** → 失败下次自然重试；提供 `syncAsync()` | `KnowledgeIngestorTest`（5 例：首入 / 未变跳过 / 变更后旧块清干净 / 不支持类型跳过 / 未声明权限=无人可见）与 `KnowledgeManifestTest`（5 例）通过；全量 `mvn test` → **220 passed** | T-3, T-4, T-5 | 已完成（2026-10-16；权限**来源**抽成 `PermissionSource`，声明机制见 T-7b） |
| T-7b | **可见范围怎么声明** | `kb/PermissionRule.java`、`kb/ConfigPermissionSource.java`、`config/KnowledgeProperties.java`、`application.yml` | **配置里按路径规则授予**（`agent.kb.perm-rules`：相对路径 glob → 权限码，**先命中先用**）；未命中走 `default-perms`；都空则**无人可见**。**原始资料一个字都不改** | `ConfigPermissionSourceTest`（5 例）通过；全量 `mvn test` → **225 passed** | T-7 | 已完成（2026-10-16；机制见备注——**选项 A 被需求方否掉后改为外部声明**） |
| T-8 | 工具接入 | `kb/KnowledgeSearchTool.java`、`config/KnowledgeConfig.java`、`tool/ToolContext.java`、`tool/Tool.java`、`service/ToolCallingService.java`、`service/ConversationService.java`、`service/ChatConfirmService.java`、`application.yml` | **管线补身份**（`ToolContext`：把**权限码**从请求线程显式传进工具执行）；只读工具 `search_knowledge`（元数据在 `agent.tool.catalog`，行为一层 Bean）；kb 各 Bean 装配（**向量库 `@Lazy`**，Milvus 没起也不挡启动）；启动后台扫一次资料 | `KnowledgeSearchToolTest`（7 例）通过；全量 `mvn clean test` → **232 passed**；`ToolRegistry` 启动校验通过 | T-6, T-7b | 已完成（2026-10-16；AC-1/AC-3/AC-5/AC-7 覆盖。**必然动了管线**，见备注） |
| T-9 | 提示词 v3（→v4） | `resources/prompts/assistant/system.md` | `assistant.system` **v2 → v3 → v4**：新增「内部资料工具」一节——**先检索再答** / **只依据检索内容** / **必须注明出处** / **查不到就直说、不编造**；v4 把**出处格式写死**（末尾另起一行 `出处：《文件名 › 章节》`） | 启动日志出现 **`assistant.system@4`**；`DroneAgentApplicationTests`、`PromptCatalogTest` 通过 | T-1 | 已完成（2026-10-16；v4 的动机是回归实测「不固定格式时会漏给出处」） |
| T-10 | 文档与端到端回归 | `drone-agent/docs/**`、`docs/tests/**` | 部署说明补 **§5.8**（起哪些东西 / 配置项 / **新增一份资料的步骤** / **可见范围怎么声明** / 回滚排障）；**真嵌入 + 真 Milvus + 真模型**的端到端回归并逐条记结果 | 文档与实现一致；回归清单逐条有结论 | T-8, T-9 | 已完成（2026-10-16；回归记录见 [`docs/tests/REQ-AGENT-0010-端到端回归.md`](../../../tests/REQ-AGENT-0010-端到端回归.md)；**过程中改掉了一个真缺陷**，见备注） |

**完成标志怎么写**：写成可验证的结果，例如「接口 X 返回 Y，且测试 Z 通过」；不要写「完成开发」「处理好」。

## 验收条件覆盖

| 验收条件 | 覆盖任务 |
|---|---|
| AC-1 | T-8, T-9, T-10 |
| AC-2 | T-6, T-8 |
| AC-3 | T-6, T-8 |
| AC-4 | T-8（沿用 `ToolResultEnvelope`） |
| AC-5 | T-6, T-8 |
| AC-6 | T-3, T-7 |
| AC-7 | T-2 |
| AC-8 | T-4 |

> 【强制】规格里的每条 AC 都要落到至少一个任务上，漏掉的必须在备注里说明原因。

## 执行规则（AI 与人都适用）

- 【强制】一次只做一条任务，做完一条再开下一条。
- 【强制】每条任务完成后更新「状态」列，并给出可验证的证据（测试结果、命令输出）。
- 【强制】执行中发现规格或方案有问题，**停止当前任务**，先回 `specs/` 或 `plans/` 改并评审。
- 【强制】不得擅自扩大范围；发现「顺手也该改」的地方，记下来作为新需求提，不要夹带。

### 备注：本需求会波及的既有代码（属本需求内既定改动）

- `config/ToolConfig.java` 需**多装配** `VectorStore`/`EmbeddingModel`/`Retriever`/`Ingestor`/检索工具——
  **不算夹带**（新能力必然新增 Bean）。
- `resources/prompts/assistant/system.md` 改 `version` 与正文——**不算夹带**（T-9）。
- **不新增 MySQL 表**，故**无迁移脚本**；若实现中发现确需关系库，**停下来回 `specs/` 议**（宪法 §2）。
- **`vector/` 是「基础设施层」，不是知识库专属**：T-4/T-5/T-6 的实现**不得与知识库场景耦合**
  （过滤条件由调用方传入），以便后续「记忆语义检索」直接复用（见 `plans` 第 9 节）。

### 备注：执行中的修正（发现即记录，不悄悄改）

- **T-2 完成标志修正（2026-10-16）**：原写「开关关时上下文里无检索工具」，但**工具要到 T-8 才存在**，
  该断言**只能等到 T-8** 才能验。T-2 的实际完成标志改为「配置绑定测试通过」；
  **开关的装配门（`agent.kb.enabled=false` 时不装配检索工具）在 T-8 一并验证**。
- **T-4 拆分（2026-10-16）**：原 T-4 把「接口 + 内存实现 + Milvus 实现」放一条。
  Milvus 适配**必须有真 Milvus 才能验证**，现在写等于把「不知道对不对」留给下一个人。
  故拆为 **T-4（接口 + 内存实现，本轮已完成）** 与 **T-4b（Milvus 适配，需真 Milvus）**。
- **T-4b 通信方式已定（2026-10-16）**：与 Milvus 走 **REST API（HTTP）**，即 **官方 SDK 不引入**、
  `pom.xml` 不变；`agent.kb.milvus.port` 默认随之改为 **9091**（REST），不是 19530（gRPC）。
- **T-4b 唯一前置**：本地起一个 Milvus（Docker 单机），才能跑 `MilvusVectorStoreIT` 做真实验证。
- **T-4b 的 schema 冲突已解决（2026-10-16）**：连上 Milvus v2.5.8 后发现库里已有 `drone_knowledge`(22 条)、
  `history_embeddings`(94 条)，与草案冲突（无权限字段 / `autoId=true` / 字段名不同）。
  **需求方确认那批数据可丢**，故决策：**沿用集合名 `drone_knowledge`**，schema **按方案补齐**
  （加 `perm_codes`、`autoId=false`、主键 `{doc_id}#{chunk_index}`）；已同步改 `plans` §4。
  **待运维执行一次**：把旧的 `drone_knowledge` drop 掉（旧 schema 与新的不符，程序会明确报错提示）。
- **T-4b 的通信方式已定并实测**：REST `/v2/vectordb/**` **与 gRPC 共用 19530**（9091 只是 management/metrics）。
  不引 Milvus SDK，`pom.xml` 不变。
- **T-4b 用真库验证过**（非纸面）：`MILVUS_IT=true mvn test -Dtest=MilvusVectorStoreIT` → 5 例通过，
  覆盖建集合 / **权限前置过滤** / upsert 覆盖 / 按条件删除 / 表达式翻译；探针集合跑完即删，不碰业务集合。
- **T-6 补了一个安全兜底（2026-10-16）**：新增 `VectorFilter.None`（**匹配零条**）。
  起因：若用「空取值的 `ContainsAny`」表达「用户没有任何权限码」，翻译成库表达式会退化成**空表达式＝不过滤**
  ——**等于把全部内部资料对所有人开放**。现在两头堵住：`ContainsAny` 构造时**拒绝空取值**，
  没有权限码时用 `None`；实现在 `search`/`deleteByFilter` **短路**，`MilvusVectorStore` 走到翻译时直接抛错。
- **T-6 的「去重」移到场景层（2026-10-16）**：原写「检索原语含去重」。但「同文档相邻块折叠」要看
  `doc_id`/`chunk_index` 这类**业务字段**，放进基础设施层会把业务概念漏进向量层（与 `ADR-0006` 决策 3 冲突）。
  故：**基础设施只做「算向量 → 带过滤检索 → 卡阈值」**，折叠由后续场景层（T-8 的检索实现）负责。
  另外，「资料更新后旧块残留」的正解是入库时**先按 `doc_id` 删旧块再写**（T-7），不是靠检索去重。
- **T-7 拆出一条 T-7b（2026-10-16）——规格缺口**：spec 只写了「资料带可见范围（权限码）」，
  **没写「资料怎么声明可见范围」**。故 T-7 把入库管线做全，把来源抽成 `PermissionSource` 接口，
  **声明机制留 T-7b**。
- **T-8 必然动了管线（2026-10-16）**：原以为「一条元数据 + 一个行为 Bean」就够，实际不够——
  检索要按**调用方的权限码**收窄数据，但 ① `Tool.invoke(argumentsJson)` **收不到身份**；
  ② 工具跑在**执行器线程**上，`UserContext`（ThreadLocal）**取不到**（本项目反复踩的坑）。
  故：
  - 引入 **`ToolContext`（角色 + 权限码）**，把 `ToolCallingService` 一路的 `Set<String> userRoles` 换成它；
  - `Tool` 增加 `default invoke(argumentsJson, ToolContext)`——**默认转调老方法**，既有工具（查天气）一行不改；
  - `ConversationService` / `ChatConfirmService` 在**请求线程**上拼好 `ToolContext` 再传下去。
  影响面小（生产 3 处 + 测试 5 处），但**这是本需求对既有管线的实际改动**，如实登记。
- **开关与「元数据外置」冲突的解法（2026-10-16）**：关掉 kb 若**去掉工具 Bean**，`agent.tool.catalog`
  里的 `search_knowledge` 就会「有元数据没行为」→ `ToolRegistry` **启动失败**（这是 `REQ-AGENT-0005` 刻意要的 fail-fast）；
  而把元数据挪进代码又违反「元数据必须外置」。两条路都不能走，故：**Bean 照建，开关在工具内部生效**——
  关掉时它**一次检索都不做**（AC-7 的字面要求满足）。**代价如实说**：模型仍"看得见"这个工具，
  调用会得到「未启用」。若要求「关掉就彻底不出现」，需另想办法（如让 catalog 支持按特性开关），属后续。
- **T-10 的端到端回归已完成（2026-10-16）**，用**真嵌入（GLM `embedding-3`）+ 真 Milvus + 真模型**跑通，
  逐条结果见 [`docs/tests/REQ-AGENT-0010-端到端回归.md`](../../../tests/REQ-AGENT-0010-端到端回归.md)。
  AC-1/AC-3/AC-5/AC-7 均通过（AC-3 的表现尤其干净：无权限时直接「没有检索到」，不泄露资料存在性）。
  **回归过程中改掉一个真缺陷**：`min-score` 默认 **0**，导致**「查不到」永远不会发生**（不相关的问题也返回 5 条）——
  实测相关 ≈0.53 / 不相关 ≈0.46，改成 **0.5**。
  另记为**遗留**：①「注明出处」是模型行为，v4 固定格式后 **5 次 4 次**（非 100%，无结构性兜底）；
  ② `min-score` 需要更大评估集再调；③ 入库后立刻 `count(*)` 因 Milvus Bounded 一致性会偏小（不影响功能）。
- **T-8 引入的启动钩子把单测「污染」了（2026-10-16，自测发现）**：`ApplicationRunner` 在
  `@SpringBootTest` 里**也会执行**，于是**跑一次 `mvn test` 就真的调了嵌入 API、往 Milvus 写了 29 块**
  （台账时间戳 `09:28:28Z` 是证据）。这**违反宪法 §5「单测不得依赖真实外部环境」**，也让测试不可重复、还白花钱。
  **修法**：新增开关 **`agent.kb.scan-on-startup`**（生产默认 `true`），
  并在 `src/test/resources/application.properties` 里设为 **false**；`KnowledgePropertiesTest` 加断言防止回退。
  同时清掉了测试灌进去的块与台账（台账留着会让下次扫描「跳过」→ 资料反而缺失）。
  另：`data/knowledge/` 目录不存在时**改为「建出来」并打印绝对路径**（原来只跳过，运维看不出该往哪放）。
- **T-7b 的机制被纠正过一次（2026-10-16，需求方）**：最初按「在 Markdown 里用 frontmatter 写 `visible-perms`」
  实现（当时选的 A）。需求方指出：**资料是给别人看的东西（操作手册、保险方案），格式五花八门，
  不能为了接入检索去改原始资料**。→ **撤掉 frontmatter 那套**（解析器一并回退，**不留半成品**），
  改为**配置里按路径声明**：`agent.kb.perm-rules`（相对路径 glob → 权限码，先命中先用）
  + `agent.kb.default-perms` 兜底；都空则**无人可见**（fail-closed）。**资料本身一个字都不用动**。
