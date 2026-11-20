<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import {
  createTask,
  dispatchTask,
  fetchDispatchOptions,
  fetchTaskDetail,
  fetchTaskPage,
  fetchTaskReport,
  fetchTaskTrack,
  withdrawTask,
  type DispatchOptions,
  type TaskCreateParam,
  type TaskDetailVO,
  type TaskQuery,
  type TaskReportVO,
  type TaskTrackVO,
  type TaskVO,
} from '@/api/task'
import AttachmentList from '@/components/AttachmentList.vue'
import TrackMap from '@/components/TrackMap.vue'
import GeoMap from '@/components/GeoMap.vue'
import ZoneConfirmDialog from '@/components/ZoneConfirmDialog.vue'
import type { GeoMarker, GeoPoint } from '@/utils/geo'
import { fetchRoutePage, type RouteVO } from '@/api/route'
import { checkPlanZones, checkTaskZones, type ZoneHitVO } from '@/api/zone'
import { onTaskMessage } from '@/composables/useTaskSocket'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()

/**
 * 收到任务推送就重查列表。
 *
 * <p>为什么必须有这一步：列表是进页面时拉一次的，而飞手可能在管理员看着列表的时候
 * 点了「开始执行」或「拒绝执行」—— 不重查的话，页面上这条还写着「已派发」，
 * 旁边还挂着一个「撤回」按钮，点下去只会得到一个报错
 * （撤回只允许从「已派发」发起，而它其实已经是「执行中」了）。
 *
 * <p>这也是文档里那条原则的落地：**推送只负责「快」，数据以查询为准**。
 * 推送过来只当「有变化了」的信号，真正显示的还是接口返回的内容。
 */
let unsubscribe: (() => void) | null = null

onMounted(() => {
  // 先吃 URL 上的下钻参数，再拉数据 —— 顺序反了就会先查一遍全量、再查一遍带条件的
  applyQueryFromUrl()
  loadData()
  unsubscribe = onTaskMessage(() => {
    loadData()
  })
})

onUnmounted(() => {
  // 一定要退订：否则页面销毁后回调还在，而且监听器会一直堆在集合里
  if (unsubscribe) {
    unsubscribe()
    unsubscribe = null
  }
})

const loading = ref(false)
const list = ref<TaskVO[]>([])
const total = ref(0)

const query = reactive<TaskQuery>({
  keyword: '',
  status: '',
  priority: '',
  page: 1,
  pageSize: 10,
})

/**
 * 「实际结束时间」区间（筛选栏里那个日期控件）。
 *
 * <p>它不是给日常用的，是给**下钻**用的：分析页的完成率点一下跳过来，
 * 带上这个区间，条数才能和那个数对上（见 docs/业务分析方案.md 三点五）。
 * 所以列表页必须能按它筛 —— 否则「复算」这句就是空话。
 */
const endRange = ref<[string, string] | null>(null)

/**
 * 下钻带进来的参数（`/task?status=finished&endFrom=…&endTo=…&pilotId=…`）。
 *
 * <p>`pilotId` 是飞手榜下钻用的：列表页没有这一项筛选控件，
 * 但它会跟着「重置」一起清掉，不会变成一个看不见又甩不掉的隐形条件。
 */
const route = useRoute()

function applyQueryFromUrl() {
  const q = route.query
  if (typeof q.status === 'string') query.status = q.status
  if (typeof q.pilotId === 'string' && q.pilotId) query.pilotId = Number(q.pilotId)
  if (typeof q.endFrom === 'string' && q.endFrom) {
    endRange.value = [q.endFrom, typeof q.endTo === 'string' ? q.endTo : '']
  }
}

/**
 * 状态与优先级的字典写在前端。
 *
 * <p>它们是相对固定的业务枚举（状态机是代码里定死的几个流转节点），
 * 不像角色那样可以随时新增，所以为它们单独开一个字典接口不划算；
 * 真要调整时前后端一起改。
 *
 * <p>展示映射与 docs/任务业务设计.md 里的状态机保持一一对应。
 */
const priorityOptions = [
  { value: 'P0', label: 'P0 紧急' },
  { value: 'P1', label: 'P1 高' },
  { value: 'P2', label: 'P2 中' },
  { value: 'P3', label: 'P3 低' },
]

const statusOptions = [
  { value: 'pending', label: '待派发' },
  { value: 'dispatched', label: '已派发' },
  { value: 'executing', label: '执行中' },
  { value: 'finished', label: '已完成' },
  { value: 'failed', label: '已失败' },
  { value: 'cancelled', label: '已取消' },
]

/** 流转动作的展示名，时间轴上用 */
const actionOptions = [
  { value: 'create', label: '创建任务' },
  { value: 'dispatch', label: '派发任务' },
  { value: 'withdraw', label: '撤回派发' },
  { value: 'expire', label: '超时释放' },
  { value: 'start', label: '开始执行' },
  { value: 'finish', label: '执行完成' },
  { value: 'fail', label: '执行失败' },
  { value: 'cancel', label: '取消任务' },
]

/** 未知编码原样显示 —— 后端将来加了新状态，这里不至于显示空白 */
function labelOf(options: { value: string; label: string }[], value?: string) {
  return options.find((item) => item.value === value)?.label ?? value ?? '—'
}

function priorityTone(priority?: string) {
  switch (priority) {
    case 'P0':
      return 'red'
    case 'P1':
      return 'amber'
    case 'P2':
      return 'cyan'
    default:
      return 'grey'
  }
}

/**
 * 告警严重级别 → 标签配色。
 *
 * <p>与 `priorityTone` 是两套配色，别混：任务那套是「计划有多重要」，
 * 告警这套是「此刻有多紧迫」。文案由后端给（`severityLabel`），
 * 颜色是前端的事 —— 和告警中心页用同一套映射。
 */
function severityTone(severity?: string) {
  switch (severity) {
    case 'critical':
      return 'red'
    case 'warning':
      return 'amber'
    default:
      return 'grey'
  }
}

function statusTone(status?: string) {
  switch (status) {
    case 'pending':
      return 'amber'
    case 'dispatched':
      return 'cyan'
    case 'executing':
      return 'violet'
    case 'finished':
      return 'green'
    case 'failed':
      return 'red'
    default:
      return 'grey'
  }
}

// ---------------------------------------------------------------------------
// 时间处理
// ---------------------------------------------------------------------------
/**
 * 后端用 @JsonFormat("yyyy-MM-dd HH:mm:ss") 收发时间，浏览器原生
 * datetime-local 控件的值是 "2026-09-19T09:00"（带 T、没有秒），两者得转一道。
 */
function toApiTime(value: string) {
  if (!value) return ''
  const normalized = value.replace('T', ' ')
  // 控件可能带秒（step 设置不同），只有到分钟时才补
  return normalized.length === 16 ? `${normalized}:00` : normalized
}

/** 列表里去掉秒和年份，省出横向空间；完整值挂在 title 上，鼠标悬停能看到 */
function fmtShortTime(value?: string) {
  return value ? value.slice(5, 16) : '—'
}

function fmtTime(value?: string) {
  return value ? value.slice(0, 16) : '—'
}

const totalPages = computed(() =>
  Math.max(1, Math.ceil(total.value / (query.pageSize ?? 10))),
)

const currentPage = computed(() => query.page ?? 1)

// ---------------------------------------------------------------------------
// 列表
// ---------------------------------------------------------------------------
async function loadData() {
  loading.value = true
  try {
    const result = await fetchTaskPage({
      ...query,
      endFrom: endRange.value?.[0],
      endTo: endRange.value?.[1],
    })
    list.value = result.list
    total.value = result.total
  } catch {
    // 错误提示由 axios 响应拦截器统一处理
    list.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

function handleSearch() {
  query.page = 1
  loadData()
}

function handleReset() {
  query.keyword = ''
  query.status = ''
  query.priority = ''
  // 「重置」要连下钻带进来的隐形条件一起清掉，否则用户看着筛选项是空的、结果却少
  query.pilotId = undefined
  endRange.value = null
  query.page = 1
  loadData()
}

function handlePageChange(page: number) {
  if (page < 1 || page > totalPages.value) return
  query.page = page
  loadData()
}

// ---------------------------------------------------------------------------
// 新建任务
// ---------------------------------------------------------------------------
const formVisible = ref(false)
const formSaving = ref(false)

/** 表单里的时间保持 datetime-local 的原生格式，提交前才转成后端要的样子 */
const form = reactive({
  taskName: '',
  priority: 'P2',
  planStartTime: '',
  planEndTime: '',
  routeInfo: '',
  description: '',
  /** 引用的航线模板；不选就还是「一句话描述」的老做法 */
  routeId: undefined as number | undefined,
  /** 起降点，地图上点出来的（GCJ-02），原样提交 */
  takeoffLng: undefined as number | undefined,
  takeoffLat: undefined as number | undefined,
  takeoffName: '',
})

function resetForm() {
  form.taskName = ''
  form.priority = 'P2'
  form.planStartTime = ''
  form.planEndTime = ''
  form.routeInfo = ''
  form.description = ''
  form.routeId = undefined
  form.takeoffLng = undefined
  form.takeoffLat = undefined
  form.takeoffName = ''
}

// ---------------------------------------------------------------------------
// 建任务时选航线 + 在图上点起降点（地图接入方案 第二步）
// ---------------------------------------------------------------------------

/** 可选航线（一次多取一些：选航线本来就希望「都在眼前」） */
const routeOptions = ref<RouteVO[]>([])
const routesLoading = ref(false)

async function loadRouteOptions() {
  routesLoading.value = true
  try {
    const result = await fetchRoutePage({ pageSize: 200 })
    routeOptions.value = result.list
  } catch {
    routeOptions.value = []
  } finally {
    routesLoading.value = false
  }
}

/** 选中的那条航线（用来在下方地图上预览它的形状） */
const pickedRoute = computed(() => routeOptions.value.find((r) => r.id === form.routeId) || null)

/**
 * 航线预览。
 *
 * <p>列表接口不带航点，所以这里要用选择框里已有的信息先画不了线 ——
 * 干脆不画：等后端返回详情再预览太重，而「哪条航线」由名字已经说清楚了。
 * 真要看形状，去「航线管理」里看。
 */
const routeHints = computed(() => {
  const route = pickedRoute.value
  if (!route) return ''
  const meters = route.distanceM
  // 按量级换单位：几十米的短航线说成「0.0 km」等于没说
  const distance =
    meters == null ? '航程未知' : meters < 1000 ? `${meters} 米` : `${(meters / 1000).toFixed(2)} 公里`
  return `${route.pointCount} 个点 · ${distance}`
})

const hasTakeoff = computed(() => form.takeoffLng !== undefined && form.takeoffLat !== undefined)

const takeoffMarker = computed<GeoMarker[]>(() =>
  hasTakeoff.value
    ? [
        {
          lng: form.takeoffLng as number,
          lat: form.takeoffLat as number,
          label: form.takeoffName || '起降点',
          tone: 'takeoff',
        },
      ]
    : [],
)

/** 起降点地图的视角：选过就在那儿，否则北京兜底 */
const takeoffCenter = computed<GeoPoint>(() =>
  hasTakeoff.value
    ? { lng: form.takeoffLng as number, lat: form.takeoffLat as number }
    : { lng: 116.397, lat: 39.909 },
)

function handlePickTakeoff(point: GeoPoint) {
  form.takeoffLng = Number(point.lng.toFixed(6))
  form.takeoffLat = Number(point.lat.toFixed(6))
}

function clearTakeoff() {
  form.takeoffLng = undefined
  form.takeoffLat = undefined
  form.takeoffName = ''
}

function openCreateDialog() {
  resetForm()
  formVisible.value = true
  void loadRouteOptions()
}

/**
 * 提交前的本地校验。
 *
 * <p>这些规则服务端也有一份（Service.validate），这里再挡一遍纯粹是为了
 * 少发一次注定失败的请求、并让用户立刻看到问题在哪。
 * 真正的底线在服务端 —— 绕过前端直接调接口，一样会被拒。
 */
function validateForm(): string | null {
  if (!form.taskName.trim()) return '请填写任务名称'
  if (form.taskName.trim().length > 100) return '任务名称不能超过 100 个字'
  if (!form.planStartTime || !form.planEndTime) return '请填写计划起止时间'

  // datetime-local 的值是定宽的 yyyy-MM-ddTHH:mm，字典序即时间序，
  // 直接比字符串即可，不必构造 Date 去踩解析格式的坑
  if (form.planEndTime < form.planStartTime) return '计划结束时间不能早于开始时间'
  return null
}

async function submitForm() {
  const invalid = validateForm()
  if (invalid) {
    ElMessage.warning(invalid)
    return
  }

  // 提交前先问一次禁飞区。命中就把「撞的是哪几块」摆到人面前，
  // 让他确认 + 写原因；没命中就直接建。
  // 这一步**只查不写**，真正的底线在创建接口那一步（它自己会再查一遍）——
  // 只靠这个预检等于「绕过前端就能免检」
  const hits = await checkPlanZones({
    routeId: form.routeId,
    takeoffLng: form.takeoffLng,
    takeoffLat: form.takeoffLat,
  }).catch(() => [] as ZoneHitVO[])
  if (hits.length > 0) {
    askZoneConfirm(hits, '这条计划命中禁飞区', '已知晓，继续创建', (reason) => doCreate(true, reason))
    return
  }
  await doCreate(false, undefined)
}

/** 真正提交创建。{@code acknowledged} 为真时代表用户已经看过命中提示并写了原因 */
async function doCreate(acknowledged: boolean, reason?: string) {
  const payload: TaskCreateParam = {
    taskName: form.taskName.trim(),
    priority: form.priority,
    planStartTime: toApiTime(form.planStartTime),
    planEndTime: toApiTime(form.planEndTime),
    routeInfo: form.routeInfo.trim() || undefined,
    description: form.description.trim() || undefined,
    routeId: form.routeId,
    takeoffLng: form.takeoffLng,
    takeoffLat: form.takeoffLat,
    takeoffName: form.takeoffName.trim() || undefined,
    acknowledgedZones: acknowledged || undefined,
    zoneReason: reason,
  }

  formSaving.value = true
  try {
    const created = await createTask(payload)
    ElMessage.success(`创建成功，编号 ${created.taskNo}，可前往派发`)
    formVisible.value = false
    // 新建的任务按时间倒序排在第一页第一条，回到第 1 页才能看到它
    query.page = 1
    await loadData()
  } catch {
    // 错误提示由 axios 响应拦截器统一处理
  } finally {
    formSaving.value = false
  }
}

// ---------------------------------------------------------------------------
// 禁飞区命中确认（建任务与派发两处共用）
// ---------------------------------------------------------------------------
const zoneDialogVisible = ref(false)
const zoneHits = ref<ZoneHitVO[]>([])
const zoneDialogTitle = ref('命中禁飞区')
const zoneConfirmText = ref('已知晓，继续')

/** 确认之后要重放的那次提交（带着原因） */
let pendingZoneSubmit: ((reason: string) => Promise<void>) | null = null

function askZoneConfirm(
  hits: ZoneHitVO[],
  title: string,
  confirmText: string,
  replay: (reason: string) => Promise<void>,
) {
  zoneHits.value = hits
  zoneDialogTitle.value = title
  zoneConfirmText.value = confirmText
  pendingZoneSubmit = replay
  zoneDialogVisible.value = true
}

async function onZoneConfirmed(reason: string) {
  const replay = pendingZoneSubmit
  pendingZoneSubmit = null
  if (replay) {
    await replay(reason)
  }
}

// ---------------------------------------------------------------------------
// 派发 / 撤回派发
// ---------------------------------------------------------------------------
const dispatchVisible = ref(false)
const optionsLoading = ref(false)
const dispatchSaving = ref(false)
const options = ref<DispatchOptions>({ pilots: [], drones: [] })

/** 当前正在派发的任务，弹窗标题与时间段提示都用它 */
const dispatchTarget = ref<TaskVO | null>(null)

/**
 * 两个下拉的 v-model 初始值是空字符串而不是 undefined ——
 * 原生 select 配一个「请选择」占位项时，空字符串最好使；
 * 提交前再转成数字发给后端。
 */
const dispatchForm = reactive({
  pilotId: '' as number | '',
  droneId: '' as number | '',
  note: '',
})

async function openDispatchDialog(task: TaskVO) {
  dispatchTarget.value = task
  dispatchForm.pilotId = ''
  dispatchForm.droneId = ''
  dispatchForm.note = ''
  dispatchVisible.value = true

  // 下拉数据每次打开都重新拉：任务可能已经搁置了一会儿，
  // 期间别的操作可能把某台设备改成维护中，用缓存会选到一个已经不可用的资源
  optionsLoading.value = true
  options.value = { pilots: [], drones: [] }
  try {
    options.value = await fetchDispatchOptions()
    // 空下拉要主动说一句，否则用户对着一片空白不知道是没数据还是没加载出来的
    if (options.value.pilots.length === 0) {
      ElMessage.warning('当前没有可派发的飞手（需要账号启用且拥有飞手角色）')
    } else if (options.value.drones.length === 0) {
      ElMessage.warning('当前没有可派发的设备（设备需处于「待命」状态）')
    }
  } catch {
    // 错误提示由 axios 响应拦截器统一处理
  } finally {
    optionsLoading.value = false
  }
}

async function submitDispatch() {
  if (!dispatchTarget.value) return
  if (!dispatchForm.pilotId) {
    ElMessage.warning('请选择飞手')
    return
  }
  if (!dispatchForm.droneId) {
    ElMessage.warning('请选择设备')
    return
  }

  // 派发前**再**查一次禁飞区：任务可能是上周建的，而区域是昨天才画的，
  // 或者今天 8 点起进入临时管制 —— 这两次校验的结果可以不一样
  const taskId = dispatchTarget.value.id
  const hits = await checkTaskZones(taskId).catch(() => [] as ZoneHitVO[])
  if (hits.length > 0) {
    askZoneConfirm(hits, '这条任务的计划命中禁飞区', '已知晓，继续派发', (reason) => doDispatch(true, reason))
    return
  }
  await doDispatch(false, undefined)
}

async function doDispatch(acknowledged: boolean, reason?: string) {
  if (!dispatchTarget.value) return

  dispatchSaving.value = true
  try {
    const updated = await dispatchTask({
      taskId: dispatchTarget.value.id,
      pilotId: Number(dispatchForm.pilotId),
      droneId: Number(dispatchForm.droneId),
      note: dispatchForm.note.trim() || undefined,
      acknowledgedZones: acknowledged || undefined,
      zoneReason: reason,
    })
    ElMessage.success(`已派发给 ${updated.pilotName}，设备 ${updated.droneCode}`)
    dispatchVisible.value = false
    await loadData()
  } catch {
    // 时间窗冲突这类错误由响应拦截器弹出提示，这里保持弹窗打开让用户改选
  } finally {
    dispatchSaving.value = false
  }
}

async function handleWithdraw(task: TaskVO) {
  let note = ''
  try {
    const result = await ElMessageBox.prompt(
      `撤回后「${task.taskNo}」会退回「待派发」，指派的飞手与设备将被清空。可以顺手填一句原因，会记进流转记录。`,
      '撤回派发',
      {
        confirmButtonText: '确认撤回',
        cancelButtonText: '取消',
        inputPlaceholder: '选填，如：飞手当天临时调班',
        inputValue: '',
        type: 'warning',
      },
    )
    note = result.value ?? ''
  } catch {
    // 用户点了取消
    return
  }

  try {
    await withdrawTask({ taskId: task.id, note: note.trim() || undefined })
    ElMessage.success('已撤回派发，任务回到「待派发」')
    await loadData()
  } catch {
    // 错误提示由 axios 响应拦截器统一处理
  }
}

// ---------------------------------------------------------------------------
// 报告（收尾后的交付物）
// ---------------------------------------------------------------------------
const reportVisible = ref(false)
const reportLoading = ref(false)
const report = ref<TaskReportVO | null>(null)

/**
 * 打开报告弹窗。
 *
 * <p>只从「已完成 / 已失败」的任务进入 —— 任务没收尾就没有报告，
 * 后端会返回「这条任务还没有报告」。与其让人点了才知道，
 * 不如入口就不显示（和列表里其他操作的显隐同一个思路）。
 */
async function openReport(task: TaskVO) {
  report.value = null
  reportLoading.value = true
  reportVisible.value = true
  try {
    report.value = await fetchTaskReport(task.id)
  } catch {
    // 拉不到就关掉，别停在一个空壳弹窗上
    reportVisible.value = false
  } finally {
    reportLoading.value = false
  }
}

// ---------------------------------------------------------------------------
// 详情（含流转时间轴）
// ---------------------------------------------------------------------------
const detailVisible = ref(false)
const detailLoading = ref(false)
const detail = ref<TaskDetailVO | null>(null)

/**
 * 飞行轨迹。
 *
 * <p>与详情**分开请求**：轨迹可能有几百个点，混进详情接口会让那个本来就
 * 不小的响应再胖一圈；而且轨迹是后画的，不必挡住详情弹窗的打开。
 */
const track = ref<TaskTrackVO | null>(null)

async function openDetail(task: TaskVO) {
  detail.value = null
  detailLoading.value = true
  detailVisible.value = true
  try {
    detail.value = await fetchTaskDetail(task.id)
    // 轨迹单独拉，**不 await**：它失败或慢都不该影响详情打开
    track.value = null
    void loadTrack(task.id)
  } catch {
    // 拉不到详情就把弹窗关掉，免得停在一个空壳上
    detailVisible.value = false
  } finally {
    detailLoading.value = false
  }
}

/** 拉轨迹。失败只记日志、不弹错 —— 地图画不出来不该盖住「详情已打开」这件事 */
async function loadTrack(taskId: number) {
  try {
    track.value = await fetchTaskTrack(taskId)
  } catch (e) {
    console.warn('任务轨迹加载失败（不影响详情）', e)
  }
}

/** 时间轴上的状态变化文案：创建时没有「变更前状态」，单独说一句 */
function transitionText(from?: string, to?: string) {
  const toLabel = labelOf(statusOptions, to)
  if (!from) return `创建为「${toLabel}」`
  return `${labelOf(statusOptions, from)} → ${toLabel}`
}

/**
 * 附言的展示文案。
 *
 * <p>创建流水服务端写的附言就是「创建任务」，和上面的动作名一模一样，
 * 再显示一遍读起来像重复了，这种情况直接不显示。
 * 其它动作的附言（撤回原因、失败原因）是真正补充信息，照常显示。
 */
function noteText(log: { action: string; note?: string }) {
  const note = log.note?.trim()
  return note && note !== labelOf(actionOptions, log.action) ? note : ''
}
</script>

<template>
  <div>
    <!-- ============ 检索栏 ============ -->
    <div class="card filter">
      <label class="search">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round">
          <circle cx="11" cy="11" r="6.2" /><path d="m19.5 19.5-3.6-3.6" />
        </svg>
        <input
          v-model="query.keyword"
          type="text"
          placeholder="搜索任务编号 / 名称"
          @keyup.enter="handleSearch"
        />
      </label>

      <select v-model="query.status" class="select">
        <option value="">全部状态</option>
        <option v-for="item in statusOptions" :key="item.value" :value="item.value">{{ item.label }}</option>
      </select>

      <select v-model="query.priority" class="select">
        <option value="">全部优先级</option>
        <option v-for="item in priorityOptions" :key="item.value" :value="item.value">{{ item.label }}</option>
      </select>

      <!-- 按「实际结束时间」筛：分析页下钻过来时用，也可以自己选 -->
      <el-date-picker
        v-model="endRange"
        type="datetimerange"
        value-format="YYYY-MM-DD HH:mm:ss"
        start-placeholder="实际结束时间从"
        end-placeholder="到"
        range-separator="~"
        class="filter-range"
      />

      <button class="btn btn--primary" type="button" @click="handleSearch">查询</button>
      <button class="btn btn--ghost" type="button" @click="handleReset">重置</button>
    </div>

    <!-- ============ 列表 ============ -->
    <div class="card">
      <div class="card__head">
        <div class="card__title">
          任务列表
          <small>共 {{ total }} 条记录，最新创建的排在最前</small>
        </div>
        <div class="card__tools">
          <button
            v-if="auth.hasPermission('task:add')"
            class="btn btn--primary"
            type="button"
            @click="openCreateDialog"
          >
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round">
              <path d="M12 5v14M5 12h14" />
            </svg>
            新建任务
          </button>
        </div>
      </div>

      <div class="table-wrap">
        <table>
          <thead>
            <tr>
              <th>任务编号</th>
              <th>任务名称</th>
              <th>优先级</th>
              <th>状态</th>
              <th>计划时间</th>
              <th>飞手</th>
              <th>设备</th>
              <th>创建人</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="task in list" :key="task.id">
              <td class="code">{{ task.taskNo }}</td>
              <td class="strong">{{ task.taskName }}</td>
              <td>
                <span class="tag" :class="`tag--${priorityTone(task.priority)}`">
                  {{ labelOf(priorityOptions, task.priority) }}
                </span>
              </td>
              <td>
                <span class="tag" :class="`tag--${statusTone(task.status)}`">
                  {{ labelOf(statusOptions, task.status) }}
                </span>
              </td>
              <td class="time">
                <span :title="fmtTime(task.planStartTime)">{{ fmtShortTime(task.planStartTime) }}</span>
                <span class="time__to" :title="fmtTime(task.planEndTime)">
                  → {{ fmtShortTime(task.planEndTime) }}
                </span>
              </td>
              <!-- 飞手与设备在「派发」环节才写入，新创建的任务这两列是空的 -->
              <td>{{ task.pilotName || '未派发' }}</td>
              <td>{{ task.droneCode || '未派发' }}</td>
              <td>{{ task.createByName || '—' }}</td>
              <td>
                <div class="ops">
                  <span class="op" @click="openDetail(task)">详情</span>
                  <!--
                    操作按状态显隐：
                      · 待派发 → 可以派发
                      · 已派发 → 可以撤回（撤回后回到待派发，再派给别人）
                    改派走的是「撤回 + 重新派发」两步，与状态机的箭头一一对应，
                    不存在「已派发直接改成另一个人」的捷径。
                  -->
                  <span
                    v-if="auth.hasPermission('task:dispatch') && task.status === 'pending'"
                    class="op"
                    @click="openDispatchDialog(task)"
                  >
                    派发
                  </span>
                  <span
                    v-if="auth.hasPermission('task:dispatch') && task.status === 'dispatched'"
                    class="op op--danger"
                    @click="handleWithdraw(task)"
                  >
                    撤回
                  </span>
                  <!--
                    报告：只在收尾过的任务上出现（完成 / 失败都写了报告）。
                    执行中的任务报告还不存在，后端会报「这条任务还没有报告」——
                    与其让用户点了才知道，不如一开始就不显示（和上面两个操作同一个思路）
                  -->
                  <span
                    v-if="task.status === 'finished' || task.status === 'failed'"
                    class="op"
                    @click="openReport(task)"
                  >
                    报告
                  </span>
                </div>
              </td>
            </tr>
            <tr v-if="!loading && list.length === 0">
              <td colspan="9" class="empty">没有符合条件的任务</td>
            </tr>
          </tbody>
        </table>
      </div>

      <div class="pager">
        <span>共 {{ total }} 条，第 {{ currentPage }} / {{ totalPages }} 页</span>
        <div class="pager__pages">
          <span
            class="pager__btn"
            :class="{ 'is-disabled': currentPage <= 1 }"
            @click="handlePageChange(currentPage - 1)"
          >
            上一页
          </span>
          <span class="pager__btn is-active">{{ currentPage }}</span>
          <span
            class="pager__btn"
            :class="{ 'is-disabled': currentPage >= totalPages }"
            @click="handlePageChange(currentPage + 1)"
          >
            下一页
          </span>
        </div>
      </div>
    </div>

    <!-- ============ 新建任务 ============ -->
    <el-dialog v-model="formVisible" title="新建任务" width="660px" align-center>
      <p class="dialog__tip">
        创建只定义<b>要做什么</b>：任务名、优先级、计划时间、任务内容。<br />
        指派飞手与设备属于「派发」环节，创建出来的任务状态固定为<b>待派发</b>。
      </p>

      <div class="form">
        <label class="form__row">
          <span class="form__label">任务名称</span>
          <input
            v-model="form.taskName"
            class="form__input"
            type="text"
            maxlength="100"
            placeholder="如 输电线路巡检"
          />
        </label>

        <div class="form__row">
          <span class="form__label">优先级</span>
          <select v-model="form.priority" class="form__input form__select">
            <option v-for="item in priorityOptions" :key="item.value" :value="item.value">
              {{ item.label }}
            </option>
          </select>
        </div>

        <label class="form__row">
          <span class="form__label">计划开始</span>
          <input v-model="form.planStartTime" class="form__input" type="datetime-local" />
        </label>

        <label class="form__row">
          <span class="form__label">计划结束</span>
          <input v-model="form.planEndTime" class="form__input" type="datetime-local" />
        </label>

        <div class="form__row">
          <span class="form__label">航线</span>
          <select v-model="form.routeId" class="form__input form__select" :disabled="routesLoading">
            <option :value="undefined">{{ routesLoading ? '加载中…' : '不选（直接写任务内容）' }}</option>
            <option v-for="item in routeOptions" :key="item.id" :value="item.id">
              {{ item.name }}{{ item.pointCount ? ' · ' + item.pointCount + ' 个点' : '' }}
            </option>
          </select>
        </div>

        <p v-if="pickedRoute" class="form__hint form__hint--full">
          已选「{{ pickedRoute.name }}」（{{ routeHints }}）。创建时会把这条航线<b>快照</b>一份存进任务，
          以后在航线管理里改动它，不影响这条任务。
        </p>

        <label class="form__row form__row--top">
          <span class="form__label">任务内容</span>
          <textarea
            v-model="form.routeInfo"
            class="form__input form__area"
            rows="3"
            placeholder="不选航线时必填；选了航线的话留空即可，系统会自动填一句摘要"
          ></textarea>
        </label>

        <div class="form__row form__row--top">
          <span class="form__label">起降点</span>
          <div class="takeoff">
            <div class="takeoff__bar">
              <input v-model="form.takeoffName" class="form__input" placeholder="起降点名称，如 A-01 基地（选填）" />
              <button v-if="hasTakeoff" class="btn btn--ghost" type="button" @click="clearTakeoff">清除</button>
            </div>
            <p v-if="hasTakeoff" class="takeoff__coord">
              {{ (form.takeoffLng as number).toFixed(6) }}, {{ (form.takeoffLat as number).toFixed(6) }}
            </p>
            <!-- 只在弹窗打开时渲染地图：藏在 display:none 里初始化会画不出来（且不报错） -->
            <GeoMap
              v-if="formVisible"
              pickable
              :fit="false"
              :center="takeoffCenter"
              :zoom="15"
              :markers="takeoffMarker"
              :height="240"
              pick-hint="点击地图选起降点（可不选）"
              @pick="handlePickTakeoff"
            />
          </div>
        </div>

        <label class="form__row form__row--top">
          <span class="form__label">补充说明</span>
          <textarea
            v-model="form.description"
            class="form__input form__area"
            rows="2"
            placeholder="选填，如 需避开午间高温时段"
          ></textarea>
        </label>
      </div>

      <template #footer>
        <button class="btn btn--ghost" type="button" @click="formVisible = false">取消</button>
        <button class="btn btn--primary" type="button" :disabled="formSaving" @click="submitForm">
          {{ formSaving ? '创建中' : '创建任务' }}
        </button>
      </template>
    </el-dialog>

    <!-- ============ 派发任务 ============ -->
    <el-dialog v-model="dispatchVisible" title="派发任务" width="560px" align-center>
      <p class="dialog__tip">
        派发是「定资源」的环节：指定谁去做、用哪台设备。<br />
        下拉里只列<b>当前可派</b>的人与设备 —— 飞手需账号启用且拥有飞手角色，设备需处于待命状态。<br />
        若与已有任务的时间段重叠，服务端会挡下来并告诉你是哪条任务。
      </p>

      <p v-if="dispatchTarget" class="dialog__task">
        <span class="code">{{ dispatchTarget.taskNo }}</span>
        <span class="dialog__name">{{ dispatchTarget.taskName }}</span>
        <span class="dialog__window">
          {{ fmtTime(dispatchTarget.planStartTime) }} ~ {{ fmtTime(dispatchTarget.planEndTime) }}
        </span>
      </p>

      <div class="form">
        <div class="form__row">
          <span class="form__label">飞手</span>
          <select
            v-model="dispatchForm.pilotId"
            class="form__input form__select"
            :disabled="optionsLoading"
          >
            <option value="" disabled>{{ optionsLoading ? '加载中…' : '请选择飞手' }}</option>
            <option v-for="item in options.pilots" :key="item.id" :value="item.id">
              {{ item.label }}
            </option>
          </select>
        </div>

        <div class="form__row">
          <span class="form__label">设备</span>
          <select
            v-model="dispatchForm.droneId"
            class="form__input form__select"
            :disabled="optionsLoading"
          >
            <option value="" disabled>{{ optionsLoading ? '加载中…' : '请选择设备' }}</option>
            <option v-for="item in options.drones" :key="item.id" :value="item.id">
              {{ item.label }}
            </option>
          </select>
        </div>

        <label class="form__row">
          <span class="form__label">备注</span>
          <input
            v-model="dispatchForm.note"
            class="form__input"
            type="text"
            maxlength="200"
            placeholder="选填，会记进流转记录"
          />
        </label>
      </div>

      <template #footer>
        <button class="btn btn--ghost" type="button" @click="dispatchVisible = false">取消</button>
        <button class="btn btn--primary" type="button" :disabled="dispatchSaving" @click="submitDispatch">
          {{ dispatchSaving ? '派发中' : '确认派发' }}
        </button>
      </template>
    </el-dialog>

    <!-- ============ 任务报告 ============ -->
    <el-dialog v-model="reportVisible" title="任务报告" width="680px" align-center>
      <div v-if="reportLoading" class="empty">加载中…</div>

      <div v-else-if="report" class="report">
        <div class="report__head">
          <div>
            <b>{{ report.taskName }}</b>
            <span class="report__no">{{ report.taskNo }}</span>
          </div>
          <!-- 报告可修订，所以显示是第几版 —— 改过要看得出 -->
          <span class="report__ver">第 {{ report.version }} 版</span>
        </div>

        <!--
          「只有声明、没有设备数据」必须标出来。
          完成**不强制**要有旁证（强制会把「图传断了」变成「飞手无法收工」），
          但复核的人得看得见 —— 否则报告里最不可信的那部分会被当成人可信的事实
        -->
        <div v-if="report.onlyDeclared" class="report__warn">
          本次执行只有飞手的声明，没有任何设备数据佐证
          （依据：{{ report.evidenceLabel || report.evidenceState }}）
        </div>

        <!-- ============ 飞手手填（主观）============ -->
        <div class="report__section">飞手填写</div>
        <div class="report__row"><span>结论</span><b>{{ report.resultLabel || report.result }}</b></div>
        <div class="report__row"><span>执行总结</span>{{ report.summary || '—' }}</div>
        <div class="report__row"><span>问题与处置</span>{{ report.problemLog || '—' }}</div>
        <div class="report__row">
          <span>时长 / 架次</span>
          {{ report.flightHours ?? '—' }} 小时 · {{ report.flightCount ?? '—' }} 架次
        </div>
        <div class="report__row">
          <span>上报人</span>{{ report.reporterName || '—' }} · {{ report.createTime || '—' }}
        </div>

        <!-- ============ 系统自动汇总（客观）============ -->
        <div class="report__section">系统汇总（从这里往下都不是飞手填的）</div>
        <div class="report__row">
          <span>实际起止</span>
          {{ report.actualStartTime || '—' }} ~ {{ report.actualEndTime || '—' }}
          <em v-if="report.computedHours != null">（{{ report.computedHours }} 小时）</em>
        </div>
        <div class="report__row">
          <span>设备数据</span>
          {{ report.telemetryCount ?? 0 }} 条
          <template v-if="report.dataFrom">（{{ report.dataFrom }} ~ {{ report.dataTo }}）</template>
        </div>
        <div class="report__row">
          <span>最低电量</span>
          {{ report.minBattery != null ? report.minBattery + '%' : '设备没报过' }}
        </div>
        <div class="report__row">
          <span>最大高度</span>
          {{ report.maxAlt != null ? report.maxAlt + ' m' : '设备没报过' }}
        </div>
        <div class="report__row">
          <span>执行依据</span>{{ report.evidenceLabel || report.evidenceState || '—' }}
        </div>
        <div class="report__row">
          <span>期间告警</span>
          <router-link v-if="report.alertCount" class="report__link" to="/alert">
            {{ report.alertCount }} 条（去告警中心看）
          </router-link>
          <template v-else>0 条</template>
        </div>
        <div class="report__row">
          <span>附件</span>
          <AttachmentList :attachments="report.attachments" />
        </div>
      </div>

      <template #footer>
        <button class="btn btn--ghost" type="button" @click="reportVisible = false">关闭</button>
      </template>
    </el-dialog>

    <!-- ============ 任务详情 ============ -->
    <el-dialog v-model="detailVisible" title="任务详情" width="680px" align-center>
      <p v-if="detailLoading" class="detail__loading">加载中…</p>

      <template v-else-if="detail">
        <div class="detail__head">
          <span class="code">{{ detail.task.taskNo }}</span>
          <span class="detail__name">{{ detail.task.taskName }}</span>
          <span class="tag" :class="`tag--${priorityTone(detail.task.priority)}`">
            {{ labelOf(priorityOptions, detail.task.priority) }}
          </span>
          <span class="tag" :class="`tag--${statusTone(detail.task.status)}`">
            {{ labelOf(statusOptions, detail.task.status) }}
          </span>
        </div>

        <dl class="detail__grid">
          <div>
            <dt>计划开始</dt>
            <dd>{{ fmtTime(detail.task.planStartTime) }}</dd>
          </div>
          <div>
            <dt>计划结束</dt>
            <dd>{{ fmtTime(detail.task.planEndTime) }}</dd>
          </div>
          <div>
            <dt>实际开始</dt>
            <dd>{{ fmtTime(detail.task.actualStartTime) }}</dd>
          </div>
          <div>
            <dt>实际结束</dt>
            <dd>{{ fmtTime(detail.task.actualEndTime) }}</dd>
          </div>
          <div>
            <dt>飞手</dt>
            <dd>{{ detail.task.pilotName || '未派发' }}</dd>
          </div>
          <div>
            <dt>设备</dt>
            <dd>{{ detail.task.droneCode || '未派发' }}</dd>
          </div>
          <div>
            <dt>创建人</dt>
            <dd>{{ detail.task.createByName || '—' }}</dd>
          </div>
          <div>
            <dt>创建时间</dt>
            <dd>{{ fmtTime(detail.task.createTime) }}</dd>
          </div>
        </dl>

        <div class="detail__block">
          <h4>任务内容</h4>
          <p class="detail__text">{{ detail.task.routeInfo || '—' }}</p>
        </div>

        <div class="detail__block">
          <h4>补充说明</h4>
          <p class="detail__text">{{ detail.task.description || '—' }}</p>
        </div>

        <!--
          飞行轨迹。数据来自设备每秒上报的经纬度（早就在证据表的报文原文里），
          不是新采的；坐标由后端转成了地图能直接用的那种（GCJ-02）。
        -->
        <div class="detail__block">
          <h4>飞行轨迹</h4>
          <TrackMap
            :points="track?.points"
            :raw-count="track?.rawCount"
            :planned="detail?.plannedRoute?.points"
            :takeoff="{
              lng: detail?.task?.takeoffLng,
              lat: detail?.task?.takeoffLat,
              name: detail?.task?.takeoffName,
            }"
          />
        </div>

        <div class="detail__block">
          <h4>流转记录</h4>
          <ol class="timeline">
            <li v-for="log in detail.logs" :key="log.id" class="timeline__item">
              <div class="timeline__body">
                <div class="timeline__top">
                  <span class="timeline__action">{{ labelOf(actionOptions, log.action) }}</span>
                  <span class="timeline__transition">{{ transitionText(log.fromStatus, log.toStatus) }}</span>
                  <span class="timeline__time">{{ fmtTime(log.createTime) }}</span>
                </div>
                <p class="timeline__who">
                  {{ log.operatorName || '—' }}
                  <template v-if="log.operatorRole">（{{ log.operatorRole }}）</template>
                </p>
                <p v-if="noteText(log)" class="timeline__note">{{ noteText(log) }}</p>
              </div>
            </li>
            <li v-if="detail.logs.length === 0" class="timeline__empty">暂无流转记录</li>
          </ol>
        </div>

        <!--
          本任务期间的告警。
          为什么不是「靠 task_id 关联」：设备上报那一刻如果没有执行中的任务，
          落库时 task_id 是空的 —— 那些「当时没主」的告警（设备提前自检报的温度高）
          其实和这条任务有关。所以后端按 task_id **或**「设备 + 任务时间窗」两边查。
        -->
        <div class="detail__block">
          <h4>
            本任务期间的告警
            <span v-if="(detail.alerts || []).length" class="detail__count">
              {{ detail.alerts!.length }} 条
            </span>
          </h4>
          <ul v-if="(detail.alerts || []).length" class="alertlist">
            <li v-for="alert in detail.alerts" :key="alert.id" class="alertlist__item">
              <span class="tag" :class="`tag--${severityTone(alert.severity)}`">
                {{ alert.severityLabel || alert.severity }}
              </span>
              <span class="alertlist__code">{{ alert.alertCodeLabel || alert.alertCode }}</span>
              <span class="alertlist__msg">{{ alert.message }}</span>
              <span class="alertlist__time">{{ fmtTime(alert.observedAt) }}</span>
            </li>
          </ul>
          <p v-else class="timeline__empty">本任务期间没有告警</p>
          <p v-if="(detail.alerts || []).length" class="detail__footnote">
            完整处置记录在
            <router-link class="report__link" to="/alert">告警中心</router-link>
          </p>
        </div>

        <!--
          现场打卡：飞手说自己去过哪。
          它**不是设备证据** —— 复核时和上面的轨迹放一起看：
          打卡点在轨迹附近就说得通，差几十公里就得问一句。
        -->
        <div class="detail__block">
          <h4>
            现场打卡
            <span v-if="(detail.checkins || []).length" class="detail__count">
              {{ (detail.checkins || []).length }} 条
            </span>
          </h4>
          <ul v-if="(detail.checkins || []).length" class="alertlist">
            <li v-for="(checkin, index) in detail.checkins" :key="checkin.id ?? index" class="alertlist__item">
              <span class="tag" :class="checkin.farAway ? 'tag--amber' : 'tag--green'">
                第 {{ checkin.seq }} 个航点
              </span>
              <span class="alertlist__msg">
                离目标 {{ checkin.deviationM }} 米<template v-if="checkin.accuracyM">（定位精度 ±{{ Math.round(Number(checkin.accuracyM)) }} 米）</template>
                <template v-if="checkin.remark"> · {{ checkin.remark }}</template>
              </span>
              <span v-if="checkin.submittedLate" class="tag tag--grey">补传</span>
              <span class="alertlist__time">{{ fmtTime(checkin.observedAt) }}</span>
            </li>
          </ul>
          <p v-else class="timeline__empty">这条任务没有现场打卡记录</p>
          <p v-if="(detail.checkins || []).length" class="detail__footnote">
            打卡是<b>飞手自述</b>，不能当设备证据用；要核对他到底到没到，看上面的实际轨迹。
          </p>
        </div>
      </template>

      <template #footer>
        <button class="btn btn--ghost" type="button" @click="detailVisible = false">关闭</button>
      </template>
    </el-dialog>

    <!--
      禁飞区命中确认。放在最外层：建任务与派发两处共用同一个弹窗，
      这样「提示里写什么、原因要写多长」只有一处实现。
    -->
    <ZoneConfirmDialog
      v-model="zoneDialogVisible"
      :hits="zoneHits"
      :title="zoneDialogTitle"
      :confirm-text="zoneConfirmText"
      @confirm="onZoneConfirmed"
    />
  </div>
</template>

<style scoped>
/* ---------- 检索栏 ---------- */
.filter {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
  padding: 16px 20px;
  margin-bottom: 18px;
}

.select {
  height: 36px;
  padding: 0 30px 0 12px;
  border-radius: 9px;
  font-size: 13px;
  font-family: inherit;
  color: var(--text);
  background-color: var(--input-bg);
  border: 1px solid var(--line-strong);
  outline: none;
  cursor: pointer;
  transition: border-color 0.25s, box-shadow 0.25s;
  appearance: none;
  background-image: linear-gradient(45deg, transparent 50%, var(--muted) 50%),
    linear-gradient(135deg, var(--muted) 50%, transparent 50%);
  background-position: calc(100% - 16px) 15px, calc(100% - 11px) 15px;
  background-size: 5px 5px, 5px 5px;
  background-repeat: no-repeat;
}

.select:focus {
  border-color: var(--accent-hover);
  box-shadow: 0 0 0 3px rgba(34, 211, 238, 0.12);
}

/* 下拉选项由浏览器渲染，深色主题下必须显式指定底色 */
.select option {
  color: var(--text);
  background-color: #0b1830;
}

/* ---------- 表格 ---------- */
.time {
  display: flex;
  flex-direction: column;
  font-size: 12.5px;
  line-height: 1.6;
  color: var(--text-2);
  white-space: nowrap;
}

.time__to {
  color: var(--muted);
}

.empty {
  padding: 40px 20px;
  text-align: center;
  color: var(--muted);
}

.pager__btn.is-disabled {
  opacity: 0.4;
  cursor: not-allowed;
}

.pager__btn.is-disabled:hover {
  color: var(--text-2);
  border-color: var(--line);
}

/* ---------- 表单（新建任务弹窗） ---------- */
.dialog__tip {
  margin-bottom: 16px;
  font-size: 12.5px;
  line-height: 1.9;
  color: var(--muted);
}

.form {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.form__row {
  display: flex;
  align-items: center;
  gap: 14px;
}

/* 多行输入框要顶对齐，否则 label 会飘在中间 */
.form__row--top {
  align-items: flex-start;
}

.form__label {
  width: 74px;
  flex: none;
  font-size: 13px;
  color: var(--muted);
}

.form__row--top .form__label {
  padding-top: 9px;
}

.form__input {
  flex: 1;
  width: 100%;
  height: 36px;
  padding: 0 12px;
  border-radius: 9px;
  font-size: 13.5px;
  font-family: inherit;
  color: var(--text);
  background: var(--input-bg);
  border: 1px solid var(--line-strong);
  outline: none;
  transition: border-color 0.25s, box-shadow 0.25s;
}

.form__input:focus {
  border-color: var(--accent-hover);
  box-shadow: 0 0 0 3px rgba(34, 211, 238, 0.12);
}

.form__select {
  appearance: none;
  cursor: pointer;
  background-image: linear-gradient(45deg, transparent 50%, var(--muted) 50%),
    linear-gradient(135deg, var(--muted) 50%, transparent 50%);
  background-position: calc(100% - 16px) 15px, calc(100% - 11px) 15px;
  background-size: 5px 5px, 5px 5px;
  background-repeat: no-repeat;
}

.form__select option {
  color: var(--text);
  background-color: #0b1830;
}

.form__area {
  height: auto;
  padding: 9px 12px;
  line-height: 1.7;
  resize: vertical;
}

/* 跨过 label 那一列的说明文字（选了航线后的提示） */
.form__hint--full {
  margin-left: 88px;
  font-size: 12px;
  line-height: 1.8;
  color: var(--muted);
}

/* ---------- 起降点选点 ---------- */
.takeoff {
  flex: 1;
  width: 100%;
}

.takeoff__bar {
  display: flex;
  gap: 10px;
  margin-bottom: 8px;
}

.takeoff__coord {
  margin-bottom: 8px;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 12px;
  color: var(--accent);
}

/* 日期时间控件在深色下需要指定配色，否则图标几乎看不见 */
.form__input[type='datetime-local'] {
  color-scheme: dark;
}

/* ---------- 派发弹窗 ---------- */
.dialog__task {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
  margin-bottom: 16px;
  padding: 10px 14px;
  border-radius: 10px;
  font-size: 13px;
  color: var(--text-2);
  background: var(--ghost-bg);
  border: 1px solid var(--line);
}

.dialog__name {
  font-weight: 500;
  color: var(--text);
}

/* 时间段单独靠右显示：派发前最该先看一眼的就是它，因为冲突判定是围绕它做的 */
.dialog__window {
  margin-left: auto;
  font-size: 12.5px;
  color: var(--muted);
  white-space: nowrap;
}

/* ---------- 本任务期间的告警（详情弹窗里） ---------- */
.detail__count {
  margin-left: 8px;
  font-weight: 400;
  font-size: 12px;
  color: var(--muted);
}

.detail__footnote {
  margin-top: 8px;
  font-size: 12px;
  color: var(--muted);
}

/*
  告警列表：一行一条，按发生时刻排（后端已排好）。
  与告警中心那种「待办队列」不同 —— 这里是**回顾**，所以不做级别的强排序，
  保持时间线的自然顺序，让人看清「什么时候出了什么事」
*/
.alertlist {
  margin: 0;
  padding: 0;
  list-style: none;
}

.alertlist__item {
  display: flex;
  align-items: baseline;
  gap: 10px;
  padding: 6px 0;
  font-size: 13px;
  line-height: 1.6;
  border-bottom: 1px solid var(--line);
}

.alertlist__item:last-child {
  border-bottom: none;
}

.alertlist__code {
  flex: none;
  color: var(--text-2);
}

.alertlist__msg {
  flex: 1;
  min-width: 0;
  color: var(--muted);
  word-break: break-all;
}

.alertlist__time {
  flex: none;
  font-size: 12px;
  color: var(--muted);
}

/* ---------- 报告弹窗 ---------- */
.report__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.report__no {
  margin-left: 10px;
  font-size: 12px;
  color: var(--muted);
}

.report__ver {
  flex: none;
  padding: 2px 10px;
  border-radius: 999px;
  font-size: 12px;
  color: var(--text-2);
  background: rgba(255, 255, 255, 0.06);
  border: 1px solid var(--line);
}

/* 「只有声明、没有数据」的提示：琥珀色，不是错误，但复核的人要看一眼 */
.report__warn {
  margin-top: 10px;
  padding: 8px 12px;
  border-radius: 8px;
  font-size: 13px;
  line-height: 1.6;
  color: #fcd34d;
  background: rgba(251, 191, 36, 0.12);
  border: 1px solid rgba(251, 191, 36, 0.32);
}

/* 分节：把「飞手填的」和「系统算的」分开 —— 这两者的可信度不一样 */
.report__section {
  margin: 18px 0 8px;
  padding-bottom: 6px;
  font-size: 13px;
  font-weight: 600;
  color: var(--text-2);
  border-bottom: 1px solid var(--line);
}

.report__row {
  display: flex;
  gap: 14px;
  padding: 5px 0;
  font-size: 13px;
  color: var(--text-2);
  line-height: 1.7;
}

.report__row > span {
  flex: none;
  width: 84px;
  color: var(--muted);
}

.report__row em {
  font-style: normal;
  color: var(--muted);
}

.report__link {
  color: var(--accent, #38bdf8);
}

/* ---------- 详情弹窗 ---------- */
.detail__loading {
  padding: 30px 0;
  text-align: center;
  color: var(--muted);
  font-size: 13px;
}

.detail__head {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
  padding-bottom: 16px;
  border-bottom: 1px solid var(--line);
}

.detail__name {
  font-size: 15px;
  font-weight: 600;
  color: var(--text);
}

.detail__grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px 24px;
  margin: 18px 0;
}

.detail__grid dt {
  font-size: 12px;
  color: var(--muted);
}

.detail__grid dd {
  margin: 3px 0 0;
  font-size: 13.5px;
  color: var(--text);
}

.detail__block {
  margin-top: 18px;
}

.detail__block h4 {
  margin-bottom: 8px;
  font-size: 12.5px;
  font-weight: 500;
  letter-spacing: 0.5px;
  color: var(--muted);
}

.detail__text {
  padding: 12px 14px;
  border-radius: 10px;
  font-size: 13px;
  line-height: 1.8;
  color: var(--text-2);
  background: var(--ghost-bg);
  border: 1px solid var(--line);
  white-space: pre-wrap;
}

/* ---------- 时间轴 ---------- */
.timeline {
  margin: 0;
  padding: 0 0 0 20px;
  list-style: none;
  /* 竖线画在 ul 的左内边距上，圆点用负 margin 压回线上 */
  border-left: 1px solid var(--line);
}

.timeline__item {
  position: relative;
  padding-bottom: 16px;
}

.timeline__item:last-child {
  padding-bottom: 0;
}

.timeline__item::before {
  content: '';
  position: absolute;
  left: -26px;
  top: 6px;
  width: 9px;
  height: 9px;
  border-radius: 50%;
  background: var(--cyan);
  box-shadow: 0 0 10px rgba(34, 211, 238, 0.8);
}

.timeline__body {
  padding: 10px 14px;
  border-radius: 10px;
  background: var(--ghost-bg);
  border: 1px solid var(--line);
}

.timeline__top {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
}

.timeline__action {
  font-size: 13.5px;
  font-weight: 500;
  color: var(--text);
}

.timeline__transition {
  font-size: 12px;
  color: var(--cyan);
}

.timeline__time {
  margin-left: auto;
  font-size: 12px;
  color: var(--muted);
}

.timeline__who {
  margin-top: 5px;
  font-size: 12.5px;
  color: var(--text-2);
}

.timeline__note {
  margin-top: 5px;
  font-size: 12.5px;
  line-height: 1.7;
  color: var(--muted);
}

.timeline__empty {
  font-size: 12.5px;
  color: var(--muted);
}
</style>
