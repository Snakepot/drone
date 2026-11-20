---
name: skill-name
version: 1
owner: 业务归属（如 运营部）
description: 一句话说明这个流程做什么（会进 L1，控制在 30 字以内）
when_to_use: 用户会怎么问（会进 L1，写 2–3 个典型说法）
permissions: [some:permission]
tools:
  - drone__some_tool
  - read_skill_resource
---

# 流程标题

> 需要模板/样式等素材时，放到同目录的 `assets/` 下（与 `references/` 一样按需读取，
> 只支持文本类扩展名：md/txt/json/csv/html/css）。

## 目标

一句话：这个流程解决什么问题，产出什么。

## 执行步骤（必须按顺序）

**第 1 步 · {动作}**：调用 `drone__xxx`，参数 `{...}`。
记录结果里的 `{关键字段}`。

**第 2 步 · {动作}**：调用 `drone__yyy`，参数 `{...}`。

## 输出模板

```markdown
# {标题} · {日期}
...
```

## 禁止

- 不得编造接口没有返回的数据。
- 写操作前必须复述关键参数并等用户确认。
- 不得跳过步骤；缺一步就停下说明原因。
