import { ref } from 'vue'

/**
 * 后台端的任务通知 WebSocket。
 *
 * <h2>它收到什么</h2>
 * 飞手操作后服务端推过来的消息，目前有两类：
 * <ul>
 *   <li>{@code taskStarted} —— 飞手开始执行了（提示性）</li>
 *   <li>{@code taskRefused} —— 飞手拒绝执行，<b>需要管理员动手重新指派</b>（重点）</li>
 * </ul>
 * 派发任务时的 {@code taskDispatched} 是推给飞手的，管理员这边收不到。
 *
 * <h2>为什么 token 放在地址里</h2>
 * 浏览器原生的 `WebSocket` <b>不能自定义请求头</b>，所以没法像普通请求那样带
 * `Authorization`。只能把 token 拼在查询参数上（后端两种都认）。
 * 代价是 token 会出现在访问日志里 —— 学习环境先这样，生产建议给日志里的 token 打码。
 *
 * <h2>为什么连接是「模块级单例」</h2>
 * 这些 ref 定义在模块作用域，不在函数里 —— 所以整个应用共用同一份状态与同一条连接，
 * 不管多少个组件调用 `useTaskSocket()`。要是定义在函数内部，每个组件都会各连一条，
 * 同一条通知会被弹好几遍。
 */
export interface TaskPushMessage {
  type: string
  data?: Record<string, unknown>
  timestamp?: string
}

/** 界面上要展示的一条通知 */
export interface TaskNotification {
  id: number
  type: string
  title: string
  /** 详情行，按顺序展示 */
  lines: string[]
  /** 需要管理员动手的（拒绝、严重告警）为 true —— 界面会显著区分且不自动消失 */
  needsAction: boolean
  /** 点「去处理」跳到这个任务的列表页 */
  taskNo?: string
  /**
   * 「去处理」按钮跳到哪。
   *
   * <p>原来写死跳 `/task`（只有任务通知），加了告警通知之后就不能写死了 ——
   * 告警该跳 `/alert`。用可选字段给个默认值，比在组件里按 type 分支干净。
   */
  path?: string
  /** 「去处理」按钮上的字，不传就用默认的 */
  actionLabel?: string
}

const HEARTBEAT_INTERVAL = 30000
const RECONNECT_BASE = 1000
const RECONNECT_MAX = 30000
const MAX_ATTEMPTS = 5

/** 普通通知停留多久自动消失（毫秒）；需要处理的那种不自动消失 */
const AUTO_DISMISS_MS = 6000

const connected = ref(false)
const notifications = ref<TaskNotification[]>([])

let socket: WebSocket | null = null
let heartbeatTimer: ReturnType<typeof setInterval> | null = null
let reconnectTimer: ReturnType<typeof setTimeout> | null = null
let attempts = 0
let closedByUs = false
let nextId = 1

/** 每条通知各自的销毁定时器，手动关掉时要一并清掉 */
const dismissTimers = new Map<number, ReturnType<typeof setTimeout>>()

/**
 * WebSocket 地址：从当前页面地址推导，走 Vite 代理的同源 `/api`。
 *
 * <p>从 `location` 推导而不是写死端口，好处是换端口、换域名、上生产都不用改代码。
 */
function socketUrl(token: string): string {
  const scheme = location.protocol === 'https:' ? 'wss' : 'ws'
  return `${scheme}://${location.host}/api/ws/task?token=${encodeURIComponent(token)}`
}

export function useTaskSocket() {
  return { connected, notifications, start, stop, dismiss, clearAll }
}

/** 连上（已连或正在连时是空操作，重复调用安全） */
function start() {
  const token = localStorage.getItem('token')
  if (!token) return
  if (socket && (socket.readyState === WebSocket.OPEN || socket.readyState === WebSocket.CONNECTING)) {
    return
  }

  closedByUs = false

  const ws = new WebSocket(socketUrl(token))
  socket = ws

  ws.onopen = () => {
    connected.value = true
    attempts = 0
    startHeartbeat()
  }

  ws.onmessage = (event) => {
    if (typeof event.data !== 'string') return
    try {
      handleMessage(JSON.parse(event.data) as TaskPushMessage)
    } catch {
      // 服务端只发 JSON，真出现脏数据就忽略，别让页面崩在一条消息上
    }
  }

  ws.onclose = () => {
    connected.value = false
    stopHeartbeat()
    socket = null
    scheduleReconnect()
  }

  ws.onerror = () => {
    // onclose 紧随其后，重连统一放在那里安排，这里不重复
    connected.value = false
  }
}

/** 断开（退出登录、离开后台时调用）。主动关不触发重连 */
function stop() {
  closedByUs = true
  stopHeartbeat()
  clearReconnectTimer()

  if (socket) {
    socket.close(1000, 'leaving')
    socket = null
  }
  connected.value = false
}

type MessageListener = (message: TaskPushMessage) => void

/**
 * 业务消息的订阅者。
 *
 * <p>通知浮层订阅它来弹提示，任务页订阅它来重查列表 ——
 * 同一条消息可以被多处消费，各自干各自的事。
 *
 * <p><b>为什么不搞一个「数据版本号」让大家 watch</b>：
 * 版本号是个全局约定，谁改了、谁该响应，散在各处；
 * 直接订阅消息更直白 —— 谁关心、谁注册，看得见。
 */
const listeners = new Set<MessageListener>()

/**
 * 订阅任务消息（收到推送时回调）。
 *
 * @returns 取消订阅的函数。**组件卸载时一定要调**，否则页面销毁了还在被回调，
 *          而且监听器会一直堆在集合里
 */
export function onTaskMessage(listener: MessageListener): () => void {
  listeners.add(listener)
  return () => {
    listeners.delete(listener)
  }
}

/** 手动关掉一条通知 */
function dismiss(id: number) {
  const timer = dismissTimers.get(id)
  if (timer) {
    clearTimeout(timer)
    dismissTimers.delete(id)
  }
  notifications.value = notifications.value.filter((item) => item.id !== id)
}

function clearAll() {
  notifications.value.forEach((item) => dismiss(item.id))
}

// ---------------------------------------------------------------------------
// 内部实现
// ---------------------------------------------------------------------------

function handleMessage(message: TaskPushMessage) {
  if (message.type === 'ready') {
    attempts = 0
    return
  }
  if (message.type === 'pong' || message.type === 'unknown') {
    return
  }

  // 先通知订阅者「有变化了」—— 它们会各自去重查数据（推送只当信号，数据以查询为准）。
  // 放在构造通知之前：将来加了新消息类型、通知浮层还不认识它时，
  // 页面该刷新仍然会刷新，不会因为「没人认领这条消息」就漏掉刷新
  listeners.forEach((listener) => {
    try {
      listener(message)
    } catch {
      // 某个订阅者出错不该连累其它订阅者
    }
  })

  const data = message.data || {}
  const taskName = str(data.taskName)
  const taskNo = str(data.taskNo)
  const pilotName = str(data.pilotName)
  const priority = str(data.priority)

  let notification: Omit<TaskNotification, 'id'> | null = null

  if (message.type === 'taskRefused') {
    // 拒绝是唯一需要管理员动手的一类，所以带的信息最多、样式最重
    notification = {
      type: message.type,
      title: '飞手拒绝执行',
      lines: [
        `${taskNo}　${taskName}`,
        `飞手：${pilotName}　优先级：${priority}`,
        str(data.reason) ? `原因：${str(data.reason)}` : '原因：未填写',
        '任务已退回「待派发」，可重新指派',
      ],
      needsAction: true,
      taskNo,
    }
  } else if (message.type === 'taskStarted') {
    notification = {
      type: message.type,
      title: '飞手已开始执行',
      lines: [`${taskNo}　${taskName}`, `飞手：${pilotName}　设备：${str(data.droneCode) || '—'}`],
      needsAction: false,
      taskNo,
    }
  } else if (message.type === 'alertRaised') {
    // 设备报警：管理员要尽快处理（设计文档：通知的目的只是要尽快告诉能处理告警的人）
    //
    // `critical` 用 needsAction=true —— 界面会显著区分且不自动消失，
    // 因为它可能意味着「飞机在天上出事了」，错过几分钟的代价不一样。
    // `warning` 就让它在几秒后自己消失，否则一屏告警会把别的通知顶掉。
    const severity = str(data.severity)
    const isCritical = severity === 'critical'
    notification = {
      type: message.type,
      title: `${str(data.severityLabel) || '告警'}：${str(data.alertCodeLabel) || str(data.alertCode)}`,
      lines: [
        str(data.message) || '—',
        `设备：${str(data.deviceCode) || '—'}`,
        // 后端给的「需要飞手在现场动手」的提示，直接展示 —— 前端不解释它
        str(data.actionHint) ? `需飞手：${str(data.actionHint)}` : '点击查看并处理',
      ],
      needsAction: isCritical,
      path: '/alert',
      actionLabel: '去处理告警',
    }
  }

  if (!notification) return

  const id = nextId++
  notifications.value = [{ id, ...notification }, ...notifications.value].slice(0, 5)

  // 需要处理的不自动消失 —— 管理员没看见就等于没通知
  if (!notification.needsAction) {
    dismissTimers.set(id, setTimeout(() => dismissById(id), AUTO_DISMISS_MS))
  }
}

function dismissById(id: number) {
  dismissTimers.delete(id)
  notifications.value = notifications.value.filter((item) => item.id !== id)
}

function startHeartbeat() {
  stopHeartbeat()
  heartbeatTimer = setInterval(() => {
    if (socket && socket.readyState === WebSocket.OPEN) {
      socket.send(JSON.stringify({ type: 'ping' }))
    }
  }, HEARTBEAT_INTERVAL)
}

function stopHeartbeat() {
  if (heartbeatTimer !== null) {
    clearInterval(heartbeatTimer)
    heartbeatTimer = null
  }
}

function clearReconnectTimer() {
  if (reconnectTimer !== null) {
    clearTimeout(reconnectTimer)
    reconnectTimer = null
  }
}

/** 退避重连：1→2→4→8→16→30 秒封顶，连续失败 5 次就停（交给下次进页面再连） */
function scheduleReconnect() {
  if (closedByUs || attempts >= MAX_ATTEMPTS) return

  const delay = Math.min(RECONNECT_BASE * 2 ** attempts, RECONNECT_MAX)
  attempts += 1

  clearReconnectTimer()
  reconnectTimer = setTimeout(() => {
    reconnectTimer = null
    start()
  }, delay)
}

function str(value: unknown): string {
  return value === null || value === undefined ? '' : String(value)
}
