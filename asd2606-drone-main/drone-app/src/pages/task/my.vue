<script setup lang="ts">
import { computed, ref } from 'vue'
import { onHide, onPullDownRefresh, onReachBottom, onShow, onUnload } from '@dcloudio/uni-app'
import { fetchMyTasks, failTask, finishTask, refuseTask, startTask, type TaskVO } from '@/api/task'
import { fetchTaskAlerts, reportOnsiteIssue, type AlertVO } from '@/api/alert'
import { uploadTaskAttachment } from '@/api/attachment'
import { fetchCurrentUser, logout, type UserVO } from '@/api/user'
import { clearToken, isLoggedIn } from '@/utils/auth'
import {
  alertSeverityOptions,
  alertStatusOptions,
  evidenceStateOptions,
  labelOf,
  manualAlertOptions,
  priorityOptions,
  statusOptions,
  toneOf,
} from '@/utils/dict'
import { closeTaskSocket, connectTaskSocket, type TaskPushMessage } from '@/utils/socket'

/** 每页条数 */
const PAGE_SIZE = 10

/**
 * 一次拉多少条告警。
 *
 * <p>取 50 而不是页面上那种 10：告警清单没有分页交互，而一条任务上
 * 通常只有几条；给宽一点，绝大多数情况一屏就能看全。
 * 真超过 50 条，说明这次任务问题不小，管理侧的告警页里看更合适。
 */
const ALERT_PAGE_SIZE = 50

const user = ref<UserVO | null>(null)
const tasks = ref<TaskVO[]>([])
const total = ref(0)
const page = ref(1)
const loading = ref(false)
const loaded = ref(false)

/** WebSocket 是否连着 —— 界面上会显示一个小标，飞手能看出「现在是实时的」 */
const realtimeOn = ref(false)

/** 当前展开的那条任务；为 null 表示都收起 */
const expandedId = ref<number | null>(null)

/** taskId → 该任务的告警清单（展开时才拉，拉过就缓存） */
const alertsByTask = ref<Record<number, AlertVO[]>>({})
/** taskId → 是否已经拉过。用它避免每次展开都重发请求 */
const alertsLoaded = ref<Record<number, boolean>>({})
/** 正在拉告警的任务 id，用于显示「加载中…」 */
const alertLoading = ref<number | null>(null)

const hasMore = computed(() => tasks.value.length < total.value)

/** 展示用名字：优先真实姓名，退回登录账号 */
const displayName = computed(() => user.value?.name || user.value?.username || '')

/**
 * 每次页面显示都重新拉一遍 + 把实时连接接回来。
 *
 * <p>用 onShow 而不是 onLoad：飞手可能从小程序其他页面切回来，
 * 期间管理员可能刚给他派了新任务或撤回了派发，切回来就该看到最新的。
 *
 * <p>连接状态也一样：小程序切到后台连接会被回收，
 * 所以回到前台不能假设「还连着」，直接连一次（已连着的话这个调用是空操作）。
 */
onShow(() => {
  if (!isLoggedIn()) {
    uni.reLaunch({ url: '/pages/login/login' })
    return
  }
  connectTaskSocket(handlePush, (connected) => {
    realtimeOn.value = connected
  })
  loadUser()
  loadTasks()
})

/**
 * 离开页面就断开。
 *
 * <p>为什么不在后台一直挂着：小程序进入后台后连接本来就会被系统回收，
 * 硬留着只是自欺欺人；而且用户不在这个页面时收到推送也没意义。
 * 回到页面 onShow 会重新连上。
 */
onHide(() => {
  closeTaskSocket()
  realtimeOn.value = false
})

onUnload(() => {
  closeTaskSocket()
  realtimeOn.value = false
})

/**
 * 收到服务端推的消息。
 *
 * <p><b>关键：推送只当「有变化了」的信号，不当数据用。</b>
 * 收到之后立刻去调一次接口，用接口结果刷新界面 ——
 * 这样消息丢了/重了/乱序了，界面数据都不会错，最坏只是晚几秒。
 */
function handlePush(message: TaskPushMessage) {
  if (message.type === 'alertRaised') {
    handleAlertPush(message)
    return
  }
  if (message.type !== 'taskDispatched') {
    return
  }

  const data = message.data || {}
  const taskName = String(data.taskName || '新任务')
  const taskNo = String(data.taskNo || '')

  uni.showToast({
    title: `新任务：${taskName}`,
    icon: 'none',
    duration: 2500,
  })

  // 不只是刷新列表：把刚推来的这条展开，飞手不用自己去列表里找
  const pushedId = Number(data.taskId)
  if (pushedId) {
    expandedId.value = pushedId
  }

  loadTasks()

  console.info(`收到派发通知：${taskNo} ${taskName}`)
}

/**
 * 收到告警推送。
 *
 * <p><b>为什么飞手要收这个</b>：他就在现场，是唯一能立刻采取行动的人
 * （返航、降落、中止任务）。只推给坐在办公室的管理员，等于把最该知道的人漏掉了。
 *
 * <p>提示文案对 `critical` 和别的级别不一样：`critical` 会把「该做什么」
 * 一起说出来。飞手可能正在操作飞机，只会扫一眼通知 ——
 * 不能让他点进去才知道要干什么。
 */
function handleAlertPush(message: TaskPushMessage) {
  const data = message.data || {}
  const severity = String(data.severity || '')
  const severityLabel = String(data.severityLabel || '告警')
  const codeLabel = String(data.alertCodeLabel || data.alertCode || '')
  const hint = String(data.actionHint || '')
  const text = String(data.message || '')
  const isCritical = severity === 'critical'

  uni.showToast({
    title: isCritical
      ? `${severityLabel}：${codeLabel}｜${hint || text}`
      : `${severityLabel}：${codeLabel}`,
    icon: 'none',
    duration: isCritical ? 4000 : 2500,
  })

  // 老规矩：推送只当「有变化了」的信号，数据以查询为准。
  // 角标在任务列表里，所以列表要刷；正在看的那条任务的清单也要刷
  loadTasks()
  const taskId = Number(data.taskId)
  if (taskId && expandedId.value === taskId) {
    loadAlerts(taskId, true)
  }

  console.info(`收到告警通知：${codeLabel}　${text}`)
}

/**
 * 拉某条任务的告警清单。
 *
 * <p>为什么懒加载（展开才拉）而不是跟着任务列表一起拉：
 * 一页有 10 条任务，全拉就是 10 次请求，而飞手绝大多数时候只看其中一条。
 *
 * @param force true 表示忽略缓存强制重拉（告警推送过来、或刚上报完时用）
 */
async function loadAlerts(taskId: number, force = false) {
  if (!force && alertsLoaded.value[taskId]) return

  alertLoading.value = taskId
  try {
    const result = await fetchTaskAlerts({ taskId, page: 1, pageSize: ALERT_PAGE_SIZE })
    // 整体替换而不是就地改：ref 里放对象时，替换会让依赖它的模板重新求值，
    // 而新增一个 key 不会（Vue 3 的响应式对新增属性是能追踪的，
    // 但整体替换在阅读上也更清楚「这就是这条任务的全部告警」）
    alertsByTask.value = { ...alertsByTask.value, [taskId]: result.list }
    alertsLoaded.value = { ...alertsLoaded.value, [taskId]: true }
  } catch {
    // 提示已由请求层统一弹过（403 / 网络异常），这里只负责收尾
  } finally {
    alertLoading.value = null
  }
}

async function loadUser() {
  try {
    user.value = await fetchCurrentUser()
  } catch {
    // 401 已经由 request.ts 统一处理（清 token 回登录页），这里不用管
  }
}

/** 拉第一页（进页面、下拉刷新、退出登录前都用它） */
async function loadTasks() {
  if (loading.value) return
  loading.value = true
  try {
    const result = await fetchMyTasks({ page: 1, pageSize: PAGE_SIZE })
    tasks.value = result.list
    total.value = result.total
    page.value = 1
    loaded.value = true
  } catch {
    tasks.value = []
    total.value = 0
    loaded.value = true
  } finally {
    loading.value = false
  }
}

/**
 * 上拉加载下一页。
 *
 * <p>页码只在成功之后才推进：失败了页码不动，下次上拉还是请求同一页，
 * 不会因为一次网络抖动就漏掉一页数据。
 */
async function loadMore() {
  if (loading.value || !hasMore.value) return

  loading.value = true
  try {
    const next = page.value + 1
    const result = await fetchMyTasks({ page: next, pageSize: PAGE_SIZE })
    tasks.value = [...tasks.value, ...result.list]
    total.value = result.total
    page.value = next
  } catch {
    // 提示已弹过，页码保持不变
  } finally {
    loading.value = false
  }
}

/** 下拉刷新：用户信息和任务一起刷，因为他可能刚被改了资料 */
onPullDownRefresh(async () => {
  // 告警缓存也要作废：管理侧可能刚把某条处理掉了，留着缓存会让状态看起来没变
  alertsLoaded.value = {}
  await Promise.all([loadUser(), loadTasks()])

  // 如果有一条正展开着，把它的清单也重新拉一次，免得停在旧数据上
  const current = expandedId.value
  if (current) {
    await loadAlerts(current, true)
  }
  uni.stopPullDownRefresh()
})

onReachBottom(loadMore)

/** 打开这条任务的地图与打卡页 */
function openMap(task: TaskVO) {
  uni.navigateTo({ url: `/pages/task/detail?id=${task.id}` })
}

function toggleExpand(task: TaskVO) {
  const opening = expandedId.value !== task.id
  expandedId.value = opening ? task.id : null

  // 展开时才去拉这条任务的告警，拉过就缓存 —— 收起再展开不会重复请求
  if (opening) {
    loadAlerts(task.id)
  }
}

/** 告警带的附件名，拼成一行显示 */
function attachmentNames(alert: AlertVO) {
  return (alert.attachments || []).map((item) => item.fileName).join('、')
}

/** 正在提交的任务 id，避免连点两次 */
const submitting = ref<number | null>(null)

/** 正在上报现场问题的任务 id（含上传照片的那一段），用于按钮按下后显示「上报中…」 */
const reporting = ref<number | null>(null)

/**
 * 页面上先选好、已经传上去的那张现场照片。
 *
 * <p>为什么不放在上报流程里：前两版都藏在上报流程中间，试的人**始终找不到**
 * （见 {@link handleReport} 的注释）。现在它由页面上那个一直可见的按钮产生，
 * 上报时只是把它带上。
 */
const pendingPhoto = ref<{ taskId: number; id: number; fileName: string } | null>(null)

/**
 * 正在上传照片的标识，用于照片按钮显示「上传中…」。
 *
 * <p>为什么不是单纯的任务 id：页面上**有两个照片按钮**（随报告提交的那张、
 * 附在现场问题上的那张），只按任务 id 记的话，一个在上传、两个按钮都在转，
 * 看不出是哪张在传。
 */
const photoUploading = ref<string | null>(null)

/** 照片上传状态的键：区分「报告照片」与「问题照片」两个按钮 */
function uploadKey(target: 'finish' | 'report', taskId: number) {
  return `${target}:${taskId}`
}

// ---------------------------------------------------------------------------
// 完成任务（报告线）
// ---------------------------------------------------------------------------

/**
 * 报告的结论。
 *
 * <p>**只有两个**：成功、部分完成。第三种「失败」不在这个表单里，
 * 它走「标记失败」那个按钮 —— 那样结论才只描述「作业完成度」，
 * 不会和任务状态（已失败）重叠。后端也据此只收这两个值。
 */
const resultOptions = [
  { value: 'success', label: '成功' },
  { value: 'partial', label: '部分完成' },
]

/** 每条任务各自的表单内容：飞手可能同时展开过好几条 */
interface FinishForm {
  result: string
  summary: string
  problemLog: string
  flightHours: string
  flightCount: string
  photoId: number | null
  photoName: string
}

const finishForms = ref<Record<number, FinishForm>>({})

/** 正在提交收尾的任务 id */
const settling = ref<number | null>(null)

/**
 * 取（或初始化）某条任务的表单。
 *
 * <p>时长**预填**一个算出来的值（从「开始执行」到现在），让飞手确认或修正：
 * 完全自动不给改，遇到「飞了两架次」就没辙；完全手填，人就会瞎填。
 * 预填 + 可改是这两者的折中（设计文档 5.1）。
 */
function formOf(task: TaskVO): FinishForm {
  if (!finishForms.value[task.id]) {
    finishForms.value = {
      ...finishForms.value,
      [task.id]: {
        result: 'success',
        summary: '',
        problemLog: '',
        flightHours: elapsedHours(task),
        flightCount: '1',
        photoId: null,
        photoName: '',
      },
    }
  }
  return finishForms.value[task.id]
}

/**
 * 从「开始执行」到现在过了多少小时（保留两位），算不出来就返回空串。
 *
 * <p>为什么不用后端算：任务还没收尾，`actual_end_time` 是空的，
 * 报告接口那套自动汇总要等完成之后才有。所以这里按「到现在为止」先给个默认值，
 * 飞手收工后自己核对一下 —— 反正他填的才是最终值。
 *
 * <p>`replace(/-/g, '/')` 是为了兼容小程序：iOS 与微信里
 * `new Date('2027-08-11 09:00:00')` 这种写法会得到 Invalid Date，
 * 换成斜杠才认。
 */
function elapsedHours(task: TaskVO): string {
  if (!task.actualStartTime) return ''
  const start = new Date(task.actualStartTime.replace(/-/g, '/')).getTime()
  if (Number.isNaN(start)) return ''
  const hours = (Date.now() - start) / 3600000
  return hours <= 0 ? '0' : String(Math.round(hours * 100) / 100)
}

function setResult(task: TaskVO, result: string) {
  formOf(task).result = result
}

/** 表单里那几个文本字段的输入处理 */
type FinishTextField = 'summary' | 'problemLog' | 'flightHours' | 'flightCount'

/**
 * 把输入框的值写回表单。
 *
 * <p>用 {@code :value + @input} 而不是 {@code v-model} 的原因：
 * 表单是按任务 id 存在一个对象里的（{@code formOf(task).summary}），
 * 而 {@code v-model="formOf(task).summary"} 在模板里没法这么写。
 * 代价是多一个方法，好处是「每条任务各留一份草稿」这件事看得见。
 */
function onFormInput(task: TaskVO, field: FinishTextField, event: unknown) {
  const value = (event as { detail?: { value?: string } })?.detail?.value ?? ''
  formOf(task)[field] = String(value)
}

/** 完成 / 标记失败：两条路都在这一个方法里，只是参数不同 */
async function handleSettle(task: TaskVO, fail: boolean) {
  const form = formOf(task)

  if (fail && !form.problemLog.trim()) {
    uni.showToast({ title: '标记失败必须填写失败原因', icon: 'none', duration: 2500 })
    return
  }
  const hours = form.flightHours.trim() === '' ? undefined : Number(form.flightHours)
  const count = form.flightCount.trim() === '' ? undefined : Number(form.flightCount)
  if (hours !== undefined && (Number.isNaN(hours) || hours < 0 || hours > 24)) {
    uni.showToast({ title: '飞行时长要在 0 ~ 24 小时之间', icon: 'none', duration: 2500 })
    return
  }

  const ok = await confirmModal(fail ? '标记执行失败' : '完成任务',
    fail
      ? `确认把「${task.taskName}」标记为执行失败吗？\n\n标记后飞手与设备都会被释放，管理员会收到通知。`
      : `确认完成「${task.taskName}」吗？\n\n提交后设备与飞手会被释放，任务进入已完成，报告会交给管理员复核。`)
  if (!ok) return

  settling.value = task.id
  try {
    const body = {
      taskId: task.id,
      // 标记失败时**不传 result**：后端会写死 failed，
      // 免得「任务状态」和「结论」两处各说各话
      result: fail ? undefined : form.result,
      summary: form.summary.trim() || undefined,
      problemLog: form.problemLog.trim() || undefined,
      flightHours: hours,
      flightCount: count,
      attachmentIds: form.photoId ? [form.photoId] : undefined,
    }
    if (fail) {
      await failTask(body)
      uni.showToast({ title: '已标记为执行失败', icon: 'none', duration: 2500 })
    } else {
      await finishTask(body)
      uni.showToast({ title: '任务已完成', icon: 'success' })
    }
    // 表单用完就撤掉，不然下次展开还留着上次的内容
    const rest = { ...finishForms.value }
    delete rest[task.id]
    finishForms.value = rest
    expandedId.value = null
    await loadTasks()
  } catch {
    // 提示已由请求层统一弹过（比如「有附件不属于这条任务」）
  } finally {
    settling.value = null
  }
}

/** 收尾表单里的照片：与「现场上报」的那张分开存，两份用途不同，别互相覆盖 */
async function pickFinishPhoto(task: TaskVO) {
  const filePath = await choosePhoto()
  if (!filePath) return
  photoUploading.value = uploadKey('finish', task.id)
  try {
    const attachment = await uploadTaskAttachment(task.id, filePath)
    const form = formOf(task)
    form.photoId = attachment.id
    form.photoName = attachment.fileName
    uni.showToast({ title: '照片已上传', icon: 'success' })
  } catch {
    // 上传失败的原因由请求层弹出来
  } finally {
    photoUploading.value = null
  }
}

function removeFinishPhoto(task: TaskVO) {
  const form = formOf(task)
  form.photoId = null
  form.photoName = ''
}

/**
 * 开始执行。
 *
 * <p>这一步之后设备才算真的起飞（后端会把设备状态从「已派出」改成「飞行中」），
 * 所以先让飞手确认一下 —— 手滑点错在业务上是件真事。
 */
async function handleStart(task: TaskVO) {
  const ok = await confirmModal('开始执行',
    `确认开始执行「${task.taskName}」吗？\n\n开始后设备将进入飞行中状态，任务不能再拒绝。`)
  if (!ok) return

  submitting.value = task.id
  try {
    await startTask(task.id)
    uni.showToast({ title: '已开始执行', icon: 'success' })
    await loadTasks()
  } catch {
    // 错误提示已经由 request.ts 弹过（比如「这条任务不是派给你的」）
  } finally {
    submitting.value = null
  }
}

/**
 * 拒绝执行。
 *
 * <p>确认框里必须说清后果：拒绝<b>不是</b>「这次不干」，而是把任务退回去 ——
 * 飞手与设备都会被释放，管理员会收到通知并重新指派别人。
 * 不写清楚的话，飞手会以为自己只是「标记一下不去」。
 */
async function handleRefuse(task: TaskVO) {
  const ok = await confirmModal('拒绝执行',
    `确认拒绝「${task.taskName}」吗？\n\n拒绝后这条任务会退回「待派发」，`
    + `不再派给你，管理员会收到通知并重新指派别人。`)
  if (!ok) return

  // 收一句原因：管理员据此决定是换人、改时间还是换设备。
  // 选填 —— 拒绝本身就是明确态度，不强制，但界面上要提示用途
  const reason = await promptModal('拒绝原因（选填）', '比如：当天有别的安排 / 设备不适合这条航线')
  if (reason === null) return

  submitting.value = task.id
  try {
    await refuseTask(task.id, reason || undefined)
    uni.showToast({ title: '已拒绝，任务已退回', icon: 'none', duration: 2500 })
    await loadTasks()
  } catch {
    // 同上，提示已由请求层统一处理
  } finally {
    submitting.value = null
  }
}

/**
 * 现场异常上报。
 *
 * <h2>照片为什么挪到页面上、不再藏在上报流程里</h2>
 * 前面两版都把「带照片」做成上报流程中间的一步（先是确认框，后来是选项面板），
 * 结果试的人**始终找不到照片功能**，两次都报「现场上报没有图片上传」。
 * 结论很清楚：<b>只要照片藏在弹层链里，就等于没有这个功能。</b>
 * 现在改成页面上**一直看得见**的一个按钮（{@link pickPhoto}），
 * 点了就拍/选并上传，上传结果直接显示在按钮旁边。
 * 上报流程里不再提照片 —— 它只负责把已经传好的那张带上。
 *
 * <h2>流程（类型 → 说明 → 提交）</h2>
 * 说明必填：一句「有问题」等于没说。
 * 照片是可选的，而且是**先传后交**：上传和提交是两次请求，
 * 这样上传失败时能重试上传，不必把说明重写一遍。
 */
async function handleReport(task: TaskVO) {
  // ---- 第一步：选类型 ----
  const alertCode = await chooseAlertCode()
  if (!alertCode) return

  // ---- 第二步：写说明（必填）----
  const message = await promptModal('现场情况说明', '发生了什么？比如：东侧有人围观，暂时不能起飞')
  if (message === null) return
  if (!message.trim()) {
    uni.showToast({ title: '请填写说明，管理员要据此判断怎么处理', icon: 'none', duration: 2500 })
    return
  }

  // ---- 第三步：提交（照片是页面上先选好的，这里只管带上）----
  // 只带「属于这条任务」的那张：飞手可能在任务 A 下选了照片又跑到任务 B 去上报，
  // 带上别人的附件 id 服务端也会拒（它校验附件归属），但没必要让它走到那一步
  const attachmentIds = pendingPhoto.value && pendingPhoto.value.taskId === task.id
    ? [pendingPhoto.value.id]
    : []

  reporting.value = task.id
  try {
    await reportOnsiteIssue({
      taskId: task.id,
      alertCode,
      message: message.trim(),
      attachmentIds,
    })
    uni.showToast({ title: '已上报，管理员会尽快处理', icon: 'none', duration: 2500 })
    pendingPhoto.value = null
    // 角标与清单都要刷：刚上报的这条会立刻出现在清单最前面（列表按级别与未处理排序）
    await loadTasks()
    await loadAlerts(task.id, true)
  } catch {
    // 提示已由请求层统一处理
  } finally {
    reporting.value = null
  }
}

/**
 * 拍一张 / 选一张现场照片，并立刻上传。
 *
 * <p>刻意做成「一次点击就到上传完成」：选完立刻传，结果直接显示在按钮旁边。
 * 不做「先选、提交时再传」——那样飞手不知道到底传上去没有。
 *
 * <p>{@code sizeType: ['original']} 是刻意的：压缩过的照片会丢掉 EXIF、
 * 也看不清细节，而取证的价值恰在那上面（设计文档 7.3 节）。
 * 代价是文件大（手机原图 3~8MB），所以后端把照片上限配到了 20MB。
 */
async function pickPhoto(task: TaskVO) {
  const filePath = await choosePhoto()
  if (!filePath) return          // 取消了选图/拍照

  photoUploading.value = uploadKey('report', task.id)
  try {
    const attachment = await uploadTaskAttachment(task.id, filePath)
    pendingPhoto.value = { taskId: task.id, id: attachment.id, fileName: attachment.fileName }
    uni.showToast({ title: '照片已上传', icon: 'success' })
  } catch {
    // 上传失败的原因由请求层弹出来（「不支持的文件类型」「照片不能超过 20MB」等）
  } finally {
    photoUploading.value = null
  }
}

/**
 * 取消已选的照片。
 *
 * <p>注意：文件**已经传到服务端了**，这里只是不再把它带上。
 * 于是服务端会留一个没人引用的附件（孤儿文件）。
 * 这是有意接受的代价 —— 为了这一步去加一个删除接口，代价比收益大，
 * 将来清理任务（设计文档第七步）本来就要扫孤儿文件。
 */
function removePhoto() {
  pendingPhoto.value = null
}

/** 选一个上报类型。用户点了取消返回 null */
function chooseAlertCode(): Promise<string | null> {
  return new Promise((resolve) => {
    uni.showActionSheet({
      itemList: manualAlertOptions.map((item) => item.label),
      success: (res) => resolve(manualAlertOptions[res.tapIndex]?.value ?? null),
      // 点取消、或点了遮罩，都走到 fail —— 都按「不报了」处理
      fail: () => resolve(null),
    })
  })
}

/**
 * 拍一张 / 选一张照片，返回本地临时路径；取消返回 null。
 *
 * <p>相机与相册都给（{@code sourceType: ['camera', 'album']}），
 * 由系统自己弹选择 —— 小程序上就是那个「拍照 / 从相册选择」的原生面板。
 * 不在我们这边再问一遍「拍照还是相册」，少一层弹层就少一次困惑。
 */
function choosePhoto(): Promise<string | null> {
  return new Promise((resolve) => {
    uni.chooseImage({
      count: 1,
      sizeType: ['original'],
      sourceType: ['camera', 'album'],
      success: (res) => resolve(res.tempFilePaths?.[0] ?? null),
      fail: () => resolve(null),
    })
  })
}

function confirmModal(title: string, content: string): Promise<boolean> {
  return new Promise((resolve) => {
    uni.showModal({
      title,
      content,
      success: (res) => resolve(res.confirm),
      fail: () => resolve(false),
    })
  })
}
/**
 * 收一行文本。
 *
 * @returns 用户填的内容（可能是空串）；点了取消返回 null ——
 *          调用方靠 null 与空串区分「取消」和「不填原因直接拒绝」
 */
function promptModal(title: string, placeholder: string): Promise<string | null> {
  return new Promise((resolve) => {
    uni.showModal({
      title,
      editable: true,
      placeholderText: placeholder,
      success: (res) => resolve(res.confirm ? (res.content ?? '') : null),
      fail: () => resolve(null),
    })
  })
}

async function handleLogout() {
  const ok = await confirmModal('退出登录', '退出后需要重新输入账号密码，确认退出吗？')
  if (!ok) return

  try {
    await logout()
  } catch {
    // 服务端调用失败不影响本地登出（比如 token 已过期），忽略即可
  } finally {
    // 退出登录必须先把实时连接断掉，否则人已经登出了，
    // 那条连接还挂在那里等着收推送（后端此时只知道「某个 token 的连接」）
    closeTaskSocket()
    clearToken()
    uni.reLaunch({ url: '/pages/login/login' })
  }
}

/** 时间展示：去掉秒，能省一点横向空间 */
function fmtTime(value?: string) {
  return value ? value.slice(0, 16) : '—'
}
</script>

<template>
  <view class="my">
    <!-- ============ 顶部：当前飞手 + 退出 ============ -->
    <view class="hero">
      <view class="hero__left">
        <view class="hero__hello">你好，{{ displayName || '飞手' }}</view>
        <view class="hero__sub">
          共 {{ total }} 条派给你的任务
          <text v-if="user && user.state === 'busy'" class="hero__busy">作业中</text>
          <!-- 实时连接状态：让飞手知道「现在是推给我的」还是「得自己下拉刷新」 -->
          <text class="hero__conn" :class="{ 'is-on': realtimeOn }">
            {{ realtimeOn ? '实时' : '未连接' }}
          </text>
        </view>
      </view>
      <view class="hero__logout" @click="handleLogout">退出</view>
    </view>

    <!-- ============ 任务列表 ============ -->
    <view v-for="task in tasks" :key="task.id" class="pilot-card task" @click="toggleExpand(task)">
      <view class="task__head">
        <text class="pilot-code">{{ task.taskNo }}</text>
        <view class="task__tags">
          <!--
            未处理告警角标。口径是「还没处理的」（pending + handling）——
            角标回答的是「有什么要管的」，处理完自己就消失了。
            有 critical 时用红色：不点进去也能看出严重程度
          -->
          <text
            v-if="(task.alertCount || 0) > 0"
            class="tag"
            :class="(task.criticalAlertCount || 0) > 0 ? 'tag--red' : 'tag--amber'"
          >
            {{ task.alertCount }} 条告警
          </text>
          <text class="tag" :class="`tag--${toneOf(priorityOptions, task.priority)}`">
            {{ labelOf(priorityOptions, task.priority) }}
          </text>
          <text class="tag" :class="`tag--${toneOf(statusOptions, task.status)}`">
            {{ labelOf(statusOptions, task.status) }}
          </text>
        </view>
      </view>

      <view class="task__name">{{ task.taskName }}</view>

      <view class="task__meta">
        <text class="task__metaLabel">计划时间</text>
        <text class="task__metaValue">
          {{ fmtTime(task.planStartTime) }} ~ {{ fmtTime(task.planEndTime) }}
        </text>
      </view>
      <view class="task__meta">
        <text class="task__metaLabel">执行设备</text>
        <text class="task__metaValue">{{ task.droneCode || '—' }}</text>
      </view>

      <!--
        展开区。任务本身的字段都在列表接口的返回里，点开不额外发请求；
        但下面的告警清单要单独拉一次（见 loadAlerts 的注释：懒加载 + 缓存）
      -->
      <view v-if="expandedId === task.id" class="task__detail">
        <view class="task__detailRow">
          <text class="task__detailLabel">任务内容</text>
          <text class="task__detailText">{{ task.routeInfo || '—' }}</text>
        </view>
        <view class="task__detailRow">
          <text class="task__detailLabel">补充说明</text>
          <text class="task__detailText">{{ task.description || '—' }}</text>
        </view>
        <view class="task__detailRow">
          <text class="task__detailLabel">实际起止</text>
          <text class="task__detailText">
            {{ fmtTime(task.actualStartTime) }} ~ {{ fmtTime(task.actualEndTime) }}
          </text>
        </view>
        <view class="task__detailRow">
          <text class="task__detailLabel">派发人</text>
          <text class="task__detailText">{{ task.createByName || '—' }}</text>
        </view>
        <view class="task__detailRow">
          <text class="task__detailLabel">创建时间</text>
          <text class="task__detailText">{{ fmtTime(task.createTime) }}</text>
        </view>
      </view>

      <!--
        本任务的告警清单。
        飞手对告警**没有任何按钮**：不认领、不关闭 —— 销账是管理侧的职责。
        「自己报的问题自己关掉」等于没有监督（设计文档第十二章）。
      -->
      <view v-if="expandedId === task.id" class="task__alerts">
        <view class="task__alertsHead">
          <text class="task__alertsTitle">本任务的告警</text>
          <text v-if="alertLoading === task.id" class="task__alertsHint">加载中…</text>
        </view>

        <view v-if="!(alertsByTask[task.id] || []).length" class="task__alertsEmpty">
          {{ alertLoading === task.id ? '' : '暂无告警' }}
        </view>

        <view
          v-for="alert in alertsByTask[task.id] || []"
          :key="alert.id"
          class="alert"
          :class="{ 'alert--action': alert.pilotActionRequired }"
        >
          <view class="alert__head">
            <text class="tag" :class="`tag--${toneOf(alertSeverityOptions, alert.severity)}`">
              {{ alert.severityLabel }}
            </text>
            <text class="alert__code">{{ alert.alertCodeLabel }}</text>
            <text class="tag" :class="`tag--${toneOf(alertStatusOptions, alert.status)}`">
              {{ alert.statusLabel }}
            </text>
          </view>

          <view class="alert__message">{{ alert.message }}</view>

          <!--
            需要飞手动手的告警：把「该做什么」摆在最显眼的位置。
            但**只提示、不给按钮** —— 他返航了、设备状态会自己变，
            那比他自己点一个「已处理」可信得多
          -->
          <view v-if="alert.pilotActionRequired && alert.actionHint" class="alert__action">
            该做什么：{{ alert.actionHint }}
          </view>

          <view v-if="(alert.attachments || []).length" class="alert__files">
            附件：{{ attachmentNames(alert) }}
          </view>

          <view class="alert__time">
            {{ fmtTime(alert.observedAt) }}　{{ alert.deviceCode || '飞手上报' }}
          </view>
        </view>
      </view>

      <!--
        完成任务（报告线）。
        **只在执行中显示** —— 只有这个状态才谈得上收尾。

        刻意做成展开区里的一个常驻表单，而不是一串弹窗：
        上一版把「带照片」做成弹层里的一步，结果没人找得到（见 handleReport 的注释）。
        表单常驻，飞手一眼就知道收工要填什么。

        `@click.stop` 是**必须的**：整张卡片上挂着「点一下展开/收起」，
        而这个表单里全是输入控件。不拦住冒泡的话，手指一碰输入框，
        事件就冒到卡片上把表单收掉了 —— 表现是「输入框点不进去」。
      -->
      <view
        v-if="expandedId === task.id && task.status === 'executing'"
        class="finish"
        @click.stop
      >
        <view class="finish__title">完成任务</view>

        <!--
          只有声明、没有设备数据 → 标出来。
          完成**不强制**要有旁证（强制会把「图传断了」变成「飞手无法收工」），
          但飞手要知道自己在承担什么
        -->
        <view v-if="task.evidenceState !== 'confirmed'" class="finish__warn">
          本次执行的依据：{{ labelOf(evidenceStateOptions, task.evidenceState) }}
          —— 还没有设备数据佐证
        </view>

        <view class="finish__row">
          <text class="finish__label">结论</text>
          <view class="finish__choices">
            <text
              v-for="opt in resultOptions"
              :key="opt.value"
              class="chip"
              :class="{ 'chip--on': formOf(task).result === opt.value }"
              @click.stop="setResult(task, opt.value)"
            >
              {{ opt.label }}
            </text>
          </view>
        </view>

        <textarea
          class="finish__input"
          :value="formOf(task).summary"
          placeholder="执行总结：做了什么、结果如何"
          :maxlength="500"
          @input="onFormInput(task, 'summary', $event)"
        />
        <textarea
          class="finish__input"
          :value="formOf(task).problemLog"
          placeholder="遇到的问题与处置（选填；标记失败时必填）"
          :maxlength="500"
          @input="onFormInput(task, 'problemLog', $event)"
        />

        <view class="finish__row">
          <text class="finish__label">飞行时长</text>
          <input
            class="finish__num"
            type="digit"
            :value="formOf(task).flightHours"
            placeholder="小时"
            @input="onFormInput(task, 'flightHours', $event)"
          />
          <text class="finish__label finish__label--tight">架次</text>
          <input
            class="finish__num"
            type="number"
            :value="formOf(task).flightCount"
            placeholder="架次"
            @input="onFormInput(task, 'flightCount', $event)"
          />
        </view>
        <view class="finish__hint">时长按「开始执行到现在」预填，收工后请核对</view>

        <!-- 现场照片：与「现场上报」那张分开存，两份用途不同，别互相覆盖 -->
        <view class="photo">
          <template v-if="formOf(task).photoId">
            <text class="photo__done">报告照片：{{ formOf(task).photoName }}</text>
            <text class="photo__remove" @click.stop="removeFinishPhoto(task)">移除</text>
          </template>
          <button
            v-else
            class="pilot-btn pilot-btn--ghost photo__btn"
            :disabled="photoUploading === uploadKey('finish', task.id)"
            @click.stop="pickFinishPhoto(task)"
          >
            {{
              photoUploading === uploadKey('finish', task.id)
                ? '照片上传中…'
                : '拍摄 / 选择报告照片（选填）'
            }}
          </button>
        </view>

        <view class="finish__actions">
          <button
            class="pilot-btn pilot-btn--ghost finish__action"
            :disabled="settling === task.id"
            @click.stop="handleSettle(task, true)"
          >
            标记失败
          </button>
          <button
            class="pilot-btn pilot-btn--primary finish__action"
            :disabled="settling === task.id"
            @click.stop="handleSettle(task, false)"
          >
            {{ settling === task.id ? '提交中…' : '提交完成' }}
          </button>
        </view>
      </view>

      <!--
        现场异常上报入口。
        **不限任务状态**：飞机落地后才发现机身损伤也算现场问题，
        而那时候任务可能已经完成了
      -->
      <view v-if="expandedId === task.id" class="task__reportRow">
        <view class="finish__title">上报现场问题</view>
        <!--
          照片：一个**一直看得见**的按钮，点了就拍/选并立刻上传。
          不做进上报流程里 —— 前后两版都藏进流程里，试的人两次都没找到，
          所以结论是：**藏起来就等于没有这个功能**。

          标签要和上面「完成任务」里的那张区分开：两张照片的去处不同
          （这张附在现场问题上，那张随报告提交）。原来两处都叫
          「拍摄 / 选择现场照片」，页面上同时出现两个一模一样的按钮，
          分不清哪张进哪里 —— 那是加完成表单时留下的问题。
        -->
        <view class="photo">
          <template v-if="pendingPhoto && pendingPhoto.taskId === task.id">
            <text class="photo__done">问题照片：{{ pendingPhoto.fileName }}</text>
            <text class="photo__remove" @click.stop="removePhoto">移除</text>
          </template>
          <button
            v-else
            class="pilot-btn pilot-btn--ghost photo__btn"
            :disabled="photoUploading === uploadKey('report', task.id)"
            @click.stop="pickPhoto(task)"
          >
            {{
              photoUploading === uploadKey('report', task.id)
                ? '照片上传中…'
                : '拍摄 / 选择问题照片（选填）'
            }}
          </button>
        </view>

        <button
          class="pilot-btn pilot-btn--ghost task__reportBtn"
          :disabled="reporting === task.id"
          @click.stop="handleReport(task)"
        >
          {{ reporting === task.id ? '上报中…' : '上报现场问题' }}
        </button>
      </view>

      <!--
        航线与打卡（只看不编辑）：派发之后才有意义 —— 待派发的任务还没定下来去哪飞。
        地图是原生的，编辑类交互（画区、拖航点）一律在管理端，这里只给「看 + 打卡」。
      -->
      <view
        v-if="task.status === 'dispatched' || task.status === 'executing'"
        class="task__actions"
      >
        <button class="pilot-btn pilot-btn--ghost task__action" @click.stop="openMap(task)">
          航线与打卡
        </button>
      </view>

      <view class="task__toggle">{{ expandedId === task.id ? '收起 ▴' : '展开详情 ▾' }}</view>

      <!--
        飞手的两个选择。
        只在「已派发」时出现 —— 那正是「活派给我了，但还没开干」的状态：
        要么开始，要么不接。已经起飞（执行中）之后就不能再拒了，该走「执行失败」。
      -->
      <view v-if="task.status === 'dispatched'" class="task__actions">
        <button
          class="pilot-btn pilot-btn--ghost task__action"
          :disabled="submitting === task.id"
          @click.stop="handleRefuse(task)"
        >
          拒绝执行
        </button>
        <button
          class="pilot-btn pilot-btn--primary task__action"
          :disabled="submitting === task.id"
          @click.stop="handleStart(task)"
        >
          {{ submitting === task.id ? '处理中…' : '开始执行' }}
        </button>
      </view>
    </view>

    <!-- ============ 各种状态 ============ -->
    <view v-if="!loaded && loading" class="hint">加载中…</view>

    <view v-else-if="loaded && tasks.length === 0" class="hint">
      <view class="hint__title">暂时没有派给你的任务</view>
      <view class="hint__sub">管理员派发任务后会出现在这里，下拉可刷新</view>
    </view>

    <view v-else-if="loading" class="hint hint--foot">加载中…</view>
    <view v-else-if="!hasMore && tasks.length > 0" class="hint hint--foot">没有更多了</view>
  </view>
</template>

<style lang="scss" scoped>
.my {
  min-height: 100vh;
  padding-bottom: 40rpx;
}

/* ---------- 顶部 ---------- */
.hero {
  display: flex;
  align-items: center;
  padding: 40rpx 32rpx 28rpx;
}

.hero__left {
  flex: 1;
  min-width: 0;
}

.hero__hello {
  font-size: 38rpx;
  font-weight: 600;
}

.hero__sub {
  margin-top: 10rpx;
  font-size: 24rpx;
  color: $pilot-muted;
}

.hero__busy {
  margin-left: 12rpx;
  padding: 2rpx 12rpx;
  border-radius: 999rpx;
  font-size: 20rpx;
  color: $pilot-tag-amber-fg;
  background: $pilot-tag-amber-bg;
  border: 1rpx solid $pilot-tag-amber-bd;
}

/* 实时连接状态：连上是绿的，没连是灰的 */
.hero__conn {
  margin-left: 12rpx;
  padding: 2rpx 12rpx;
  border-radius: 999rpx;
  font-size: 20rpx;
  color: $pilot-tag-grey-fg;
  background: $pilot-tag-grey-bg;
  border: 1rpx solid $pilot-tag-grey-bd;

  &.is-on {
    color: $pilot-tag-green-fg;
    background: $pilot-tag-green-bg;
    border-color: $pilot-tag-green-bd;
  }
}

.hero__logout {
  flex: none;
  padding: 10rpx 26rpx;
  border-radius: 999rpx;
  font-size: 24rpx;
  color: $pilot-text-2;
  background: $pilot-ghost-bg;
  border: 1rpx solid $pilot-line-strong;
}

/* ---------- 任务卡片 ---------- */
.task__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.task__tags {
  display: flex;
  gap: 10rpx;
}

.task__name {
  margin-top: 16rpx;
  font-size: 34rpx;
  font-weight: 600;
  line-height: 1.5;
}

.task__meta {
  display: flex;
  margin-top: 12rpx;
  font-size: 26rpx;
  line-height: 1.6;
}

.task__metaLabel {
  flex: none;
  width: 140rpx;
  color: $pilot-muted;
}

.task__metaValue {
  flex: 1;
  color: $pilot-text-2;
}

/* 展开区：与上面拉开一点距离，并用一条细线分隔 */
.task__detail {
  margin-top: 24rpx;
  padding-top: 20rpx;
  border-top: 1rpx solid $pilot-line;
}

.task__detailRow {
  display: flex;
  margin-bottom: 16rpx;

  &:last-child {
    margin-bottom: 0;
  }
}

.task__detailLabel {
  flex: none;
  width: 140rpx;
  font-size: 25rpx;
  color: $pilot-muted;
}

.task__detailText {
  flex: 1;
  font-size: 25rpx;
  line-height: 1.7;
  color: $pilot-text-2;
  word-break: break-all;
}

.task__toggle {
  margin-top: 20rpx;
  text-align: center;
  font-size: 24rpx;
  color: $pilot-cyan;
}

/* ---------- 本任务的告警 ---------- */
.task__alerts {
  margin-top: 24rpx;
  padding-top: 20rpx;
  border-top: 1rpx solid $pilot-line;
}

.task__alertsHead {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.task__alertsTitle {
  font-size: 26rpx;
  font-weight: 600;
}

.task__alertsHint {
  font-size: 22rpx;
  color: $pilot-muted;
}

.task__alertsEmpty {
  margin-top: 12rpx;
  font-size: 24rpx;
  color: $pilot-muted;
}

.alert {
  margin-top: 16rpx;
  padding: 18rpx 20rpx;
  border-radius: 14rpx;
  background: $pilot-input-bg;
  border: 1rpx solid $pilot-line;
}

/*
  需要飞手动手的那几条：左边加一条红色竖线。
  整屏扫一眼就能找到「哪条在叫我做事」—— 这是这张清单最主要的信息
*/
.alert--action {
  border-left: 6rpx solid $pilot-red;
}

.alert__head {
  display: flex;
  align-items: center;
  gap: 10rpx;
}

.alert__code {
  flex: 1;
  min-width: 0;
  font-size: 26rpx;
  color: $pilot-text;
}

.alert__message {
  margin-top: 10rpx;
  font-size: 25rpx;
  line-height: 1.6;
  color: $pilot-text-2;
  word-break: break-all;
}

.alert__action {
  margin-top: 10rpx;
  font-size: 25rpx;
  line-height: 1.6;
  color: $pilot-tag-red-fg;
}

.alert__files {
  margin-top: 8rpx;
  font-size: 23rpx;
  color: $pilot-muted;
  word-break: break-all;
}

.alert__time {
  margin-top: 10rpx;
  font-size: 22rpx;
  color: $pilot-muted;
}

/* ---------- 完成任务（报告线） ---------- */
.finish {
  margin-top: 24rpx;
  padding-top: 20rpx;
  border-top: 1rpx solid $pilot-line;
}

.finish__title {
  font-size: 28rpx;
  font-weight: 600;
}

/* 「只有声明、没有设备数据」的提示：琥珀色，不是错误但要看一眼 */
.finish__warn {
  margin-top: 12rpx;
  padding: 12rpx 16rpx;
  border-radius: 12rpx;
  font-size: 23rpx;
  line-height: 1.6;
  color: $pilot-tag-amber-fg;
  background: $pilot-tag-amber-bg;
  border: 1rpx solid $pilot-tag-amber-bd;
}

.finish__row {
  display: flex;
  align-items: center;
  gap: 12rpx;
  margin-top: 20rpx;
}

.finish__label {
  flex: none;
  font-size: 25rpx;
  color: $pilot-muted;
}

.finish__label--tight {
  margin-left: 8rpx;
}

.finish__choices {
  display: flex;
  gap: 12rpx;
}

/* 结论的选项：选中态一眼能看出来 */
.chip {
  padding: 8rpx 26rpx;
  border-radius: 999rpx;
  font-size: 25rpx;
  color: $pilot-text-2;
  background: $pilot-ghost-bg;
  border: 1rpx solid $pilot-line-strong;
}

.chip--on {
  color: $pilot-btn-fg;
  background: linear-gradient(100deg, $pilot-cyan, #38bdf8);
  border-color: transparent;
}

.finish__input {
  width: 100%;
  box-sizing: border-box;
  margin-top: 16rpx;
  padding: 16rpx 18rpx;
  min-height: 110rpx;
  border-radius: 12rpx;
  font-size: 25rpx;
  line-height: 1.6;
  color: $pilot-text;
  background: $pilot-input-bg;
  border: 1rpx solid $pilot-line;
}

.finish__num {
  flex: 1;
  padding: 12rpx 16rpx;
  border-radius: 12rpx;
  font-size: 25rpx;
  color: $pilot-text;
  background: $pilot-input-bg;
  border: 1rpx solid $pilot-line;
}

.finish__hint {
  margin-top: 10rpx;
  font-size: 22rpx;
  color: $pilot-muted;
}

.finish__actions {
  display: flex;
  gap: 20rpx;
  margin-top: 24rpx;
}

.finish__action {
  flex: 1;
  height: 76rpx;
  font-size: 28rpx;
  margin: 0;
}

/* ---------- 上报现场问题 ---------- */
.task__reportRow {
  margin-top: 24rpx;
}

/* 照片：一直可见的一行，避免它藏在流程里没人发现 */
.photo {
  display: flex;
  align-items: center;
  gap: 16rpx;
  margin-bottom: 16rpx;
}

.photo__btn {
  flex: 1;
  height: 72rpx;
  font-size: 26rpx;
  margin: 0;
}

.photo__done {
  flex: 1;
  font-size: 24rpx;
  line-height: 1.6;
  color: $pilot-tag-green-fg;
  word-break: break-all;
}

.photo__remove {
  flex: none;
  padding: 6rpx 20rpx;
  border-radius: 999rpx;
  font-size: 22rpx;
  color: $pilot-tag-red-fg;
  background: $pilot-tag-red-bg;
  border: 1rpx solid $pilot-tag-red-bd;
}

.task__reportBtn {
  width: 100%;
  height: 76rpx;
  font-size: 28rpx;
  margin: 0;
}

/* 飞手的两个选择：拒绝在左、开始执行在右（主操作放右边，符合习惯） */
.task__actions {
  display: flex;
  gap: 20rpx;
  margin-top: 24rpx;
}

.task__action {
  flex: 1;
  height: 76rpx;
  font-size: 28rpx;
  margin: 0;
}

/* ---------- 空状态 / 加载 ---------- */
.hint {
  padding: 120rpx 48rpx;
  text-align: center;
  color: $pilot-muted;
  font-size: 26rpx;
  line-height: 1.9;
}

.hint--foot {
  padding: 40rpx 48rpx 20rpx;
  font-size: 24rpx;
}

.hint__title {
  font-size: 30rpx;
  color: $pilot-text-2;
  margin-bottom: 12rpx;
}
</style>
