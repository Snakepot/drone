import { ElMessage } from 'element-plus'
import { http } from '@/api/request'

/**
 * 对话请求。
 *
 * <h3>为什么不带历史消息</h3>
 * 服务端自己持有会话（`REQ-AGENT-0003`），历史由它组装，客户端只报
 * 「哪个会话」和「这次说了什么」。所以这里没有 messages 字段——
 * 客户端也就没有地方可以伪造轮次。
 */
/** 续跑令牌（REQ-AGENT-0029）：继续/撤销已完成的写操作时原样带回 */
export interface AgentResumeToken {
  /** 已完成的写操作；续跑按「工具 + 参数摘要」对回 operationId */
  operations: { tool: string; operationId: string; argsDigest: string }[]
  /** 已捕获对象 ID 的写操作，顺序即完成顺序（撤销反着来） */
  captured: { tool: string; objectId: string; operationId: string }[]
}

export interface AgentChatParam {
  /** 会话标识，由 {@link useConversationId} 生成并持久化 */
  conversationId: string
  /** 用户这一轮说的话 */
  message: string
  /** 续跑令牌（REQ-AGENT-0029）：继续/撤销已完成的写操作时带上 */
  resumeToken?: AgentResumeToken
}

/** 一次请求的上下文账单摘要（REQ-AGENT-0025） */
export interface AgentStreamBill {
  /** 所有轮次实际输入 token 之和 */
  promptTokens: number
  /** 最后一轮的上下文估算（当前上下文有多大） */
  contextTokens: number
  /** 缓存命中 token 之和 */
  cachedTokens: number
  /** 缓存命中率（%） */
  hitRate: number
  /** 最后一轮估算合计 */
  estimateTotal: number
  /** 最后一轮分类 token：SYSTEM / TOOL_DEFINITIONS / SESSION / ... */
  kindTokens: Record<string, number>
  /** 本次用到的工具（去重） */
  toolNames: string[]
  /** 模型调用轮数 */
  rounds: number
}

/** 取消报告里的一个写步骤（REQ-AGENT-0029） */
export interface AgentCancellationStep {
  /** 写操作 id；未开始的步骤为 null */
  operationId?: string | null
  tool: string
  /** succeeded / unknown / not_started / failed */
  status: string
  objectId?: string | null
  summary?: string
}

/** 取消后的部分完成报告 */
export interface AgentCancellationReport {
  cancelled: boolean
  completed: AgentCancellationStep[]
  /** 明确失败、确定未生效的写步骤 */
  failed: AgentCancellationStep[]
  notStarted: AgentCancellationStep[]
  unknown: AgentCancellationStep[]
  /** continue / revoke / keep */
  options: string[]
  resumeToken?: AgentResumeToken | null
}

/** 计划里的一个步骤（REQ-AGENT-0031） */
export interface AgentPlanStep {
  id: string
  title: string
  /** PENDING / RUNNING / DONE / FAILED / SKIPPED */
  status: string
  error?: string | null
}

/** 计划摘要：内置计划展示进度；生成计划在 PENDING_APPROVAL 时等待用户批准 */
export interface AgentPlan {
  planId: string
  name: string
  /** BUILTIN / GENERATED */
  origin: string
  /** PENDING_APPROVAL / COMPLETED / FAILED / REJECTED / CANCELLED */
  status: string
  steps: AgentPlanStep[]
}

/** 流式结束时的汇总（正文、思考用时、待确认、产物、账单、取消报告、计划） */
export interface AgentStreamDone {
  reply: string
  reasoningMs: number
  pending?: AgentPending
  artifacts?: AgentArtifact[]
  bill?: AgentStreamBill
  /** done 事件里的取消报告 */
  report?: AgentCancellationReport
  /** 计划摘要（REQ-AGENT-0031） */
  plan?: AgentPlan
}

/**
 * 流式回调。后端按 `type` 分三类增量，前端分别渲染：
 * 思考（可折叠）、正文（逐字追加）、状态（工具轮次提示）。
 */
export interface AgentStreamHandlers {
  onReasoning?: (delta: string) => void
  onContent?: (delta: string) => void
  onStatus?: (text: string) => void
  onDone?: (payload: AgentStreamDone) => void
  onError?: (message: string) => void
}

/** 会话列表项，字段与后端 SessionSummary 一一对应 */
export interface AgentSessionSummary {
  conversationId: string
  title: string
  startedAt: string
  messageCount: number
}

/** 「待确认」信息，与后端 PendingConfirmation 对应 */
export interface AgentPending {
  pendingId: string
  tool: string
  summary: string
}

/** 一次对话产出的 HTML 文件；正文不在响应里，按 id 再取 */
export interface AgentArtifact {
  id: string
  fileName: string
  sizeBytes: number
}

/** 非流式对话的返回，与后端 ChatResponse 对应 */
export interface AgentChatReply {
  reply: string
  /** 命中危险工具时才有：需要用户确认（见 REQ-AGENT-0006） */
  pending?: AgentPending
  /** 本次生成的 HTML 产物（如运营日报）；没有时为空 */
  artifacts?: AgentArtifact[]
  /** 本次对话的追踪 id，用于日志/回放对齐（REQ-AGENT-0026） */
  traceId?: string
  /** 计划摘要（REQ-AGENT-0031） */
  plan?: AgentPlan
}

/**
 * 取回一个 HTML 产物的内容。
 *
 * <p>走 `http.download`（responseType: blob）而不是普通 get：响应体是 HTML 文件流，
 * 当 JSON 解会报错；鉴权头由统一封装带上，所以不能直接把 URL 塞进 iframe。
 */
export function fetchArtifact(id: string): Promise<Blob> {
  return http.download(`/agent/chat/artifacts/${encodeURIComponent(id)}`)
}

/** 确认 / 拒绝一次危险操作 */
export interface AgentConfirmParam {
  conversationId: string
  pendingId: string
  approve: boolean
}

/**
 * 非流式对话的超时。
 *
 * <p>比 `request.ts` 的全局 10s 长得多：一旦命中工具调用，服务端要**连调两次模型 + 两次外部
 * HTTP**（查天气），实测一次往返约 8～15s，用 10s 会被掐断。
 */
const CHAT_TIMEOUT_MS = 60_000

/**
 * 非流式对话：一次拿到完整回复。
 *
 * <h3>为什么面板用它、而不是流式</h3>
 * 工具调用（`REQ-AGENT-0004`）只在**非流式**路径上实现（见 `ADR-0004`），
 * 走 `/chat` 才能让「查天气」这类能力真正生效。流式（{@link chatStream}）仍保留备用，
 * 但目前没有调用方。
 *
 * <p>走 `request.ts` 的 axios 封装，所以 Token、401/403、业务失败提示都由拦截器统一处理。
 * 路径 {@code /agent/chat}：开发环境由 vite 代理到 drone-agent(8081)。
 */
export function chat(param: AgentChatParam) {
  return http.post<AgentChatReply>('/agent/chat', param, { timeout: CHAT_TIMEOUT_MS })
}

/**
 * 确认 / 拒绝一次危险操作（`REQ-AGENT-0006`）。
 *
 * <p>后端**幂等**：同一个 `pendingId` 只执行一次，重复提交返回「已处理或已过期」。
 * 路径 {@code /agent/chat/confirm}。
 */
export function confirmChat(param: AgentConfirmParam) {
  return http.post<AgentChatReply>('/agent/chat/confirm', param, { timeout: CHAT_TIMEOUT_MS })
}

/** 批准 / 拒绝一份生成计划（REQ-AGENT-0031） */
export interface AgentPlanConfirmParam {
  conversationId: string
  planId: string
  approve: boolean
}

/**
 * 批准或拒绝生成计划。
 *
 * <p>后端幂等：同一 planId 只消费一次，重复提交返回「已处理或已过期」。
 * 批准后同步执行计划并返回最终答复（本期非流式）。
 */
export function confirmPlan(param: AgentPlanConfirmParam) {
  return http.post<AgentChatReply>('/agent/chat/plan/confirm', param, { timeout: CHAT_TIMEOUT_MS })
}

/** 后端 SSE 事件（未用到的字段为 undefined） */
interface StreamEvent {
  type?: 'reasoning' | 'content' | 'status' | 'done' | 'error'
  /** done 事件里的账单摘要 */
  bill?: AgentStreamBill
  /** done 事件里的取消报告 */
  report?: AgentCancellationReport
  /** done 事件里的计划摘要 */
  plan?: AgentPlan
  delta?: string
  text?: string
  reply?: string
  reasoningMs?: number
  pending?: AgentPending
  artifacts?: AgentArtifact[]
  message?: string
  /** 旧协议兼容 */
  error?: string
}

/**
 * 流式对话：逐段回调，直到模型说完或出错。
 *
 * <h3>为什么不用 axios</h3>
 * axios 拿不到「边到边读」的响应流（XHR 的 readyState 只能等整段下载完，
 * 浏览器也不把流式 body 交给它），所以这里直接用 fetch + ReadableStream。
 * 代价是 request.ts 那套拦截器用不上，Token 和 401 得自己处理 ——
 * 下面 {@link handleUnauthorized} 就是补这一点。
 *
 * <h3>路径</h3>
 * {@code /api/agent/chat/stream}：开发环境由 vite 代理转到 drone-agent(8081)，
 * 生产环境需要网关把 {@code /api/agent/**} 转发过去。
 */
export async function chatStream(
  param: AgentChatParam,
  handlers: AgentStreamHandlers,
): Promise<void> {
  const token = localStorage.getItem('token')
  const response = await fetch(`${import.meta.env.VITE_API_BASE_URL}/agent/chat/stream`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: JSON.stringify(param),
  })

  if (!response.ok) {
    // 鉴权失败等：这时响应体是 JsonResult（不是 SSE 流），照普通 JSON 读
    const message = await readErrorMessage(response)
    if (response.status === 401) {
      handleUnauthorized(message)
    }
    throw new Error(message)
  }

  if (!response.body) {
    throw new Error('当前浏览器不支持流式响应')
  }

  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''

  while (true) {
    const { done, value } = await reader.read()
    if (done) break

    buffer += decoder.decode(value, { stream: true })

    // SSE 一个事件以空行结尾，按空行切块；剩下的留在 buffer 里等下一段
    let separator = buffer.indexOf('\n\n')
    while (separator !== -1) {
      const block = buffer.slice(0, separator)
      buffer = buffer.slice(separator + 2)
      // 先把下一个分隔符算出来，再处理本块：下面有 continue，放到末尾会被跳过而死循环
      separator = buffer.indexOf('\n\n')

      const payload = extractEventData(block)
      if (!payload) continue
      const event = JSON.parse(payload) as StreamEvent

      // 旧协议（只有 delta/error）兼容：没有 type 时按正文处理
      const type = event.type ?? (event.error ? 'error' : 'content')
      if (type === 'reasoning') {
        if (event.delta) handlers.onReasoning?.(event.delta)
      } else if (type === 'content') {
        if (event.delta) handlers.onContent?.(event.delta)
      } else if (type === 'status') {
        if (event.text) handlers.onStatus?.(event.text)
      } else if (type === 'done') {
        handlers.onDone?.({
          reply: event.reply ?? '',
          reasoningMs: event.reasoningMs ?? 0,
          pending: event.pending,
          artifacts: event.artifacts,
          bill: event.bill,
          report: event.report,
          plan: event.plan,
        })
      } else if (type === 'error') {
        const message = event.message ?? event.error ?? '请求失败'
        handlers.onError?.(message)
        // 流已经开始，HTTP 状态码改不了；抛出让调用方收尾
        throw new Error(message)
      }
    }
  }
}

/**
 * 我自己的会话列表。
 *
 * <p>走 request.ts 的封装（普通 JSON 请求，不需要流式），
 * 因此 Token、401、错误提示都由那套拦截器统一处理。
 *
 * <p>注意：**还没归档过的会话不在列表里**（新会话要等被淘汰或服务端停机刷盘后才入索引）。
 */
export function listSessions() {
  return http.get<AgentSessionSummary[]>('/agent/chat/sessions')
}

/**
 * 查询当前会话未处理的待确认票据（REQ-AGENT-0029）。
 *
 * <p>票据已经落盘，重启后前端不一定还记得 pendingId；面板打开时用它恢复确认框。
 */
export function fetchPending(conversationId: string) {
  return http.get<AgentPending[]>('/agent/chat/pending', { conversationId })
}

/** 取消一次在途请求（REQ-AGENT-0029）：只置位，立即返回 */
export function cancelChat(conversationId: string) {
  return http.post<boolean>('/agent/chat/cancel', { conversationId })
}

/** 删除一个会话。幂等：删不存在的会话同样返回成功 */
export function deleteSession(conversationId: string) {
  return http.delete<void>(`/agent/chat/sessions/${encodeURIComponent(conversationId)}`)
}

/** 从一块事件里取出 data: 的内容；一个事件有多行 data: 时用换行拼接 */
function extractEventData(block: string): string {
  return block
    .split('\n')
    .filter((line) => line.startsWith('data:'))
    .map((line) => line.slice('data:'.length).replace(/^ /, ''))
    .join('\n')
}

/** 读出错响应体里的 message；读不出来就给个兜底文案 */
async function readErrorMessage(response: Response): Promise<string> {
  try {
    const body = (await response.json()) as { message?: string }
    return body?.message || `请求失败（HTTP ${response.status}）`
  } catch {
    return `请求失败（HTTP ${response.status}）`
  }
}

/**
 * 401 的收尾，行为与 request.ts 的拦截器保持一致：清登录痕迹并回登录页。
 *
 * <p>不得不重写一遍：流式请求绕开了 axios，也就绕开了那套拦截器 ——
 * 不补这一步的话，Token 失效时面板只会显示一句「登录状态已失效」，人却停在原地。
 */
function handleUnauthorized(message: string): void {
  localStorage.removeItem('token')
  localStorage.removeItem('user')
  ElMessage.error(message)
  if (window.location.pathname !== '/login') {
    window.location.href = '/login'
  }
}
