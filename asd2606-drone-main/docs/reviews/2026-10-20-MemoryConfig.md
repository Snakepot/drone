# 代码评审报告 — MemoryConfig

> **评审对象**：`drone-agent/src/main/java/cn/tedu/droneagent/config/MemoryConfig.java`
> **评审范围**：1 个文件（存量代码评审，被审对象**无改动**）
> **评审人 / 日期**：AI / 2026-10-20
> **规则依据**：`AGENTS.md` · `docs/sdd/constitution.md` · `docs/sdd/java-standards.md` · `docs/sdd/frontend-standards.md`
> **结论**：**可合并**

## 1. 摘要

记忆子系统的装配与生命周期**没有发现违反【强制】的问题**：
停机刷盘阶段（1000）大于归档器（0），Spring 先同步刷盘再停重试调度器；
Caffeine 的移除回调跑在专属线程池（`memory-archive-N`），不占公共 `ForkJoinPool`；
executor 交给 Spring `shutdown`，数据安全由同步刷盘兜底，不依赖 `awaitTermination`。

剩下 4 条是**非阻塞建议**，集中在两件事：**依赖注入显式化**、**配置的启动期校验**。

| 级别 | 数量 | 含义 |
|---|---|---|
| BLOCKER | 0 | 安全 / 数据 / 编译 |
| MAJOR | 0 | 规则或契约违规、AC 缺测试 |
| MINOR | 0 | 低影响偏离 |
| NIT | 0 | 纯风格，可选 |

## 2. 发现

| ID | 级别 | 位置 | 问题 | 规则依据 | 建议改法 | 状态 |
|---|---|---|---|---|---|---|
| — | — | — | **未发现违反【强制】的问题** | — | — | — |

## 3. 建议（无对应【强制】，不阻塞）

| ID | 位置 | 建议 | 理由 |
|---|---|---|---|
| SG-001 | `MemoryConfig.java:71` | 给 `Executor` 注入加 `@Qualifier("memoryArchivingExecutor")` | 上下文里现有 **3 个 `Executor` 类型 Bean**（`memoryArchivingExecutor` / `toolExecutor` / `knowledgeIngestExecutor`）。当前能注入成功，靠的是 Spring 在多个候选中**回退按参数名匹配**；一旦重命名这个参数，启动就会 `NoUniqueBeanDefinitionException` |
| SG-002 | `MemoryConfig.java:55`、`:60`、`:93` | 抽一个 `memoryRoot(MemoryProperties)` 私有方法，三处复用 | `Path.of(properties.root())` 重复三次；将来统一做 `toAbsolutePath().normalize()` 或校验时，改三处容易漏 |
| SG-003 | `MemoryConfig.java:55`（以及同源的 60/93） | 装配时校验 `root` 非空白，空则 `throw new IllegalStateException(...)` | `${AGENT_MEMORY_ROOT:./data/agent-memory}`：**环境变量被设成空串时 Spring 不会回落默认值**，`Path.of("")` 指向**进程工作目录**——记忆文件会悄悄散落到 cwd，而不是部署说明要求的挂载卷 |
| SG-004 | `MemoryConfig.java:46` | 如需按磁盘能力调整，可把线程数提到 `agent.memory.*` | 目前硬编码 `2`，但第 40–41 行已解释「2 个足够、形成背压」——**可接受**，不强制改 |

## 4. 验证记录

| 命令 | 结果 | 说明 |
|---|---|---|
| `cd drone-agent && mvn -q compile` | **成功**（无输出） | 编译通过 |
| `mvn test -Dtest=DroneAgentApplicationTests` | **Tests run: 1, Failures: 0, Errors: 0** | 完整上下文能起；顺带证明 3 个 Executor Bean 的注入路径是通的 |
| `mvn test`（全量） | **未执行** | 本次评审**无改动**；全量结果属历史数据，不计入本次结论 |
| `cd drone-web && npm run type-check` / `npm run lint` | **未执行** | 改动未涉及前端 |

## 5. 未覆盖与风险

- **未覆盖**：`SessionStore` / `MemoryArchiver` / `MemoryShutdownFlusher` 的内部实现——
  本次只读了与**装配**相关的生命周期与回调路径，没有逐行评审它们的业务逻辑。
- **需人工确认**：无。
- **已知遗留**：无（4 条建议均不阻塞合并）。

## 6. 变更清单

```
（无改动：评审时 git status 为空）
```

## 7. 下一步

- [ ] 决定是否采纳 **SG-001**（`@Qualifier`，一行改动，建议采纳）
- [ ] 决定是否采纳 **SG-003**（root 空白 fail-fast，与「配置写错就起不来」的一贯取向一致）
- [ ] **SG-002** / **SG-004** 可选，不阻塞

---

> 本报告是**记录**，不是营销材料：不写「亮点」，不夸大；没验证的就写没验证。
> 报告与代码同仓库，随代码一起评审与回滚。
