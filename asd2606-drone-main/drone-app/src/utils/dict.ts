/**
 * 任务的状态与优先级字典。
 *
 * <h2>这三个地方必须一致</h2>
 * | 位置 | 用途 |
 * |---|---|
 * | 后端 `TaskStatusFlow` | 状态的权威定义（含合法流转表） |
 * | 后台管理端 `drone-web/src/views/TaskView.vue` | 管理员的展示 |
 * | 本文件 | 飞手端的展示 |
 *
 * 数据库里存的是英文编码（`pending` 这种），中文展示放在前端 ——
 * 所以加语言、改文案都不用动数据，但前端这几处字典要跟着改。
 */
export interface DictItem {
  value: string
  label: string
  /** 对应 App.vue 里的 `.tag--xxx` 配色 */
  tone: string
}

export const priorityOptions: DictItem[] = [
  { value: 'P0', label: 'P0 紧急', tone: 'red' },
  { value: 'P1', label: 'P1 高', tone: 'amber' },
  { value: 'P2', label: 'P2 中', tone: 'cyan' },
  { value: 'P3', label: 'P3 低', tone: 'grey' },
]

export const statusOptions: DictItem[] = [
  { value: 'pending', label: '待派发', tone: 'amber' },
  { value: 'dispatched', label: '已派发', tone: 'cyan' },
  { value: 'executing', label: '执行中', tone: 'violet' },
  { value: 'finished', label: '已完成', tone: 'green' },
  { value: 'failed', label: '已失败', tone: 'red' },
  { value: 'cancelled', label: '已取消', tone: 'grey' },
]

/**
 * 告警的严重级别。
 *
 * <h2>这三档和任务的 P0~P3 是两回事</h2>
 * 任务那套说的是「计划有多重要」，这套说的是「<b>此刻有多紧迫</b>」。
 * 一个 P3 的低优先级任务，设备电池烧了照样是 `critical`。
 * 混用会让人搞不清「这条到底急不急」—— 所以刻意不复用
 * （设计文档第三章专门写了这条）。
 */
export const alertSeverityOptions: DictItem[] = [
  { value: 'critical', label: '严重', tone: 'red' },
  { value: 'warning', label: '警告', tone: 'amber' },
  { value: 'info', label: '提示', tone: 'grey' },
]

/** 告警的处理状态。飞手端只展示、不改 —— 销账是管理侧的职责 */
export const alertStatusOptions: DictItem[] = [
  { value: 'pending', label: '待处理', tone: 'amber' },
  { value: 'handling', label: '处理中', tone: 'cyan' },
  { value: 'resolved', label: '已处理', tone: 'green' },
  { value: 'ignored', label: '误报', tone: 'grey' },
]

/**
 * 现场异常上报的 4 个选项（人工上报类）。
 *
 * <h2>为什么这 4 条要单独列出来</h2>
 * 它们的价值最容易被低估：设备传感器测得到电量，但测不到
 * 「现场围了一群人不让起飞」「磁场干扰」「客户临时改要求」。
 * 这些只有站在那儿的人知道，而它们往往正是任务做不下去的真正原因。
 *
 * <p>编码必须与后端 {@code AlertCode.MANUAL_CODES} 一致 ——
 * 那里是白名单，传别的编码会被拒（防伪造设备测得的告警）。
 */
export const manualAlertOptions: DictItem[] = [
  { value: 'man.onsite.issue', label: '现场异常', tone: 'amber' },
  { value: 'man.device.damage', label: '设备损伤', tone: 'red' },
  { value: 'man.weather', label: '天气突变', tone: 'amber' },
  { value: 'man.customer.issue', label: '客户问题', tone: 'grey' },
]

/**
 * 执行依据 —— 「这句话有没有旁证」。
 *
 * <h2>为什么飞手端也要显示它</h2>
 * 它回答的是「这次执行<b>凭的是什么</b>」：
 * 只有飞手一句「我飞了」（declared），还是有设备数据撑腰（confirmed）。
 * 收尾时把「仅声明」标出来，是为了让飞手知道自己在承担什么 ——
 * 完成**不强制**要有设备数据（强制会把「图传断了」变成「无法收工」），
 * 但必须让人看得见。
 */
export const evidenceStateOptions: DictItem[] = [
  { value: 'none', label: '无依据', tone: 'grey' },
  { value: 'declared', label: '仅飞手声明', tone: 'amber' },
  { value: 'confirmed', label: '已有设备数据佐证', tone: 'green' },
  { value: 'stale', label: '声明已过期', tone: 'red' },
]

/**
 * 取展示文案。
 *
 * <p>遇到没登记的编码**原样返回**，而不是显示空白或「未知」——
 * 后端将来加了新状态时，界面上至少能看到原始的编码，不至于让人以为是没数据。
 */
export function labelOf(options: DictItem[], value?: string): string {
  return options.find((item) => item.value === value)?.label ?? value ?? '—'
}

/** 取标签配色；没登记的统一用灰色 */
export function toneOf(options: DictItem[], value?: string): string {
  return options.find((item) => item.value === value)?.tone ?? 'grey'
}
