import { http } from './request'

/**
 * 业务分析的数据接口（见 `docs/业务分析方案.md`）。
 *
 * <h2>三个指标为什么挤在一个接口里</h2>
 * 它们要一起读才有意义（「这周缺设备的积压多」+「现在还有活等了一天以上」= 该买了）。
 * 拆成三次请求会出现「归因是 10 秒前算的、积压是刚算的」这种时间错位，
 * 而且每个接口都要重复一遍区间参数校验。
 */

/** 一个归因及其占比 */
export interface ReasonShare {
  /** blocked_pilot 缺飞手 / blocked_drone 缺设备 / blocked_both 两者都缺 / idle 资源够没人派 */
  reason: string
  /**
   * 中文名。
   *
   * <p>由后端给（与 `DispatchBlockReason.label` 是同一处定义）——
   * 前端不重复维护一套翻译：哪天加了一个归因，漏改的只会是后端那一处，
   * 而不是「后端加了、前端忘了、页面上显示一个英文编码」。
   */
  label: string
  count: number
  percent: number
}

/** 积压时长的一个桶 */
export interface BacklogBucket {
  key: string
  label: string
  count: number
}

export interface DispatchBlockStats {
  /** 区间端点，后端按「本地日历」给的字符串 */
  from: string
  to: string
  /** 区间内的归因点总数（各归因占比的分母） */
  blockPoints: number
  /** 已按条数从多到少排好 */
  reasonShare: ReasonShare[]
  /** 区间内有采样的小时数 */
  sampledHours: number
  /** 区间一共多少小时；与 sampledHours 的差就是停机时段 */
  expectedHours: number
  /** 此刻待派发的任务总数（快照，不随区间变） */
  pendingTotal: number
  backlogAge: BacklogBucket[]
  /** 从创建到派发的中位时长（分钟）；区间内没有派发记录时为 null */
  medianMinutes: number | null
  /** 参与中位数的样本数 —— 太少就不要下结论 */
  dispatchedSamples: number
}

export interface RangeQuery {
  /** yyyy-MM-dd HH:mm:ss，不传后端给「最近 7 天」 */
  from?: string
  to?: string
}

/** 待派发积压的归因（三个指标一次拿全） */
export function fetchDispatchBlockStats(params: RangeQuery = {}) {
  return http.get<DispatchBlockStats>('/analytics/dispatch-block', params)
}

// ---------------------------------------------------------------------------
// 任务与告警概览（docs/业务分析方案.md 第一步）
// ---------------------------------------------------------------------------

/** 任务侧。比率与时长**没有样本时是 null**（界面显示「—」），不是 0 */
export interface OverviewTasks {
  /** 每天新建任务数，与 days 一一对应（缺口后端已补 0） */
  created: number[]
  createdTotal: number
  finished: number
  failed: number
  /** 完成 ÷（完成 + 失败），%；没有结束的活时为 null */
  finishRate: number | null
  onTimeBase: number
  onTimeRate: number | null
  durationBase: number
  avgMinutes: number | null
}

/** 某个级别的告警趋势线 */
export interface AlertSeries {
  /** critical / warning / info */
  severity: string
  label: string
  values: number[]
}

export interface OverviewAlerts {
  trend: AlertSeries[]
  total: number
  critical: number
  handled: number
  mttrMinutes: number | null
  /** 此刻未处置：**快照**，不看区间 */
  openNow: number
}

export interface AnalyticsOverview {
  from: string
  to: string
  /** 两张趋势图**共用**的横轴（区间内每一天，缺口也在） */
  days: string[]
  tasks: OverviewTasks
  alerts: OverviewAlerts
}

/** 任务与告警概览（六个指标一次拿全） */
export function fetchOverview(params: RangeQuery = {}) {
  return http.get<AnalyticsOverview>('/analytics/overview', params)
}

// ---------------------------------------------------------------------------
// 飞手与设备效率（docs/业务分析方案.md 第二步）
// ---------------------------------------------------------------------------

/**
 * 飞手榜的一行。
 *
 * <p>⚠️ 这一行里的数**不是同一个时间基准**算出来的，别相互除：
 * 工作量/按时率按「任务的结束时间」、无旁证占比按「任务的开始时间」（含还在飞的）、
 * 派发/拒绝按「流水时间」。各自的说明写在 `EfficiencyVO` 上。
 */
export interface PilotRow {
  pilotId: number
  name: string
  /** 派给他的次数（含他拒掉的）—— 拒绝率的分母 */
  offers: number
  refused: number
  refuseRate: number | null
  /** 区间内结束的活（完成 + 失败） */
  taskCount: number
  finished: number
  onTimeRate: number | null
  /** 区间内开过的活（含还在飞的）—— 无旁证占比的分母 */
  started: number
  noProof: number
  noProofRate: number | null
  hours: number | null
  sorties: number
}

/** 设备榜的一行 */
export interface DeviceRow {
  deviceId: number
  code: string
  name: string
  /** 当前工作状态（快照） */
  status: string
  inServiceDays: number
  /** 可飞时长 = 在役天数 × 每日可飞小时 —— 利用率的分母，单独列出来方便核对 */
  flyableHours: number
  hours: number | null
  sorties: number
  utilization: number | null
  alerts: number
  criticalAlerts: number
  faultRatePer100h: number | null
}

export interface Efficiency {
  from: string
  to: string
  rangeDays: number
  /** 每日可飞小时（配置项）—— 利用率的分母里有它，界面上要写出来 */
  flyableHoursPerDay: number
  pilots: PilotRow[]
  devices: DeviceRow[]
}

/** 飞手与设备效率（两个榜单一次拿全） */
export function fetchEfficiency(params: RangeQuery = {}) {
  return http.get<Efficiency>('/analytics/efficiency', params)
}

// ---------------------------------------------------------------------------
// 证据量与在线设备（docs/业务分析方案.md 第三步：预聚合日表）
// ---------------------------------------------------------------------------

/**
 * 证据量与活跃设备趋势。
 *
 * <p>历史那几天读的是**预聚合日表**，没算的那几天（通常是今天）由后端现场从明细补 ——
 * 所以报文里带了 `precomputedDays` / `liveDays`，界面上要说清「今天那个点还在涨」。
 */
export interface EvidenceTrend {
  from: string
  to: string
  days: string[]
  /** 每天多少条证据（只算有效的） */
  evidenceCounts: number[]
  /** 每天多少台设备活跃 */
  activeDevices: number[]
  /** 其中多少天来自预聚合表 */
  precomputedDays: number
  /** 多少天是当场从明细算的（正常只有今天） */
  liveDays: number
}

/** 证据量与活跃设备趋势 */
export function fetchEvidenceTrend(params: RangeQuery = {}) {
  return http.get<EvidenceTrend>('/analytics/evidence', params)
}

// ---------------------------------------------------------------------------
// 点位覆盖率（docs/业务分析方案.md 第四步）
// ---------------------------------------------------------------------------

/** 一个点位的覆盖情况 */
export interface CoveragePoint {
  /** 点位 id；快照里的旧点位已被删除时为 null */
  pointId: number | null
  code: string | null
  name: string
  /** 经度（GCJ-02，可直接画在地图上） */
  lng: number
  lat: number
  plannedTimes: number
  visitedTimes: number
  covered: boolean
}

/** 一条航线的覆盖情况 */
export interface RouteCoverage {
  name: string
  plannedPoints: number
  visitedPoints: number
  coverageRate: number | null
  taskCount: number
}

export interface Coverage {
  from: string
  to: string
  /** 固定 gcj02 */
  coordinateSystem: string
  /** 判定「到过」的半径（米） */
  radiusMeters: number
  taskCount: number
  /** 其中没配航线、因此没有计划点的任务数 */
  tasksWithoutRoute: number
  plannedPoints: number
  visitedPoints: number
  coverageRate: number | null
  routes: RouteCoverage[]
  points: CoveragePoint[]
}

/** 点位覆盖率 */
export function fetchCoverage(params: RangeQuery = {}) {
  return http.get<Coverage>('/analytics/coverage', params)
}

/**
 * 下载覆盖率 Excel。
 *
 * <p>不能直接给 `<a href>`：那条链接不带 Authorization 头，会被 401 挡回来。
 * 所以照设备导出的做法——带 token 取回 Blob，再交给 `utils/download` 存盘
 * （它还会处理「后端返回的是 JSON 错误」这种情况）。
 */
export function downloadCoverage(params: RangeQuery = {}): Promise<Blob> {
  return http.download('/analytics/coverage/export', params)
}
