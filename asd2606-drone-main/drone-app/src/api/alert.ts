import { http, type PageResult } from './request'
import type { TaskAttachmentVO } from './attachment'

/**
 * 告警，与后端 AlertVO 对应。
 *
 * <p>这里只有**读**：飞手端对告警没有任何写操作（不认领、不关闭）。
 * 「自己报的问题自己关掉」等于没有监督，所以销账是管理侧的职责 ——
 * 设计见 docs/告警体系设计.md 第十二章。
 */
export interface AlertVO {
  id: number
  taskId?: number
  /** 设备编号。人工上报的告警**没有**设备编号（那不是设备说的），所以是可空的 */
  deviceCode?: string
  alertCode: string
  /** 中文名由服务端给（`AlertCode.label`），前端不维护第二份码表 */
  alertCodeLabel: string
  /** info 提示 / warning 警告 / critical 严重 */
  severity: string
  severityLabel: string
  message: string
  /** device 设备上报 / app 飞手端上报 / operator 后台操作 */
  source?: string
  observedAt?: string
  reportedAt?: string
  /** pending 待处理 / handling 处理中 / resolved 已处理 / ignored 误报 */
  status: string
  statusLabel: string
  handledByName?: string
  handledAt?: string
  handleNote?: string
  /** 需不需要飞手在现场动手 */
  pilotActionRequired?: boolean
  /** 需要动手时的那句「该做什么」 */
  actionHint?: string
  attachments?: TaskAttachmentVO[]
}

/** 查某条任务上的告警 */
export interface TaskAlertQuery {
  taskId: number
  page?: number
  pageSize?: number
}

/**
 * 查询派给我的某条任务上的告警。
 *
 * <p>没有 pilotId 参数：服务端从登录态取身份，并校验这条任务是不是派给我的，
 * 不是就返回 403。前端传什么都不作数。
 */
export function fetchTaskAlerts(params: TaskAlertQuery) {
  return http.get<PageResult<AlertVO>>('/task/alert/list', { ...params })
}

/**
 * 现场异常上报的入参。
 *
 * <p>`alertCode` 只能取那 4 个人工上报类编码（见 {@link manualAlertOptions} 对应的字典），
 * 传设备类的（如 `bat.critical`）会被服务端拒掉 —— 这是防伪造：
 * 设备测到的告警只能由设备数据产生，人不能替它说话。
 */
export interface OnsiteReportParam {
  taskId: number
  alertCode: string
  message: string
  attachmentIds?: number[]
}

/**
 * 提交现场异常上报。
 *
 * <p>说明是必填的：管理侧要据此判断怎么处理，一句「有问题」等于没说。
 * 照片可选 —— 附件要先用 {@link uploadTaskAttachment} 上传拿到 id。
 */
export function reportOnsiteIssue(data: OnsiteReportParam) {
  return http.post<AlertVO>('/task/alert/report', { ...data })
}
