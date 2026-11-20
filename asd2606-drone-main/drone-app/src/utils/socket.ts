/**
 * 任务通知的 WebSocket 客户端。
 *
 * <h2>它负责的四件事</h2>
 * <ol>
 *   <li><b>连接</b>：带上登录 token 连到后端的 `/ws/task`</li>
 *   <li><b>心跳</b>：定期发 ping，让中间的网络设备知道这条连接还活着</li>
 *   <li><b>重连</b>：断了自己接回来，且用「退避」而不是死循环猛试</li>
 *   <li><b>分发</b>：把收到的消息交给页面处理</li>
 * </ol>
 *
 * <h2>一条重要原则</h2>
 * 收到推送后<b>不要直接把消息内容当数据用</b>，而是拿它当「有变化了」的信号，
 * 去调一次接口拿最新数据。理由：推送会丢、会重、会乱序，只有查询结果才是准的。
 * 详见 {@code docs/任务通知.md} 的「消息以谁为准」一节。
 *
 * <h2>Token 怎么带给后端（两端不一样）</h2>
 * - <b>微信小程序</b>：`uni.connectSocket` 支持自定义 `header`，走 `Authorization`，规范且不进 URL
 * - <b>H5</b>：浏览器原生 WebSocket <b>不能自定义请求头</b>，只能把 token 放查询参数里
 *
 * 后端两种都认（见 `TaskWsHandshakeInterceptor`），所以这里用条件编译分开写。
 */
import { WS_BASE_URL, WS_TASK_PATH } from '@/config'
import { getToken } from '@/utils/auth'

/** 心跳间隔：30 秒一次。太密浪费电和流量，太疏则中间设备可能已经掐了连接才发现 */
const HEARTBEAT_INTERVAL = 30000

/** 重连退避的起点与封顶（毫秒） */
const RECONNECT_BASE = 1000
const RECONNECT_MAX = 30000

/** 连续失败多少次后放弃（等下次进页面再连，而不是无限重试耗电） */
const MAX_RECONNECT_ATTEMPTS = 5

export interface TaskPushMessage {
  /** 消息类型，如 taskDispatched */
  type: string
  /** 消息内容；具体字段随 type 而定 */
  data?: Record<string, unknown>
  timestamp?: string
}

type MessageHandler = (message: TaskPushMessage) => void

let socket: UniApp.SocketTask | null = null
/** 连接状态变化的通知者（页面用它显示「实时连接」小标） */
let stateListener: ((connected: boolean) => void) | null = null

let heartbeatTimer: ReturnType<typeof setInterval> | null = null
let reconnectTimer: ReturnType<typeof setTimeout> | null = null
let reconnectAttempts = 0
/** 主动关闭（离开页面/退出登录）时置 true，避免「自己关的还去重连」 */
let closedByUs = false
let connecting = false

/**
 * 建立连接（已连上或正在连时直接返回，重复调用安全）。
 *
 * @param onMessage 收到业务消息时的回调
 * @param onStateChange 连接状态变化回调，用于界面上显示是否实时
 */
export function connectTaskSocket(
  onMessage: MessageHandler,
  onStateChange?: (connected: boolean) => void,
) {
  if (onStateChange) {
    stateListener = onStateChange
  }

  const token = getToken()
  if (!token) {
    // 还没登录，连了也会被后端拒绝，直接不连
    return
  }
  if (socket || connecting) {
    return
  }

  connecting = true
  closedByUs = false

  let url = WS_BASE_URL + WS_TASK_PATH
  const header: Record<string, string> = {}

  // #ifdef H5
  // 浏览器不能自定义 WebSocket 请求头，token 只能放地址里
  url += `?token=${encodeURIComponent(token)}`
  // #endif

  // #ifdef MP-WEIXIN
  // 小程序支持自定义 header，这样 token 不会出现在 URL 与访问日志里
  header.Authorization = `Bearer ${token}`
  // #endif

  const task = uni.connectSocket({ url, header, complete: () => undefined })
  socket = task

  task.onOpen(() => {
    connecting = false
    reconnectAttempts = 0
    notifyState(true)
    startHeartbeat()
  })

  task.onMessage((res) => {
    handleMessage(res.data, onMessage)
  })

  task.onClose(() => {
    connecting = false
    stopHeartbeat()
    notifyState(false)
    socket = null
    scheduleReconnect(onMessage)
  })

  task.onError(() => {
    connecting = false
    stopHeartbeat()
    notifyState(false)
    socket = null
    // onClose 通常也会紧跟着触发，重连接口统一放在 onClose 里，这里不重复排
  })
}

/**
 * 主动断开（离开页面、退出登录时调用）。
 *
 * <p>「主动关」和「被动断」要区分开：主动关不该触发重连，
 * 否则用户退出登录后还在后台偷偷连，既费电又没有意义。
 */
export function closeTaskSocket() {
  closedByUs = true
  stopHeartbeat()
  clearReconnectTimer()

  if (socket) {
    socket.close({ code: 1000, reason: '页面关闭' })
    socket = null
  }
  connecting = false
  notifyState(false)
}

/** 当前是否已连上（页面可用它决定要不要提示「实时」） */
export function isSocketConnected(): boolean {
  return socket !== null && !connecting
}

// ---------------------------------------------------------------------------
// 内部实现
// ---------------------------------------------------------------------------

function handleMessage(raw: unknown, onMessage: MessageHandler) {
  if (typeof raw !== 'string') return

  let message: TaskPushMessage
  try {
    message = JSON.parse(raw)
  } catch {
    // 后端不会发非 JSON 的东西，真出现就忽略，别让页面崩
    return
  }

  // ready / pong 是连接层面的信号，不交给页面
  if (message.type === 'ready') {
    // 收到 ready 说明握手与登记都成功了
    reconnectAttempts = 0
    return
  }
  if (message.type === 'pong' || message.type === 'unknown') {
    return
  }

  onMessage(message)
}

function startHeartbeat() {
  stopHeartbeat()
  heartbeatTimer = setInterval(() => {
    if (!socket) return
    socket.send({
      data: JSON.stringify({ type: 'ping' }),
      // 心跳发失败不用提示用户，交由 onClose 去重连
      fail: () => undefined,
    })
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

/**
 * 安排一次重连。
 *
 * <p><b>退避</b>是重点：第一次 1 秒后试，之后 2、4、8、16、30 秒。
 * 如果失败就固定每秒重试，服务端一重启，所有客户端会在同一瞬间一起冲上来
 * （这叫「惊群」），刚起来的服务端可能又被压垮。
 *
 * <p>连续失败到上限就停下来 —— 交给用户下次进页面时重连，
 * 比在后台无限重试省电。
 */
function scheduleReconnect(onMessage: MessageHandler) {
  if (closedByUs || reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
    return
  }

  const delay = Math.min(RECONNECT_BASE * 2 ** reconnectAttempts, RECONNECT_MAX)
  reconnectAttempts += 1

  clearReconnectTimer()
  reconnectTimer = setTimeout(() => {
    reconnectTimer = null
    connectTaskSocket(onMessage)
  }, delay)
}

function notifyState(connected: boolean) {
  if (stateListener) {
    stateListener(connected)
  }
}
