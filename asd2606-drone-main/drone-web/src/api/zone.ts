import { http, type PageResult } from './request'

/**
 * 禁飞区（地图接入方案 第三步：静态校验）。
 *
 * <h2>⚠️ 坐标一律是 GCJ-02</h2>
 * 与点位库、航线同一条约定：库里存 WGS84，接口两端都是 GCJ-02，
 * 后端在出入口各转一次。所以本地不再做任何坐标转换 ——
 * 画在地图上的就是接口给的，点出来的位置原样传回去。
 *
 * <h2>校验的两个接口为什么在 /task 下</h2>
 * 校验要判的是「<b>这条任务打算飞哪些点</b>」，那是任务领域的事
 * （起降点 + 航线快照），所以预检接口挂在任务那边：
 * {@link checkPlanZones}（建任务前）与 {@link checkTaskZones}（派发前）。
 * 本文件把它们和禁飞区的 CRUD 放在一起，只是因为前端用起来方便。
 */

/** 一个坐标点（GCJ-02） */
export interface ZonePoint {
  lng: number
  lat: number
}

export interface NoFlyZoneVO {
  id: number
  /** 固定 gcj02，由后端给出 */
  coordinateSystem: string
  name: string
  /** official 官方空域 / custom 自建 */
  source: string
  sourceLabel: string
  /** polygon 多边形 / circle 圆 */
  shape: string
  shapeLabel: string
  /** 多边形顶点，shape=polygon 时有值 */
  points?: ZonePoint[]
  /** 圆心，shape=circle 时有值 */
  center?: ZonePoint
  /** 半径（米），shape=circle 时有值 */
  radius?: number
  /** 区域内限高（米）；为空表示完全禁飞 */
  maxAlt?: number
  /** 是否完全禁飞（后端算好的，界面靠它决定颜色与文案） */
  forbidden: boolean
  /** 高度规则说明，如「完全禁飞」「限高 120 米」 */
  altRuleLabel: string
  effectiveFrom?: string
  effectiveTo?: string
  enabled: boolean
  remark?: string
  createByName?: string
  createTime?: string
  updateTime?: string
}

export interface NoFlyZoneQuery {
  keyword?: string
  source?: string
  enabled?: boolean
  page?: number
  pageSize?: number
}

/**
 * 新增 / 编辑参数。
 *
 * <p>形状决定填哪一组字段：多边形填 `points`，圆填 `centerLng/centerLat/radius`。
 * `maxAlt` 不填表示**完全禁飞** —— 别用 0 表示「不限」，那是「限高 0 米」。
 */
export interface NoFlyZoneSaveParam {
  id?: number
  name: string
  source?: string
  shape?: string
  points?: ZonePoint[]
  centerLng?: number
  centerLat?: number
  radius?: number
  maxAlt?: number
  effectiveFrom?: string
  effectiveTo?: string
  enabled?: boolean
  remark?: string
}

/** 一条命中记录，与后端 ZoneHitVO 对应 */
export interface ZoneHitVO {
  zoneId: number
  zoneName: string
  source: string
  shape: string
  forbidden: boolean
  maxAlt?: number
  /** 命中点是什么：起降点 / 第 3 个航点 / 航线途经点 */
  pointLabel: string
  lng: number
  lat: number
  /** 是否因超过区域限高而命中 */
  overAlt: boolean
  /** 拼好的一句话，直接显示即可 */
  message: string
}

/** 形状与来源字典（编码 → 中文名），从后端取 —— 前端不再维护一份映射 */
export interface ZoneOptions {
  shapes: Record<string, string>
  sources: Record<string, string>
}

export function fetchZoneOptions() {
  return http.get<ZoneOptions>('/zone/options')
}

export function fetchZonePage(params: NoFlyZoneQuery) {
  return http.get<PageResult<NoFlyZoneVO>>('/zone/list', params)
}

export function fetchZoneDetail(id: number) {
  return http.get<NoFlyZoneVO>(`/zone/${id}`)
}

export function addZone(data: NoFlyZoneSaveParam) {
  return http.post<NoFlyZoneVO>('/zone/add', data)
}

export function updateZone(data: NoFlyZoneSaveParam) {
  return http.post<NoFlyZoneVO>('/zone/update', data)
}

export function deleteZone(id: number) {
  // 后端是 POST + @RequestParam，所以 id 走查询串
  return http.post<void>(`/zone/delete?id=${encodeURIComponent(id)}`)
}

/** 启用 / 停用：排查问题时临时停掉一个区，且**不该顺手改它的形状** */
export function toggleZone(id: number, enabled: boolean) {
  return http.post<void>(`/zone/${encodeURIComponent(id)}/toggle?enabled=${enabled}`)
}

/**
 * 建任务前的预检：这条航线 + 这个起降点会不会撞上禁飞区。
 *
 * <p>只查不写。查询结果用来把「撞的是哪几块区域」摆到人面前 ——
 * 真正的底线在 `POST /task/create` 那一步，它自己也会再查一遍。
 */
export function checkPlanZones(param: { routeId?: number; takeoffLng?: number; takeoffLat?: number }) {
  return http.post<ZoneHitVO[]>('/task/zone-check', param)
}

/** 派发前的预检：这条任务已经定下来的计划会不会撞上禁飞区 */
export function checkTaskZones(taskId: number) {
  return http.get<ZoneHitVO[]>(`/task/${taskId}/zone-check`)
}
