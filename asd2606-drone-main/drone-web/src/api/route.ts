import { http, type PageResult } from './request'

/**
 * 点位库与航线的接口。
 *
 * <h2>⚠️ 坐标一律是 GCJ-02</h2>
 * 库里存的是 WGS84（GPS 原始值），但接口两端都是 GCJ-02 ——
 * 后端在出入口各转一次，见后端 `SurveyPointVO` / `SurveyPointSaveParam` 的说明。
 * 所以这里<b>不要再转</b>：读出来直接交给地图，点出来的位置直接传回去。
 *
 * <h2>为什么点位和航线放一个文件</h2>
 * 它们是同一件事的两半（在地图上规划「去哪飞」），共用一个权限点、一个菜单组，
 * 而且航线的点常常直接从点位库里挑 —— 分两个文件只会让来回 import。
 */

// ---------------------------------------------------------------------------
// 点位库
// ---------------------------------------------------------------------------

export interface SurveyPointVO {
  id: number
  /** 固定 gcj02，由后端给出（见文件头说明） */
  coordinateSystem: string
  /** 客户给的点位编号，可空 */
  code?: string
  name: string
  lng: number
  lat: number
  /** 作业高度（米，相对起飞点） */
  alt?: number
  /** survey 巡检 / takeoff 起降 / dock 机巢 / other 其他 */
  category: string
  /** 类型中文名，后端给的 —— 前端不再维护一份映射 */
  categoryLabel: string
  remark?: string
  createByName?: string
  createTime?: string
  updateTime?: string
}

/** 点位查询条件，与后端 SurveyPointQueryParam 对应 */
export interface SurveyPointQuery {
  keyword?: string
  category?: string
  page?: number
  pageSize?: number
}

/** 点位保存参数；`id` 不传是新增、传了是编辑 */
export interface SurveyPointSaveParam {
  id?: number
  code?: string
  name: string
  /** GCJ-02：地图上点出来的那个位置，原样传回来 */
  lng: number
  lat: number
  alt?: number
  category?: string
  remark?: string
}

export function fetchPointPage(params: SurveyPointQuery) {
  return http.get<PageResult<SurveyPointVO>>('/point/list', params)
}

/**
 * 点位类型字典（编码 → 中文名）。
 *
 * <p>从后端取而不是前端写死：后端加了类型，前端下拉里就会自动出现，
 * 不会出现「新类型的点位建不出来」这种怪事。
 */
export function fetchPointCategories() {
  return http.get<Record<string, string>>('/point/categories')
}

export function addPoint(data: SurveyPointSaveParam) {
  return http.post<SurveyPointVO>('/point/add', data)
}

export function updatePoint(data: SurveyPointSaveParam) {
  return http.post<SurveyPointVO>('/point/update', data)
}

export function deletePoint(id: number) {
  // 后端是 POST + @RequestParam("id")，所以 id 走查询串而不是请求体
  return http.post<void>(`/point/delete?id=${encodeURIComponent(id)}`)
}

// ---------------------------------------------------------------------------
// 航线
// ---------------------------------------------------------------------------

export interface RoutePointVO {
  /** 顺序，从 1 开始（服务端按数组顺序生成） */
  seq: number
  lng: number
  lat: number
  alt?: number
  /** none 飞过 / photo 拍照 / hover 悬停 */
  action: string
  actionLabel: string
  hoverSeconds?: number
  /** 来自点位库时记着它的 id；地图上临时加的点为空 */
  pointId?: number
  pointName?: string
}

export interface RouteVO {
  id: number
  coordinateSystem: string
  name: string
  description?: string
  pointCount: number
  /** 总航程（米） */
  distanceM?: number
  createByName?: string
  createTime?: string
  updateTime?: string
  /** 只有详情接口会返回航点 */
  points?: RoutePointVO[]
}

export interface RouteQuery {
  keyword?: string
  page?: number
  pageSize?: number
}

/** 航点保存参数；**没有 seq** —— 顺序就是数组顺序 */
export interface RoutePointSaveParam {
  lng: number
  lat: number
  alt?: number
  action?: string
  hoverSeconds?: number
  pointId?: number
}

export interface RouteSaveParam {
  id?: number
  name: string
  description?: string
  /** 至少 2 个，按飞行顺序排列 */
  points: RoutePointSaveParam[]
}

export function fetchRoutePage(params: RouteQuery) {
  return http.get<PageResult<RouteVO>>('/route/list', params)
}

/** 航线详情，含航点（按飞行顺序） */
export function fetchRouteDetail(id: number) {
  return http.get<RouteVO>(`/route/${id}`)
}

/** 航点动作字典（编码 → 中文名），理由同点位类型 */
export function fetchRouteActions() {
  return http.get<Record<string, string>>('/route/actions')
}

export function addRoute(data: RouteSaveParam) {
  return http.post<RouteVO>('/route/add', data)
}

export function updateRoute(data: RouteSaveParam) {
  return http.post<void>('/route/update', data)
}

export function deleteRoute(id: number) {
  // 同 deletePoint：后端是 POST + @RequestParam
  return http.post<void>(`/route/delete?id=${encodeURIComponent(id)}`)
}
