# 前端开发规范

> 本文件是 SDD 流程在**前端**层面的细则，配合 [`README.md`](./README.md) 使用。
> 与 [`java-standards.md`](./java-standards.md) 是并列关系：那边管 Java，这边管 TS / Vue。
> 项目宪法（`constitution.md`）可以**加严**本文件，**不得放宽**。

## 0. 基线、分级与冲突处理

- **基线**：Vue 3 官方风格指南（优先级 A、B 视为【强制】，优先级 C 视为【推荐】）
  + TypeScript 严格模式。
- **格式**：交给 Prettier，不在评审里争论缩进与换行；格式问题由工具修，不人工掰。
- **分级**：【强制】必须做到；【推荐】应当做到，不做要说明理由。
- 【强制】本文件与 `java-standards.md` 冲突时**以本文件为准**（各管各的技术栈）。

## 1. 覆盖的工程与依赖

| 工程 | 技术栈 | 说明 |
|---|---|---|
| `drone-web` | Vue 3.5 + Vite 8 + TS + Element Plus + Pinia + vue-router + axios | 管理端 |
| `drone-app` | uni-app（Vue 3.4 + TS 4.9 + Vite 5.2.8） | 飞手端，同时出 H5 与微信小程序 |

- 【强制】Node 版本按 `package.json` 的 `engines`（`drone-web` 为 `^22.18 || >=24.12`，
  `.nvmrc` 为 24）。版本不对先切版本，不要靠降级依赖去迁就环境。
- 【强制】新增依赖必须评审；不得引入与现有同类且重复的库
  （第二个请求库、第二个状态库、第二个 UI 库）。
- 【强制】两个前端**各自独立构建**，不共享 node_modules，不建 workspace。

## 2. 工程结构与依赖方向

`drone-web` 的目录职责（`drone-app` 同理，按 uni-app 约定组织）：

| 目录 | 职责 | 禁止 |
|---|---|---|
| `views/` | 页面，一个路由一个 | 写通用逻辑 |
| `components/` | 可复用组件 | 直接调后端（须经 `api/`） |
| `layouts/` | 布局骨架（菜单、顶栏） | 写业务规则 |
| `api/` | **所有** HTTP 调用的唯一出口 | 出现 UI 逻辑 |
| `stores/` | Pinia 状态 | 当成全局变量袋 |
| `composables/` | 组合式函数（`useXxx`） | 内含具体业务文案 |
| `utils/` | 纯函数工具 | 依赖 Vue 响应式 |
| `types/` | 类型声明（含工具生成物） | 手改自动生成的文件 |

- 【强制】依赖方向：`views → components / composables / stores / api → utils`。
  `utils` 不得反向依赖 `api`、`stores`。
- 【强制】导入一律用 `@/` 别名（`@` → `src`），禁止 `../../../` 这种深相对路径。
- 【强制】`src/types/auto-imports.d.ts`、`src/types/components.d.ts` 由
  `unplugin-auto-import` / `unplugin-vue-components` 生成，**不要手改**。

## 3. 命名

- 【强制】组件文件 `PascalCase.vue`（`AgentChatPanel.vue`）；组件名在模板里也用 PascalCase。
- 【强制】组合式函数 `useXxx.ts`（`useTheme.ts`、`useTaskSocket.ts`）。
- 【强制】`api/` 下模块用小写（`agent.ts`、`task.ts`），导出函数用动词
  （`fetchXxx` / `createXxx` / `updateXxx` / `deleteXxx`）。
- 【强制】类型用 `PascalCase`；与后端契约对应的类型以 `XxxResult` / `XxxParam` 结尾。
- 【强制】禁止中文标识符；禁止拼音英文混用。
- 【推荐】布尔变量不加 `is` 之外的冗余前缀，且保持全项目一致（`loading`、`sending`、`visible`）。

## 4. 类型

- 【强制】**禁止 `any`**。不确定的用 `unknown` + 显式收窄；实在需要逃生舱要写明理由。
- 【强制】函数的入参与返回值必须有类型；不得靠推断留下隐式 `any`。
- 【强制】与后端契约对齐的类型放在 `api/` 模块里，并注明对应的后端结构
  （如统一响应体 `{state, message, data}`、分页 `PageResult`）。
- 【强制】只用于类型的导入用 `import type`（避免打包进运行时）。
- 【推荐】联合类型用 `type`，对象结构用 `interface`。

## 5. 组件

- 【强制】一律 `<script setup lang="ts">`；不写 Options API。
- 【强制】props / emits 用类型式声明（`defineProps<T>()` / `defineEmits<T>()`），
  不用运行时对象，不用 `$attrs` 兜类型。
- 【强制】样式默认加 `scoped`；**全局样式只允许出现在 `assets/theme.css`**。
- 【强制】组件不得感知「当前登录的是谁」以外的权限细节——权限判断统一走
  `useAuthStore().hasPermission()`，不要在组件里硬编码权限码字符串的散落判断。
- 【推荐】父组件通过 `v-model` 传递开合/选中状态（`defineModel`），避免手写 `xx + update:xx`。
- 【强制】组件不得直接调后端；必须经 `api/`。

## 6. 接口层

- 【强制】**所有 HTTP 调用走 `api/` 下的模块 + `api/request.ts`**；
  禁止在组件、store、composable 里直接 `axios` / `fetch`。
- 【强制】统一响应体解包、错误提示、401 处理**集中在 `request.ts`**，不得各写一套。
- 【强制】业务失败是「HTTP 200 + `state≠200`」，**判断成败必须看 `state`**，不能只看 HTTP 状态码。
- 【强制】错误提示由拦截器统一弹出；调用方只负责收尾（关 loading），不要重复弹。
- 【强制】特殊传输（流式 SSE、文件上传、下载）在 `api/` 层单独封装，
  并在文件里**写明为什么不能用统一封装**（如 axios 拿不到流式 body、
  `uni.request` 发不了 multipart）。参考 `drone-web/src/api/agent.ts` 的写法。
- 【强制】请求超时、分页上限由 `api/` 层统一约定，不在业务里随手改。
- 【推荐】`api/` 模块只做「参数 → 请求 → 返回类型」的映射，不掺业务判断。

## 7. 状态管理

- 【强制】用 Pinia；**只有跨页面共享的状态才进 store**，页面内状态留在组件里。
- 【强制】token 的存取只在 `stores/auth` 与 `request.ts` 两处，别处不得直接读写 localStorage。
- 【强制】不把「服务端数据的副本」长期存进 store（除用户信息这类低变更数据），
  需要时现取，避免出现两份真相。
- 【推荐】store 只暴露动作与派生状态（`computed`），外部不要直接改内部 ref。

## 8. 路由与权限

- 【强制】路由用 `meta.permission` 声明所需权限码；页面显隐与守卫统一按它判断。
- 【强制】权限码必须与后端 RBAC 一致（如 `agent:chat`、`task:list`）。
- 【强制】**前端拦截只是体验优化，不构成安全边界**。不得出现「前端拦了所以后端不用拦」
  或「后端拦了所以前端随便」两种偷懒。
- 【推荐】菜单项按权限显隐时，同时准备「无任何可见菜单」的兜底页，避免登录后撞空白。

## 9. 样式与主题

- 【强制】颜色、间距、边框等一律用 `assets/theme.css` 里的 CSS 变量，**禁止硬编码色值**。
- 【强制】深浅色两套都要能看清；新增界面必须在两种主题下各看一眼。
- 【强制】组件样式 `scoped`；需要穿透时用 `:deep()`，并确认不是设计问题。
- 【推荐】响应式断点集中在布局层处理，不要在组件里散落 `@media`。

## 10. 测试

- 【强制】`api/` 层、`utils/`、`composables/` 的**纯逻辑必须有单元测试**
  （不依赖浏览器、不依赖真后端；HTTP 用桩件替）。
- 【强制】框架用 **Vitest**（`drone-web` 已于 2026-10-12 配置）。测试文件与源码同级，
  命名 `<被测文件名>.spec.ts`（如 `src/api/agent.spec.ts`）；用 `npm run test` 执行。
- 【强制】默认 `environment: 'node'`，非必要不加 DOM 环境。
  需要 DOM 或组件测试时必须**先评审**再引入 `happy-dom` / `jsdom` / `@vue/test-utils`。
- 【推荐】关键交互的组件测试。
- 【强制】测试不得依赖真实后端；需要真实接口的归入集成测试并单独标记。
- 【强制】每条验收条件必须有对应用例——**含前端验收条件**（如「无权限时看不到入口」）。
- 【强制】修缺陷先补一个能复现的失败用例。

## 11. 静态检查与交付

- 【强制】提交前 `npm run type-check` 与 `npm run lint` **必须零失败**。
  任一不过即视为不可合并。
- 【强制】提交规范与 `java-standards.md` 第 11 节一致（同一仓库一套约定）。
- 【强制】提交不得包含 `node_modules/`、`dist/`、`.env.local`、真实密钥。
  前端只能放**公开**的 Key（如地图 Web 端 Key），服务端密钥绝不下发到前端。
- 【强制】环境变量以 `.env.*` 管理，敏感值放不进仓库的 `.env.local`；
  注意 Vite 的优先级是 `.env.development` > `.env.local`，
  在 `.env.development` 里写空值会把 `.env.local` 的真实值**盖掉**。

## 12. 前端特有的坑（必须知道）

- 【强制】后端**没有配 CORS**。`drone-web` 与 `drone-app` 的 H5 必须走 devServer 代理
  （同源），不能直连后端。
- 【强制】`drone-web` 的 `vite.config.ts` 中 `/api/agent` 那条代理**必须排在 `/api` 之前**
  （按声明顺序匹配，顺序反了会被 `/api` 吃掉、转发到错误的后端）。
- 【强制】小程序没有 CORS 概念，直连后端地址；联调需在开发者工具里勾
  「不校验合法域名」，真机调试要把地址换成开发机的局域网 IP。
- 【强制】`drone-app` 不能用 axios（小程序没有 `XMLHttpRequest`），
  请求必须走 `uni.request` 封装；文件上传走 `uni.uploadFile`。

## 13. 注释

> **读者定位同样是初级工程师**，通用要求见
> [`java-standards.md`](./java-standards.md) 第 13 节（那里给了反例/正例的对照）。
> 本节只列前端特有的几条，**不重复**已在那里的要求。

- 【强制】`<script setup>` 里**每个 ref / computed / 函数都要能一眼看出它承载什么状态或什么动作**；
  交互链路稍复杂时，先在顶部用一小段块注释说明「这一段在整个交互里处于哪一步」。
- 【强制】模板里的**条件渲染与嵌套三元必须加注释**说明「这个分支对应什么业务状态」——
  否则读者只能从表达式反推业务含义。
- 【强制】`composables/` 里的组合式函数，头注释写清：它解决什么问题、**调用时机**、
  以及**它自己不管什么**（例如「它只管生成与持久化，不管面板何时打开」）。
- 【强制】`api/` 模块的注释写清**为什么这样传**，而不是复述字段名。
  典型场景：某个请求为什么**不走**统一封装（如流式响应必须用 `fetch` 读流）。
- 【推荐】样式里的注释说明**这段样式为什么存在**（例如「顶栏高 64px，面板从它下面开始」），
  不要复述属性值 —— 属性本身写着呢。
