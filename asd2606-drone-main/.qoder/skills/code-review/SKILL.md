---
name: code-review
description: Review changed or proposed code in the drone monorepo against AGENTS.md, the project constitution and docs/sdd/java-standards.md + frontend-standards.md; produce severity-ranked findings (BLOCKER/MAJOR/MINOR/NIT) with file:line, rule citation and a minimal fix, plus an explicit list of checks actually run. Use when asked to review a diff, PR/MR, staged changes, a file or module, or to self-review before committing.
when_to_use: User says 代码评审 / code review / review this diff / 帮我看下改动 / 自审 before commit.
---

# Code Review（drone 仓库专用）

## Overview

按**本仓库自己的规则**评审，不输出泛泛的风格偏好。规则来源只有四个：
`AGENTS.md`、`docs/sdd/constitution.md`、`docs/sdd/java-standards.md`、`docs/sdd/frontend-standards.md`。
每条结论必须能指到「哪一行 + 哪条规则」，否则不要写。

## Workflow

### 1. 先定范围（不要凭记忆审）

- 看改动：`git status --short`、`git diff --stat`，然后 `git diff`（或 `git diff --cached`、`git diff <base>...HEAD`）。
- 审单个文件/模块：**完整读文件**，不要只读 diff 片段。
- 按工程分组：`drone-agent` / `drone-server` / `drone-web` / `drone-app`；跨工程的改动要分别对照各自的规范。
- 若改动关联需求：读 `docs/sdd/specs|plans|tasks/<REQ-...>.md`，确认**范围与验收条件**。

### 2. 只加载相关规则

| 改动范围 | 必读 |
|---|---|
| 全部 | `AGENTS.md`、`docs/sdd/constitution.md` |
| Java（`drone-agent` / `drone-server`） | `java-standards.md` §5 异常与日志、§7 配置与密钥、§8 安全、§9 测试、§11 Git、§13 注释 |
| Vue / uni-app | `frontend-standards.md` §6 接口层、§8 路由权限、§10 测试、§11 交付、§12 前端特有的坑、§13 注释 |
| 关联需求 | 对应 `specs/`（AC）、`plans/`（接口/数据模型）、`tasks/`（完成标志） |

### 3. 按这个顺序审（高影响在前）

1. **正确性与安全**：越权、密钥、数据丢失/损坏、吞异常、并发。
2. **SDD 合规**：有没有 spec/plan/task；是否**超范围**；AC 是否有对应用例；提交能否追溯到 REQ。
3. **仓库约定**：统一响应体、分层、日志、注释、配置外置。
4. **测试与验证**：AIR、不依赖真实外部环境、关键路径覆盖。
5. **Nit**：最多 5 条，明确标「可选」。

### 4. 验证（只报真跑过的）

相关时运行并记录**命令 + 结果**：

```bash
cd drone-server && mvn -q compile
cd drone-agent  && mvn test
cd drone-web    && npm run type-check && npm run lint
cd drone-app    && npm run type-check
```

- **没跑就不写结论**；不能把「应该能过」写成「已通过」。
- 跑不动（缺环境/缺密钥）就写「未执行 + 原因」。

### 5. 输出格式（固定）

```markdown
## 结论
<1~3 句 + 可合并 / 需修改 / 有阻塞>

## 发现（按严重度）
| 级别 | 位置 | 问题 | 为什么（规则） | 建议改法 |
|---|---|---|---|---|
| BLOCKER | `path/File.java:42` | ... | java-standards §8【强制】... | 最小改法 |

## 验证
- `mvn test` → 233 passed（实际输出摘要）
- **未执行**：`npm run lint`（原因）

## 未覆盖
- 没审的部分与原因（如生成代码、历史遗留）
```

### 6. 落一份报告（默认做）

聊天里的结论只是摘要；**完整证据链要落到文件**，便于复核、追踪和随代码回滚。

- 用 `assets/report-template.md` 填一份，写到 **`docs/reviews/<YYYY-MM-DD>-<范围短名>.md`**
  （例：`docs/reviews/2026-10-20-MemoryConfig.md`）。
- 「范围短名」用**被审对象**（类名 / 需求 ID / 分支名），不要用 `review1`、`final` 这类名字。
- 报告与聊天摘要**内容一致**：聊天给结论，报告留完整记录（发现 + 规则依据 + 验证 + 未覆盖）。
- **用户明说「只看结论 / 不用写文件」时**：不落盘，并在回复里注明「未生成报告」。
- 报告里**不允许**出现：没执行过的命令、没读过的文件、猜出来的行号。

## 严重度定义

| 级别 | 判定 |
|---|---|
| **BLOCKER** | 安全漏洞（越权/密钥/注入）、数据丢失或损坏、编译不过、违反【强制】且会导致错误行为 |
| **MAJOR** | 某条 AC 无测试、分层/契约违规、吞异常、异步日志未标注、配置硬编码 |
| **MINOR** | 低影响约定偏离、命名不达意、缺「为什么」注释 |
| **NIT** | 纯风格；标注「可选」，最多 5 条 |

## 评审纪律

- **指到行**：每个问题给 `file:line`，必要时引用那一行原文。
- **指到规则**：写明出处（如 `java-standards §5【强制】禁止吞异常`）。
- **最小改法**：只给修复建议，**不擅自重写**整段代码；用户要求才动手。
- **区分【发现】与【推断】**：不确定就写「需要确认 X」，不要猜。
- **不编造**：测试结果、行号、日志、调用关系都必须来自实际读取/执行。
- **不吹捧**：没问题就说没问题，不要凑「亮点」。
- **报告是记录，不是营销材料**：不写「代码质量良好」这类空话；状态（待修/已修/接受）要如实标。

## 本仓库的高频雷区（优先看）

- **统一响应体** `{state,message,data}`；**业务失败是 HTTP 200 + `state≠200`**，前端必须判 `state`。
- **权限**：Token 只装身份；权限每次查库；`/internal/**` 用共享密钥；**前端隐藏入口不算边界**。
- **ThreadLocal**：`UserContext` 必须清理（含 SSE 的 `afterConcurrentHandlingStarted`）；
  工具跑在执行器线程 → **身份必须显式传参**（见 `ToolContext`）。
- **日志**：关键节点一进一出、自解释、**异步动作必须标明异步**、脱敏、**不打 `Authorization`**。
- **提示词**只在 `prompts/**/*.md`；工具元数据只在 `agent.tool.catalog`；依赖版本只在 `<properties>`。
- **数据库**：新增迁移文件（不改老文件）、`lower_snake_case`、无外键、无物理删除。
- **前端**：HTTP 只走 `api/`；`/api/agent` 代理必须排在 `/api` 之前；**后端无 CORS**，不能直连。
- **提交**：中文 `动作：细节` + 需求 ID；不带 `target/`、`node_modules/`、`data/`、密钥。
- **SDD**：改动前有 specs/plans/tasks；**一次只做一条任务**；发现「顺手该改」的记下来另立需求，不夹带。

## Resources

- `references/checklist.md`：按领域展开的检查清单（含本仓库踩过的真实反例）。
  改动跨多个领域、或需要精确引用【强制】原文时读它。
- `assets/report-template.md`：评审报告模板。**每次评审默认按它落一份**到 `docs/reviews/`。
