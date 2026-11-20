import { http, type PageResult } from './request'
import type { TaskAttachmentVO } from './attachment'

/**
 * 告警视图对象，与后端 AlertVO 对应。
 *
 * <p>几个「中文名」（`alertCodeLabel` / `severityLabel` / `statusLabel`）
 * 是**后端给的**，不是前端映射的 —— 告警编码有 27 个，
 * 前端再写一份字典必然和后端漂移：后端加了新告警、前端没跟着加，
 * 页面上就是一片英文编码。所以这份对应关系只维护一处（后端 AlertCode）。
 *
 * <p>但「用什么颜色」是前端的事，后端不管 —— 见下面的 tone 映射。
 */
export interface AlertVO {
  id: number
  /** 所属任务 ID，可能为空：告警可以不属于任何任务（试飞、机库自检） */
  taskId?: number
  deviceCode?: string
  /** 告警编码，如 bat.critical */
  alertCode: string
  /** 编码中文名，如「电量严重不足」 */
  alertCodeLabel?: string
  /** info 提示 / warning 警告 / critical 严重 */
  severity: string
  severityLabel?: string
  message?: string
  /** device 设备 / app 飞手端 / operator 人工 */
  source?: string
  observedAt?: string
  reportedAt?: string
  /** pending 待处理 / handling 处理中 / resolved 已处理 / ignored 误报 */
  status: string
  statusLabel?: string
  handledByName?: string
  handledAt?: string
  handleNote?: string
  /** 是否需要飞手在现场动手 */
  pilotActionRequired?: boolean
  actionHint?: string
  /**
   * 现场照片等附件。飞手端上报时拍的，这里嵌在告警里一起返回 ——
   * 所以列表和详情都不用再额外请求一次
   */
  attachments?: TaskAttachmentVO[]
}

/** 查询条件，与后端 AlertQueryParam 对应 */
export interface AlertQuery {
  keyword?: string
  severity?: string
  status?: string
  deviceCode?: string
  pilotActionRequired?: number
  /**
   * 发生时间区间（下钻用，见 docs/业务分析方案.md 三点五）：
   * MTTR 与告警趋势都按 observed_at 落窗，列表按同一列筛才对得上。
   */
  observedFrom?: string
  observedTo?: string
  page?: number
  pageSize?: number
}

/** 处理动作，与后端 AlertStatusFlow 的三个动作对应 */
export type AlertAction = 'claim' | 'resolve' | 'ignore'

export interface AlertHandleParam {
  id: number
  action: AlertAction
  /** 处置说明。收尾（resolve / ignore）时后端要求必填 */
  note?: string
}

export function fetchAlertPage(params: AlertQuery) {
  // 注意：这里的 http.get 第二个参数就是查询参数本身，
  // 不是 axios 的 { params } 包装 —— 见 api/request.ts 里 http 的定义
  return http.get<PageResult<AlertVO>>('/alert/list', params)
}

export function fetchAlertDetail(id: number) {
  return http.get<AlertVO>(`/alert/${id}`)
}

export function handleAlert(data: AlertHandleParam) {
  return http.post<AlertVO>('/alert/handle', data)
}

