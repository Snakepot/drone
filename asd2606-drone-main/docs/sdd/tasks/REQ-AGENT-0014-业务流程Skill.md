# 任务拆解 — REQ-AGENT-0014 业务流程 Skill

- **需求 ID**：`REQ-AGENT-0014`
- **负责人 / 日期**：AI / 2026-10-22

## 任务表

| 编号 | 任务 | 产出 | 完成标志 | 依赖 | 状态 |
|---|---|---|---|---|---|
| T-1 | 规范三件套与 ADR-0009 | docs | 评审通过 | - | 已完成 |
| T-2 | 抽取 frontmatter 解析 | `prompt/Frontmatter`，PromptCatalog 复用 | 既有 PromptCatalogTest 全绿 | T-1 | 已完成 |
| T-3 | Skill 目录与定义 | `SkillDefinition` / `SkillCatalog` / `SkillProperties` | 解析/校验/L1 过滤用例通过 | T-2 | 已完成 |
| T-4 | Skill 工具与装配 | `use_skill` / `read_skill_resource` / `SkillConfig` / 校验器 | 工具与越权用例通过 | T-3 | 已完成 |
| T-5 | 提示词按权限裁剪 | `assistant.system` v7 + `ChatService` 三入口 | 三种权限的 L1 断言通过 | T-4 | 已完成 |
| T-6 | 四条业务 Skill | 4 个 `SKILL.md` + 模板 + 口径资料 | 启动加载 4 个；冒烟产出固定版式 | T-5 | 已完成 |
| T-7 | 文档与全量验证 | 手册、README 索引、`mvn test`、真机冒烟 | 全绿并记录 | T-6 | 已完成 |

## 验收条件覆盖

| 验收条件 | 任务 |
|---|---|
| AC-1 | T-3 |
| AC-2 | T-5 |
| AC-3 / AC-4 | T-4 |
| AC-5 | T-4 |
| AC-6 | T-6 / T-7 |
| AC-7 | T-3 |
| AC-8 / AC-9 | T-3 / T-4 |

## 执行规则

- 一次只做一条任务；每条完成后更新状态并给出证据。
- 发现规格/方案问题先停下改文档，不夹带。

## 执行记录（2026-10-22）

- 加载 4 条流程，启动校验通过；`mvn test` → **275 passed**。
- 真机：admin 的 L1 含 4 条；日报按固定版式产出，调用链可串起。
- 冒烟发现轮次预算不足（3 轮），模型输出伪工具文本；已把 `max-rounds` 调到 6 并补提示词约束。
- 未覆盖：派发/告警处置的真机写入（避免改数据）、飞手账号（`agent:chat` 只授予 admin）。
- 详见 `docs/tests/REQ-AGENT-0014-端到端回归.md`。
