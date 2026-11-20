import { http, type PageResult } from './request'
import type { AlertVO } from './alert'
import type { TaskAttachmentVO } from './attachment'
import type { RouteVO } from './route'

/** 任务视图对象，与后端 TaskVO 对应 */
export interface TaskVO {
  id: number
  /** 任务编号，服务端生成，格式 TK-yyyyMMdd-序号 */
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
  /** 指派后才有的字段，创建出来时都是空的 */
  droneId?: number
  droneCode?: string
  pilotId?: number
  pilotName?: string
  routeInfo?: string
  /** 引用的航线模板 id，可空 */
  routeId?: number
  /**
   * 起降点，⚠️ 坐标是 **GCJ-02**（后端转好的，直接交给地图）。
   *
   * <p>注意它和别的坐标字段不一样：库里存的是 WGS84，但 VO 出来的是 GCJ-02 ——
   * 因为这个 VO 的用途就是「在地图上标一个点」。详见后端 `TaskVO` 的说明。
   */
  takeoffLng?: number
  takeoffLat?: number
  takeoffName?: string
  description?: string
  createBy?: number
  createByName?: string
  createTime?: string
  updateTime?: string
}

/**
 * 流转日志，与后端 TaskLogVO 对应。
 *
 * <p>后端刻意没把 taskId、operatorId 这类内部 ID 返给前端，
 * 前端也不该依赖它们 —— 要跳转到「某个人的任务」应该走查询条件，而不是拿 ID 拼 URL。
 */
export interface TaskLogVO {
  id: number
  /** create / dispatch / withdraw / start / finish / fail / cancel */
  action: string
  fromStatus?: string
  toStatus?: string
  operatorName?: string
  operatorRole?: string
  note?: string
  createTime?: string
}

/** 任务详情 = 任务本体 + 流转流水，时间轴用 logs 渲染 */
export interface TaskDetailVO {
  task: TaskVO
  logs: TaskLogVO[]
  /**
   * 本任务期间的告警（后端按 `task_id` **或**「设备 + 任务时间窗」聚合）。
   *
   * <p>为什么要时间窗：设备上报那一刻如果没有执行中的任务，`task_id` 是空的，
   * 但那些告警其实和这条任务有关。只按 `task_id` 查会漏掉它们。
   */
  alerts?: AlertVO[]
  /**
   * 这条任务**当时**的计划航线（任务里的快照，坐标 GCJ-02）。
   *
   * <p>它取自任务自己的快照，不是当前的航线模板 ——
   * 模板后来被改或被删，这条任务的「当时航线」都不会跟着变。
   * 为空表示创建时没选航线（临时任务直接填了一句文字）。
   */
  plannedRoute?: RouteVO
  /**
   * 现场打卡记录（飞手说自己去过哪）。
   *
   * <p>它是**自述**，不能当设备证据用 —— 复核时和轨迹放一起看：
   * 打卡点在轨迹附近就说得通，差几十公里就得问一句。
   */
  checkins?: TaskCheckinVO[]
}

/**
 * 看板上的一个位置点（当前位置与最近航迹都用它）。坐标是 GCJ-02。
 */
export interface TaskLivePoint {
  lng: number
  lat: number
  observedAt?: string
}

/**
 * 监控看板上的一条，与后端 TaskMonitorVO 对应。
 *
 * <p>一条记录同时回答四个问题（谁在飞、现在在哪、刚才怎么飞、打算怎么飞）——
 * 后端一次给全，是因为看板要「整行一起刷新」：
 * 分接口去拼，就会出现这一行的位置是 3 秒前、航线是 10 秒前。
 */
export interface TaskMonitorVO {
  coordinateSystem: string
  taskId: number
  taskNo: string
  taskName: string
  priority?: string
  pilotName?: string
  droneCode?: string
  actualStartTime?: string
  planEndTime?: string
  /** 超期与否由后端算（拿本机时间比，客户端时钟一偏就会一会儿红一会儿不红） */
  overdue: boolean
  overdueMinutes?: number
  alertCount?: number
  criticalAlertCount?: number
  /** 当前位置，为空表示还没有带坐标的数据（界面显示「暂无位置」而不是画在 0,0） */
  current?: TaskLivePoint
  /** 最近一小段航迹（已抽稀） */
  trail?: TaskLivePoint[]
  /** 当时的计划航线（快照） */
  plannedRoute?: RouteVO
  takeoffLng?: number
  takeoffLat?: number
  takeoffName?: string
}

/**
 * 现场打卡记录，与后端 TaskCheckinVO 对应。
 *
 * <p>坐标系是 GCJ-02。注意它同时带目标点和打卡点两组坐标 ——
 * 有用的信息是**两者的差**（离目标多远）。
 */
export interface TaskCheckinVO {
  coordinateSystem: string
  id?: number
  seq: number
  targetLng: number
  targetLat: number
  lng: number
  lat: number
  accuracyM?: number
  /** 离目标点位的距离（米，服务端算的） */
  deviationM: number
  /** 偏差是否超过阈值（200 米）—— 阈值由后端给，前端不再自己判一遍 */
  farAway: boolean
  observedAt: string
  reportedAt?: string
  /** 离线补传上来的（收到时刻明显晚于打卡时刻） */
  submittedLate: boolean
  reporterName?: string
  remark?: string
}

/** 任务查询条件，与后端 TaskQueryParam 对应 */
export interface TaskQuery {
  /** 模糊匹配 任务编号 / 任务名称 */
  keyword?: string
  status?: string
  priority?: string
  /** 飞手 ID：分析页的「飞手榜」下钻过来时用（列表页没有这一项筛选控件） */
  pilotId?: number
  /**
   * 实际结束时间区间（下钻用，见 docs/业务分析方案.md 三点五）。
   *
   * <p>为什么是这一列：分析页的完成率/按时率/平均时长都按它落窗，
   * 列表按同一列筛，条数才能和那个数对上。
   */
  endFrom?: string
  endTo?: string
  page?: number
  pageSize?: number
}

/**
 * 任务创建参数，与后端 TaskCreateParam 对应。
 *
 * <p>注意这里<b>没有</b> status、createBy、pilot、drone ——
 * 状态由服务端固定为待派发、创建人取自登录态、指派谁和用哪台设备是「派发」环节的事。
 * 后端参数对象里压根没有这些字段，想传也传不进去。
 */
export interface TaskCreateParam {
  taskName: string
  priority: string
  /** 格式 yyyy-MM-dd HH:mm:ss，后端用 @JsonFormat 按这个模式解析 */
  planStartTime: string
  planEndTime: string
  routeInfo?: string
  /** 引用的航线模板 id；选了它，后端会存一份快照，之后模板改动不影响这条任务 */
  routeId?: number
  /** 起降点，⚠️ 坐标要传 **GCJ-02**（地图上点出来的那个值，原样传回） */
  takeoffLng?: number
  takeoffLat?: number
  takeoffName?: string
  description?: string
  /** 已知晓禁飞区风险、仍要创建；命中时必填 true（见 ZoneConfirmDialog） */
  acknowledgedZones?: boolean
  /** 已知晓风险的原因，命中并确认时必填，会记进流转流水 */
  zoneReason?: string
}

/** 监控看板：当前所有执行中任务的位置与状态 */
export function fetchMonitor() {
  return http.get<TaskMonitorVO[]>('/task/monitor')
}

/** 分页查询任务（支持关键字 / 状态 / 优先级筛选） */
export function fetchTaskPage(params: TaskQuery) {
  return http.get<PageResult<TaskVO>>('/task/list', params)
}

/** 查询任务详情，含状态流转流水 */
export function fetchTaskDetail(id: number) {
  return http.get<TaskDetailVO>(`/task/${id}`)
}

/** 创建任务（状态由服务端固定为「待派发」） */
export function createTask(data: TaskCreateParam) {
  return http.post<TaskVO>('/task/create', data)
}

/**
 * 派发参数。
 *
 * <p>没有 status —— 派发后状态由服务端按状态机推导（一定是「已派发」），
 * 和后端 TaskDispatchParam 保持一致。
 */
export interface TaskDispatchParam {
  taskId: number
  pilotId: number
  droneId: number
  /** 附言，选填，会记进流转流水 */
  note?: string
  /** 已知晓禁飞区风险、仍要派发；命中时必填 true（见 ZoneConfirmDialog） */
  acknowledgedZones?: boolean
  /** 已知晓风险的原因，命中并确认时必填，会记进流转流水 */
  zoneReason?: string
}

/** 撤回派发参数 */
export interface TaskWithdrawParam {
  taskId: number
  /** 撤回原因，选填；即使不填，服务端也会自动记下原指派是谁和哪台设备 */
  note?: string
}

/**
 * 派发下拉的一个选项。
 *
 * <p>后端只给 id 和拼好的文案，前端不自己拼字符串 ——
 * 「张伟 · 13800001001」的格式只在服务端定义一次，不会前后端各写一遍然后跑偏。
 */
export interface DispatchOption {
  id: number
  label: string
}

/**
 * 派发弹窗的下拉数据。
 *
 * <p>单独一个接口而不是复用员工、设备列表接口，是为了守住权限边界：
 * 派发只需要 task:dispatch，不该顺带要求 staff:list 和 device:list。
 */
export interface DispatchOptions {
  pilots: DispatchOption[]
  drones: DispatchOption[]
}

/** 派发任务：指定飞手与设备，返回派发后的任务 */
export function dispatchTask(data: TaskDispatchParam) {
  return http.post<TaskVO>('/task/dispatch', data)
}

/** 撤回派发：任务退回「待派发」，指派关系清空 */
export function withdrawTask(data: TaskWithdrawParam) {
  return http.post<TaskVO>('/task/withdraw', data)
}

/** 查询派发弹窗的下拉数据（可派的飞手 + 可用的设备） */
export function fetchDispatchOptions() {
  return http.get<DispatchOptions>('/task/dispatch-options')
}

/**
 * 任务报告：飞手手填的部分 + 系统自动汇总的部分。
 *
 * <p>两半的分工很要紧：
 * <ul>
 *   <li><b>手填</b>（结论 / 总结 / 问题 / 时长 / 架次）是<b>主观的</b>，
 *       等于飞手的承诺 —— 也正是最需要被验证的那句话</li>
 *   <li><b>自动汇总</b>（飞了多久、收到多少条设备数据、最低电量、期间几条告警）
 *       是系统从证据里现算出来的，<b>不靠飞手填</b>，所以才是报告里最可信的部分</li>
 * </ul>
 *
 * <p>`onlyDeclared` 为 true 表示这次执行**只有飞手的声明**、没有任何设备数据佐证。
 * 完成并不强制要求有旁证（强制会把「图传断了」变成「飞手无法收工」），
 * 但界面要把它标出来。
 */
export interface TaskReportVO {
  id: number
  /** 版本号。报告可修订，每改一次 +1，旧版本保留 */
  version: number
  result: string
  resultLabel?: string
  summary?: string
  problemLog?: string
  flightHours?: number
  flightCount?: number
  reporterName?: string
  createTime?: string

  taskNo?: string
  taskName?: string
  taskStatus?: string
  pilotName?: string
  droneCode?: string
  actualStartTime?: string
  actualEndTime?: string
  /** 按实际起止算出来的时长（小时） */
  computedHours?: number
  telemetryCount?: number
  dataFrom?: string
  dataTo?: string
  minBattery?: number
  maxAlt?: number
  evidenceState?: string
  evidenceLabel?: string
  onlyDeclared?: boolean
  alertCount?: number
  attachments?: TaskAttachmentVO[]
}

/**
 * 查某条任务的报告。
 *
 * <p>任务没收尾时后端会返回「这条任务还没有报告」—— 所以界面上
 * 只在已完成 / 已失败的任务上显示「报告」入口，不让人点了才知道。
 */
export function fetchTaskReport(taskId: number) {
  return http.get<TaskReportVO>(`/task/report/${taskId}`)
}

/** 轨迹上的一个点。**坐标已经是 GCJ-02**，可以直接交给地图去画 */
export interface TaskTrackPoint {
  lng: number
  lat: number
  observedAt?: string
}

/**
 * 任务轨迹。
 *
 * <p>⚠️ **注意坐标系**：`points` 里的坐标是 **GCJ-02**（高德/腾讯那一套），
 * 不是库里存的 WGS84。后端在返回前转过一次，就是为了让地图能直接用。
 * **别拿它和数据库里的经纬度比** —— 那会差 500 米左右。
 */
export interface TaskTrackVO {
  /** 固定 `gcj02`，告诉调用方这批坐标是哪种 */
  coordinateSystem: string
  /** 原始点位数（抽稀前）；和 points 长度对比能看出抽掉了多少 */
  rawCount: number
  /** 抽稀后的轨迹点，按时间升序 */
  points: TaskTrackPoint[]
}

/**
 * 查某条任务的轨迹。
 *
 * <p>数据不是新采的：设备每秒报的经纬度早就存在证据表的**报文原文**里，
 * 这个接口把它取出来、抽稀、转成地图能直接用的坐标系。
 */
export function fetchTaskTrack(taskId: number) {
  return http.get<TaskTrackVO>(`/task/${taskId}/track`)
}
