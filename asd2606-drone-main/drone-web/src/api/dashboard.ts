import { http } from './request'

/**
 * 数字大屏的数据接口（见 `docs/数字大屏方案.md` 3.2）。
 *
 * <h2>为什么是一个总览接口，而不是十几个</h2>
 * 权限要在服务端裁（哪些块这个人能看，只有服务端说了算）、
 * 整屏的数字要属于同一时刻、而且它每 10 秒就会被调一次。
 * 所以「一屏的数字」一次给全：**返回里有哪些 key，就渲染哪几张卡片**。
 */

/** 一块「值 → 条数」的分布 */
export type CountMap = Record<string, number>

/** 任务总览（管理侧） */
export interface DashboardTasks {
  total: number
  byStatus: CountMap
  todayNew: number
  overdue: number
}

/** 我的任务（飞手侧）。口径与 DashboardTasks **不同**，所以名字也不一样 */
export interface DashboardMyTasks {
  total: number
  pending: number
  doing: number
  done: number
}

/** 大屏滚动流里的一条告警 */
export interface RecentAlert {
  label: string
  /** critical / warning / info */
  severity: string
  deviceCode?: string
  message?: string
  observedAt?: string
}

export interface DashboardAlerts {
  open: number
  bySeverity: CountMap
  todayNew: number
  recent?: RecentAlert[]
}

export interface DashboardMyAlerts {
  open: number
}

export interface DashboardDevices {
  total: number
  byStatus: CountMap
  totalFlights: number
  totalHours: string | number
  /** 0~1 */
  onlineRate: number
  /** 在线率的口径：最近这么多分钟内有数据就算在线 */
  onlineWindowMinutes: number
}

export interface DashboardStaff {
  total: number
  idle: number
  busy: number
  other: number
}

export interface DashboardGeo {
  points: number
  routes: number
  zones: number
}

export interface DashboardEvidence {
  today: number
  lastHour: number
}

/**
 * 一屏要的所有数字。
 *
 * <p>⚠️ 每个块都可能是 `undefined` —— **那表示「你没权限看」**，不是「数量是 0」。
 * 所以卡片要按 `v-if` 渲染，而不是把 undefined 当成 0 显示出来。
 */
export interface DashboardSummary {
  generatedAt?: string
  tasks?: DashboardTasks
  myTasks?: DashboardMyTasks
  alerts?: DashboardAlerts
  myAlerts?: DashboardMyAlerts
  devices?: DashboardDevices
  staff?: DashboardStaff
  geo?: DashboardGeo
  evidence?: DashboardEvidence
}

/** 大屏自己的配置，与后端 ScreenProperties 对应（刷新间隔、轮播） */
export interface ScreenConfig {
  carouselEnabled: boolean
  carouselPages: string[]
  carouselSeconds: number
  carouselPinOnCritical: boolean
  carouselPinMaxSeconds: number
  refreshMapSeconds: number
  refreshLiveSeconds: number
  refreshStatSeconds: number
  pauseWhenHidden: boolean
  nightPauseEnabled: boolean
  nightPauseFrom: string
  nightPauseTo: string
}

/** 一屏的数字。**只要登录就能调** —— 哪个块给不给出服务端按权限裁 */
export function fetchSummary() {
  return http.get<DashboardSummary>('/dashboard/summary')
}

/** 最近的告警（滚动流）。要 `alert:list` */
export function fetchRecentAlerts(limit = 8) {
  return http.get<RecentAlert[]>('/dashboard/recent', { limit })
}

/** 大屏配置。登录即可 —— 它只有刷新间隔和轮播参数，没有业务数据 */
export function fetchScreenConfig() {
  return http.get<ScreenConfig>('/dashboard/config')
}
