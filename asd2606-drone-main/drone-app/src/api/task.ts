import { http, type PageResult } from './request'
import type { TaskAttachmentVO } from './attachment'

/** 任务，与后端 TaskVO 对应 */
export interface TaskVO {
  id: number
  /** 任务编号，形如 TK-20260918-001 */
  taskNo: string
  taskName: string
  /** P0 紧急 / P1 高 / P2 中 / P3 低 */
  priority: string
  /** pending 待派发 / dispatched 已派发 / executing 执行中 / finished 已完成 / failed 已失败 / cancelled 已取消 */
  status: string
  planStartTime?: string
  planEndTime?: string
  actualStartTime?: string
  actualEndTime?: string
  droneCode?: string
  pilotName?: string
  /** 任务内容 / 航线描述 */
  routeInfo?: string
  description?: string
  createByName?: string
  createTime?: string
  /**
   * 这条任务上还有多少条**未处理**的告警（卡片角标）。
   *
   * <p>口径是「未处理」（pending + handling）：角标回答「有什么要管的」，
   * 处理完就该消失。要「一共发生过几条」的话，展开后的清单里有完整的。
   *
   * <p>只有「我的任务」接口会返回这两个字段，管理端的任务总表里是空的。
   */
  alertCount?: number
  /** 未处理告警里 critical 的条数 —— 决定角标是红的还是黄的 */
  criticalAlertCount?: number
  /**
   * 执行依据：none 无 / declared 仅飞手声明 / confirmed 已有设备数据佐证 / stale 声明已过期。
   *
   * <p>它回答的是「这次执行<b>凭的是什么</b>」。收尾时要用它提示
   * 「本次只有你的声明，还没有设备数据」—— 完成不强制要求有旁证，
   * 但飞手要知道自己在承担什么。
   */
  evidenceState?: string
}

/** 地图上的一个点（GCJ-02，后端转好的，直接用） */
export interface MapPoint {
  lng: number
  lat: number
}

/** 航线上的一个航点（计划航线的形状，GCJ-02） */
export interface RoutePointVO {
  seq: number
  lng: number
  lat: number
  alt?: number
  /** none 飞过 / photo 拍照 / hover 悬停 */
  action: string
  actionLabel?: string
  hoverSeconds?: number
  pointName?: string
}

/** 计划航线（任务上的快照，坐标 GCJ-02） */
export interface PlannedRouteVO {
  name: string
  pointCount?: number
  distanceM?: number
  points?: RoutePointVO[]
}

/** 实际轨迹（已抽稀，坐标 GCJ-02） */
export interface TaskTrackVO {
  coordinateSystem: string
  rawCount?: number
  points?: Array<MapPoint & { observedAt?: string }>
}

/**
 * 现场打卡记录，与后端 TaskCheckinVO 对应（坐标 GCJ-02）。
 *
 * <p>它同时带**目标点**和**打卡点**两组坐标 —— 有用的信息是两者的差：
 * 离目标多远。地图上把两个点都画出来，别人一眼就能判断他到没到。
 */
export interface TaskCheckinVO {
  coordinateSystem: string
  id?: number
  /** 目标航点序号（从 1 开始） */
  seq: number
  targetLng: number
  targetLat: number
  lng: number
  lat: number
  /** 手机定位精度（米） */
  accuracyM?: number
  /** 离目标点位的距离（米，服务端算的） */
  deviationM: number
  /** 偏差是否超过阈值（200 米）—— 阈值由后端给，前端不再自己写一个 */
  farAway: boolean
  observedAt: string
  /** 是否离线补传上来的 */
  submittedLate: boolean
  reporterName?: string
  remark?: string
  /** 本次提交是不是「库里已经有同样的一条」（补传重发时会出现） */
  duplicated?: boolean
}

/**
 * 飞手端「我这条任务的地图」：一次请求给全画图与打卡要用的东西。
 *
 * <p>合成一个接口是因为飞手在野外点开一次就要看到全部，弱网下多一次请求就多一次转圈。
 */
export interface TaskMapVO {
  coordinateSystem: string
  taskId: number
  taskNo: string
  taskName: string
  status: string
  droneCode?: string
  plannedRoute?: PlannedRouteVO
  takeoffLng?: number
  takeoffLat?: number
  takeoffName?: string
  track?: TaskTrackVO
  checkins?: TaskCheckinVO[]
  /** 已经打过卡的不同航点数 */
  checkedPointCount?: number
  /** 计划航点数（没有航线时为 0） */
  totalPointCount?: number
}

/** 打卡入参。⚠️ 坐标传 GCJ-02（`uni.getLocation({type:'gcj02'})` 拿到的就是它） */
export interface TaskCheckinParam {
  taskId: number
  /** 目标航点序号（从 1 开始）；目标坐标由服务端从航线快照里取，前端传不了 */
  seq: number
  lng: number
  lat: number
  /** 打卡那一刻。**必填**：它同时是补传去重的键之一（同任务同航点同刻 = 同一次打卡） */
  observedAt: string
  /**
   * 上传坐标的坐标系：gcj02（小程序定位，默认）/ wgs84（浏览器 GPS）。
   *
   * <p>为什么要显式声明：小程序给的是地图那套 GCJ-02，浏览器给的是 GPS 原始值 WGS84，
   * 两者差 500 米 —— 服务端靠猜的话，猜错的表现是「位置差不多对」，最难发现。
   */
  coordinateSystem?: 'gcj02' | 'wgs84'
  /**
   * 只试算不落库：用来在**提交前**问一句「离目标 230 米，确定到了吗」。
   *
   * <p>距离必须由服务端算：客户端手里是手机/浏览器那套坐标，和航线快照不一定同系，
   * 自己算会凭空多出 500 米（这一版真踩到了）。
   */
  dryRun?: boolean
  accuracyM?: number
  remark?: string
}

/** 取我这条任务的地图数据（航线 + 起降点 + 轨迹 + 打卡记录） */
export function fetchMyTaskMap(taskId: number) {
  return http.get<TaskMapVO>(`/task/my/${taskId}/map`)
}

/**
 * 到点打卡。
 *
 * <p>偏差由服务端算（目标点取自任务的航线快照）—— 客户端算的那个数只用来
 * 在提交前提示一句「离目标 230 米，确定到了吗」，不作为结果展示。
 */
export function submitCheckin(data: TaskCheckinParam) {
  return http.post<TaskCheckinVO>('/task/checkin', data as unknown as Record<string, unknown>)
}

/** 「我的任务」的查询条件 */
export interface MyTaskQuery {
  keyword?: string
  status?: string
  page?: number
  pageSize?: number
}

/**
 * 查询派给我的任务。
 *
 * <p>注意**没有 pilotId 参数**：飞手 id 由服务端从登录态里取。
 * 前端就算传了也会被覆盖掉 —— 这是这个接口存在的意义，见后端 TaskController 的注释。
 */
export function fetchMyTasks(params: MyTaskQuery) {
  return http.get<PageResult<TaskVO>>('/task/my', { ...params })
}

/**
 * 开始执行。
 *
 * <p>成功后任务变「执行中」，同时后端会把设备从「已派出」改成「飞行中」，
 * 并通知能重新指派的人。
 *
 * <p>同样没有 pilotId —— 服务端会校验这条任务是不是派给当前登录人的，
 * 不是的话返回 403。
 */
export function startTask(taskId: number) {
  return http.post<TaskVO>('/task/start', { taskId })
}

/**
 * 拒绝执行。
 *
 * <p>拒绝后任务退回「待派发」、飞手与设备都释放，管理员可以直接重新指派别人。
 * 所以界面上要跟飞手说清楚：拒绝不是「这次不干」，而是「把这条任务退回去」。
 *
 * @param reason 拒绝原因，选填；会记进流转记录并一起推给管理员
 */
export function refuseTask(taskId: number, reason?: string) {
  return http.post<TaskVO>('/task/refuse', { taskId, reason })
}

/**
 * 报告的「手填 + 自动汇总」两半，与后端 TaskReportVO 对应。
 *
 * <p><b>飞手端目前没有报告查看页</b>（设计文档 10.2 把报告详情放在管理端），
 * 所以这个类型现在只用来描述「收尾时后端会算出来的东西」，
 * 留在这里是因为 {@link TaskFinishParam} 的字段与之对应、读代码时能对上。
 * 真要做飞手看自己报告的页面时，直接用它就行。
 */
export interface TaskReportVO {
  id: number
  version: number
  result: string
  resultLabel?: string
  summary?: string
  problemLog?: string
  flightHours?: number
  flightCount?: number
  reporterName?: string
  createTime?: string
  // ---- 以下都是自动汇总 ----
  taskNo?: string
  taskName?: string
  taskStatus?: string
  pilotName?: string
  droneCode?: string
  actualStartTime?: string
  actualEndTime?: string
  /** 按实际起止算出来的时长（小时）—— 界面上预填到「飞行时长」，让飞手确认或改 */
  computedHours?: number
  telemetryCount?: number
  dataFrom?: string
  dataTo?: string
  minBattery?: number
  maxAlt?: number
  evidenceState?: string
  evidenceLabel?: string
  /** true 表示这次执行**只有飞手的声明**、没有设备数据佐证（界面上要标出来） */
  onlyDeclared?: boolean
  alertCount?: number
  attachments?: TaskAttachmentVO[]
}

/** 收尾入参：完成与标记失败共用（失败时 problemLog 必填） */
export interface TaskFinishParam {
  taskId: number
  /** success 成功 / partial 部分完成；**标记失败不用传**（服务端写死 failed） */
  result?: string
  summary?: string
  problemLog?: string
  flightHours?: number
  flightCount?: number
  attachmentIds?: number[]
}

/**
 * 完成任务并提交报告。
 *
 * <p>一个接口收三条线：任务状态、设备与飞手资源、报告。所以它不是
 * 「提交一份表」那么简单 —— 成功之后飞手手上这条活就没了。
 */
export function finishTask(data: TaskFinishParam) {
  return http.post<TaskVO>('/task/finish', { ...data })
}

/**
 * 标记执行失败。
 *
 * <p>与完成的区别只在两点：必须填失败原因（`problemLog`），以及落地的状态不同。
 * 之所以要有这条出口：没有它，飞手遇到飞机故障只能谎报完成。
 */
export function failTask(data: TaskFinishParam) {
  return http.post<TaskVO>('/task/fail', { ...data })
}
