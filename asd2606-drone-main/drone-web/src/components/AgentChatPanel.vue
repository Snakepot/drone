<script setup lang="ts">
import { computed, nextTick, reactive, ref, watch } from 'vue'
import {
  cancelChat,
  chatStream,
  confirmChat,
  confirmPlan,
  deleteSession,
  fetchPending,
  listSessions,
  type AgentArtifact,
  type AgentChatReply,
  type AgentSessionSummary,
  type AgentCancellationReport,
  type AgentPlan,
  type AgentStreamBill,
  type AgentStreamDone,
} from '@/api/agent'
import { useConversationId } from '@/composables/useConversationId'
import AgentArtifactCard from '@/components/AgentArtifactCard.vue'

/**
 * 面板上显示的一条消息。
 *
 * <p>它只是**显示**用的：真正的会话上下文在服务端，本地的 messages 不参与请求。
 * 所以刷新页面后面板是空的（接口里没有「取某会话聊天记录」），而助手其实还记得
 * ——这一幕靠一条提示说清，见 {@link useConversationId} 的注释。
 */
interface ChatMessage {
  role: 'user' | 'assistant'
  content: string
  /** 助手这一条附带的 HTML 产物（日报等）；没有则为空 */
  artifacts?: AgentArtifact[]
  /** 深度思考的文字（只展示，不入库） */
  reasoning?: string
  /** 思考用时（毫秒），流式结束时由后端给出 */
  reasoningMs?: number
  /** 工具轮次的进度提示 */
  status?: string
  /** 是否仍在流式输出 */
  streaming?: boolean
  /** 本次请求的上下文账单（REQ-AGENT-0025） */
  bill?: AgentStreamBill
  /** 账单明细是否展开 */
  billOpen?: boolean
  /** 取消后的部分完成报告（REQ-AGENT-0029） */
  report?: AgentCancellationReport
  /** 计划摘要与步骤状态（REQ-AGENT-0031） */
  plan?: AgentPlan
}

/** 展开状态由 AdminLayout 用 v-model 控制 */
const open = defineModel<boolean>({ default: false })

const { conversationId, restored, startNew } = useConversationId()

const messages = ref<ChatMessage[]>([])
const input = ref('')
const sending = ref(false)

/** 下一次 send 携带的续跑令牌（REQ-AGENT-0029） */
let resumeForNextSend: AgentCancellationReport['resumeToken'] = null

/** 账单分类的中文名；后端给的是枚举名，前端只做展示映射 */
const BILL_LABELS: Record<string, string> = {
  SYSTEM: '系统提示词',
  TOOL_DEFINITIONS: '工具定义',
  SESSION: '当前会话',
  LONG_TERM: '长期记忆',
  TOOL_CALL: '工具调用',
  TOOL_RESULT: '工具结果',
  COMPACTION: '历史摘要',
  RESUME: '续跑上下文',
}

function formatTokens(value: number): string {
  if (value >= 1_000_000) return `${(value / 1_000_000).toFixed(1)}M`
  if (value >= 1000) return `${(value / 1000).toFixed(1)}k`
  return String(value)
}

/** 分类明细：按 token 降序，条形长度相对最大值，便于一眼看出大头 */
function billEntries(bill: AgentStreamBill) {
  const entries = Object.entries(bill.kindTokens)
    .filter(([, tokens]) => tokens > 0)
    .map(([key, tokens]) => ({ key, tokens, label: BILL_LABELS[key] ?? key }))
    .sort((left, right) => right.tokens - left.tokens)
  const max = Math.max(...entries.map((entry) => entry.tokens), 1)
  return entries.map((entry) => ({ ...entry, percent: Math.round((entry.tokens * 100) / max) }))
}

/** 是否全屏。全屏时面板铺满窗口，正文居中限宽，读起来不散 */
const fullscreen = ref(false)

/** 是否正在看历史会话列表 */
const historyOpen = ref(false)

const sessions = ref<AgentSessionSummary[]>([])
const loadingSessions = ref(false)

const listRef = ref<HTMLElement | null>(null)
const inputRef = ref<HTMLTextAreaElement | null>(null)

const welcome = '你好，我是无人机管理平台的智能助手。设备、任务、航线相关的问题都可以问我。'

// 首次展开时放一条欢迎语并聚焦输入框
watch(open, async (value) => {
  if (!value) {
    // 收起时退出全屏与历史视图，否则下次打开还是那个状态，会让人以为打不开
    fullscreen.value = false
    historyOpen.value = false
    return
  }
  // 恢复来的会话不加欢迎语：它不是新会话，加一句「你好」反而像是刚开始
  if (messages.value.length === 0 && !restored.value) {
    messages.value.push({ role: 'assistant', content: welcome })
  }
  await nextTick()
  inputRef.value?.focus()
  await scrollToBottom()
  await restorePending()
})

/** 已处理过的票据 id：避免「done 弹过一次、打开面板又弹一次」 */
const handledPendingIds = new Set<string>()

/**
 * 重启后恢复确认框（REQ-AGENT-0029）：票据在服务端落盘，前端按会话查一次。
 * 查询失败不打扰用户——确认框只是补救，不影响正常对话。
 */
async function restorePending() {
  if (sending.value || messages.value.some((message) => message.streaming)) return
  try {
    const pending = await fetchPending(conversationId.value)
    const first = pending.find((item) => !handledPendingIds.has(item.pendingId))
    if (!first) return
    handledPendingIds.add(first.pendingId)
    const reply = await resolveConfirmation({ reply: '', pending: first })
    if (reply) {
      messages.value.push({ role: 'assistant', content: reply })
      await scrollToBottom()
    }
  } catch {
    // 查询/确认失败保持静默：用户可以重新发起操作
  }
}

async function scrollToBottom() {
  await nextTick()
  const el = listRef.value
  if (el) el.scrollTop = el.scrollHeight
}

/** 流式时每个 token 都滚会掉帧：合并到每帧一次 */
let scrollScheduled = false
function scrollSoon() {
  if (scrollScheduled) return
  scrollScheduled = true
  requestAnimationFrame(() => {
    scrollScheduled = false
    void scrollToBottom()
  })
}

/** 思考块展开状态：未手动切换时，流式中且还没正文就展开 */
const thinkOpen = reactive<Record<number, boolean>>({})

function isThinkOpen(index: number, message: ChatMessage): boolean {
  if (thinkOpen[index] !== undefined) return thinkOpen[index]
  return Boolean(message.streaming && !message.content)
}

function toggleThink(index: number, message: ChatMessage) {
  thinkOpen[index] = !isThinkOpen(index, message)
}

/** 等待动画只在「还没有任何思考/正文」时显示 */
const showTyping = computed(() => {
  if (!sending.value) return false
  const last = messages.value[messages.value.length - 1]
  return !last || last.role !== 'assistant' || (!last.content && !last.reasoning)
})

async function send() {
  const text = input.value.trim()
  if (!text || sending.value) return

  messages.value.push({ role: 'user', content: text })
  input.value = ''
  sending.value = true

  // 先插入一条「流式中」的助手消息，后续增量都写进它
  const assistant = reactive<ChatMessage>({
    role: 'assistant',
    content: '',
    reasoning: '',
    reasoningMs: 0,
    status: '',
    streaming: true,
  })
  messages.value.push(assistant)
  scrollSoon()

  try {
    await chatStream(
      {
        conversationId: conversationId.value,
        message: text,
        resumeToken: resumeForNextSend ?? undefined,
      },
      {
        onReasoning: (delta) => {
          assistant.reasoning = (assistant.reasoning || '') + delta
          scrollSoon()
        },
        onContent: (delta) => {
          assistant.content += delta
          assistant.status = ''
          scrollSoon()
        },
        onStatus: (status) => {
          assistant.status = status
          scrollSoon()
        },
        onDone: (done) => {
          assistant.content = done.reply || assistant.content
          assistant.reasoningMs = done.reasoningMs
          assistant.artifacts = done.artifacts
          assistant.bill = done.bill
          assistant.report = done.report
          assistant.plan = done.plan
          assistant.status = ''
          assistant.streaming = false
          if (done.pending) {
            assistant.content = done.reply || '这项操作需要你确认后才会执行。'
            handledPendingIds.add(done.pending.pendingId)
            void finishPending(assistant, done)
          } else if (!assistant.content) {
            assistant.content = '（模型没有返回内容）'
          }
          scrollSoon()
        },
      },
    )
  } catch (error) {
    assistant.streaming = false
    assistant.status = ''
    const reason = error instanceof Error ? error.message : '请求失败'
    assistant.content = assistant.content
      ? `${assistant.content}\n（请求失败：${reason}）`
      : `（请求失败：${reason}）`
  } finally {
    sending.value = false
    resumeForNextSend = null
    scrollSoon()
  }
}

/** 命中危险工具时弹确认框；确认/拒绝后再把下一轮答复递归处理 */
async function resolveConfirmation(response: AgentChatReply): Promise<string> {
  const pending = response.pending
  if (!pending) return response.reply

  let approve = false
  try {
    await ElMessageBox.confirm(pending.summary, '这一步需要你确认', {
      confirmButtonText: '确认执行',
      cancelButtonText: '取消',
      type: 'warning',
    })
    approve = true
  } catch {
    approve = false // 关掉弹框即「拒绝」
  }

  const next = await confirmChat({
    conversationId: conversationId.value,
    pendingId: pending.pendingId,
    approve,
  })
  return resolveConfirmation(next)
}

/** 流式结束但命中危险工具：沿用现有确认弹窗，确认后把最终答复写回同一条消息 */
async function finishPending(assistant: ChatMessage, done: AgentStreamDone) {
  try {
    const reply = await resolveConfirmation({ reply: done.reply, pending: done.pending })
    assistant.content = reply || assistant.content
  } catch (error) {
    const reason = error instanceof Error ? error.message : '请求失败'
    assistant.content += `（请求失败：${reason}）`
  } finally {
    scrollSoon()
  }
}

const PLAN_STATUS_LABELS: Record<string, string> = {
  PENDING_APPROVAL: '等待批准',
  COMPLETED: '已完成',
  FAILED: '执行失败',
  REJECTED: '已拒绝',
  EXPIRED: '已过期',
  CANCELLED: '已取消',
}

const PLAN_STEP_LABELS: Record<string, string> = {
  PENDING: '待执行',
  RUNNING: '执行中',
  DONE: '完成',
  FAILED: '失败',
  SKIPPED: '未执行',
}

function planStatusLabel(status: string): string {
  return PLAN_STATUS_LABELS[status] ?? status
}

function planStepLabel(status: string): string {
  return PLAN_STEP_LABELS[status] ?? status
}

/** 批准/拒绝生成计划：批准后同步执行，结果写回同一条助手消息 */
async function resolvePlan(assistant: ChatMessage, approve: boolean) {
  const plan = assistant.plan
  if (!plan || plan.status !== 'PENDING_APPROVAL') return
  plan.status = 'RUNNING'
  try {
    const response = await confirmPlan({
      conversationId: conversationId.value,
      planId: plan.planId,
      approve,
    })
    assistant.plan = response.plan ?? plan
    assistant.content = response.reply || assistant.content
    assistant.artifacts = response.artifacts ?? assistant.artifacts
  } catch (error) {
    plan.status = 'PENDING_APPROVAL'
    const reason = error instanceof Error ? error.message : '请求失败'
    assistant.content += `（计划审批失败：${reason}）`
  } finally {
    scrollSoon()
  }
}

/** 继续未完成的步骤：复用 resumeToken，让 MCP 幂等层去重已完成的写操作 */
function continueFromReport(report: AgentCancellationReport) {
  if (!report.resumeToken) return
  resumeForNextSend = report.resumeToken
  input.value = '继续刚才未完成的步骤。'
  void send()
}

/** 撤销已完成的写操作：仍是普通对话，危险操作会走确认流程 */
function revokeFromReport(report: AgentCancellationReport) {
  if (!report.resumeToken) return
  resumeForNextSend = report.resumeToken
  input.value = '撤销刚才已完成的写操作：请先逐项查询确认，再按逆序停用/删除；不可撤销的如实说明。'
  void send()
}

/** 停止当前请求：只通知服务端置位，实际结果由随后的 done.report 给出 */
async function cancelCurrent() {
  if (!sending.value) return
  try {
    await cancelChat(conversationId.value)
  } catch {
    // 请求可能刚好结束，忽略即可
  }
}

/** Enter 发送，Shift+Enter 换行 */
function handleKeydown(event: KeyboardEvent) {
  if (event.key !== 'Enter' || event.shiftKey) return
  event.preventDefault()
  void send()
}

/** 开一个新会话：换 id、清面板，并把视图切回对话 */
function newConversation() {
  startNew()
  messages.value = [{ role: 'assistant', content: welcome }]
  historyOpen.value = false
  void nextTick(() => inputRef.value?.focus())
}

async function toggleHistory() {
  historyOpen.value = !historyOpen.value
  if (historyOpen.value) {
    await refreshSessions()
  }
}

async function refreshSessions() {
  loadingSessions.value = true
  try {
    sessions.value = await listSessions()
  } catch {
    // 失败提示已由 request.ts 的拦截器统一弹出，这里只需别把界面卡在 loading 上
  } finally {
    loadingSessions.value = false
  }
}

async function removeSession(summary: AgentSessionSummary) {
  try {
    await ElMessageBox.confirm(
      `删除后内容无法恢复。确认删除「${summary.title || summary.conversationId}」吗？`,
      '删除会话',
      { confirmButtonText: '确认删除', cancelButtonText: '取消', type: 'warning' },
    )
  } catch {
    // 用户点了取消
    return
  }

  try {
    await deleteSession(summary.conversationId)
  } catch {
    // 提示已由拦截器弹出；列表保持原样，用户可重试
    return
  }

  if (summary.conversationId === conversationId.value) {
    // 删掉的正是当前会话：必须换一个 id，否则下一条消息会把这个 id 又建回来
    newConversation()
  }
  ElMessage.success('已删除')
  await refreshSessions()
}

function formatTime(iso: string): string {
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) return ''
  const pad = (value: number) => String(value).padStart(2, '0')
  return `${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`
}

function close() {
  fullscreen.value = false
  historyOpen.value = false
  open.value = false
}
</script>

<template>
  <Transition name="agent-slide">
    <aside v-show="open" class="agent" :class="{ 'is-fullscreen': fullscreen }" aria-label="智能助手">
      <div class="agent__inner">
        <header class="agent__head">
          <div class="agent__brand">
            <span class="agent__mark">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round">
                <path d="M12 3.5 13.6 9l5.4 1.7-5.4 1.7L12 18l-1.6-5.6L5 10.7 10.4 9 12 3.5Z" />
              </svg>
            </span>
            <div class="agent__title">
              <strong>智能助手</strong>
              <span>DroneAgent</span>
            </div>
          </div>

          <div class="agent__actions">
            <button
              class="agent__icon-btn"
              type="button"
              title="新建会话"
              aria-label="新建会话"
              @click="newConversation"
            >
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round">
                <path d="M12 5.5v13M5.5 12h13" />
              </svg>
            </button>

            <button
              class="agent__icon-btn"
              :class="{ 'is-active': historyOpen }"
              type="button"
              title="历史会话"
              aria-label="历史会话"
              @click="toggleHistory"
            >
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round">
                <path d="M4.5 6.5h15M4.5 12h15M4.5 17.5h9" />
              </svg>
            </button>

            <button
              class="agent__icon-btn"
              type="button"
              :title="fullscreen ? '退出全屏' : '全屏'"
              :aria-label="fullscreen ? '退出全屏' : '全屏'"
              @click="fullscreen = !fullscreen"
            >
              <svg v-if="fullscreen" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round">
                <path d="M4.5 9H9V4.5M19.5 9H15V4.5M19.5 15H15v4.5M4.5 15H9v4.5" />
              </svg>
              <svg v-else viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round">
                <path d="M9 4.5H4.5V9M15 4.5h4.5V9M15 19.5h4.5V15M9 19.5H4.5V15" />
              </svg>
            </button>

            <button class="agent__icon-btn" type="button" title="收起" aria-label="收起" @click="close">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round">
                <path d="m7 7 10 10M17 7 7 17" />
              </svg>
            </button>
          </div>
        </header>

        <!-- 历史会话列表 -->
        <div v-if="historyOpen" class="agent__history">
          <p v-if="loadingSessions" class="agent__hint">正在加载…</p>
          <p v-else-if="sessions.length === 0" class="agent__hint">
            还没有历史会话。当前这条会话在结束之后才会出现在这里。
          </p>
          <ul v-else class="sessions">
            <li v-for="item in sessions" :key="item.conversationId" class="session">
              <div class="session__main">
                <span class="session__title">{{ item.title || '(无标题)' }}</span>
                <span class="session__meta">{{ formatTime(item.startedAt) }} · {{ item.messageCount }} 条</span>
              </div>
              <button
                class="session__delete"
                type="button"
                title="删除这条会话"
                aria-label="删除这条会话"
                @click="removeSession(item)"
              >
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round">
                  <path d="M5.5 7.5h13M9.5 7.5V6a1 1 0 0 1 1-1h3a1 1 0 0 1 1 1v1.5M7.5 7.5l.8 10a1 1 0 0 0 1 .9h5.4a1 1 0 0 0 1-.9l.8-10" />
                </svg>
              </button>
            </li>
          </ul>
        </div>

        <!-- 对话 -->
        <div v-else ref="listRef" class="agent__list">
          <!-- 恢复了上次的会话：面板是空的，但服务端还记得——不说清会被当成失忆 -->
          <p v-if="restored && messages.length === 0" class="agent__hint">
            已接上之前的会话。历史消息暂不支持在这里回看，但助手仍记得前面说过的内容。
          </p>

          <div
            v-for="(message, index) in messages"
            :key="index"
            class="msg"
            :class="[`msg--${message.role}`, { 'msg--has-artifact': message.artifacts?.length }]"
          >
            <!-- 思考过程：流式中默认展开，正文开始后自动折叠 -->
            <div
              v-if="message.role === 'assistant' && (message.reasoning || message.reasoningMs)"
              class="think"
            >
              <button class="think__head" type="button" @click="toggleThink(index, message)">
                <span class="think__title">
                  {{
                    message.streaming && !message.content
                      ? '正在深度思考…'
                      : `已深度思考${
                          message.reasoningMs
                            ? `（用时 ${(message.reasoningMs / 1000).toFixed(0)} 秒）`
                            : ''
                        }`
                  }}
                </span>
                <span
                  class="think__chevron"
                  :class="{ 'think__chevron--open': isThinkOpen(index, message) }"
                  >›</span
                >
              </button>
              <div v-show="isThinkOpen(index, message)" class="think__body">
                {{ message.reasoning }}
              </div>
            </div>

            <!-- 工具轮次的进度提示 -->
            <div v-if="message.role === 'assistant' && message.status" class="msg__status">
              {{ message.status }}
            </div>

            <p v-if="message.content">{{ message.content }}</p>

            <!-- 本次消耗：数据来自 done.bill，不额外请求 -->
            <div v-if="message.role === 'assistant' && message.bill" class="bill">
              <button class="bill__head" type="button" @click="message.billOpen = !message.billOpen">
                <span>
                  本次 {{ formatTokens(message.bill.contextTokens) }} token · 缓存
                  {{ message.bill.hitRate.toFixed(1) }}%
                </span>
                <span class="bill__chevron" :class="{ 'bill__chevron--open': message.billOpen }">›</span>
              </button>
              <div v-if="message.billOpen" class="bill__body">
                <div v-for="entry in billEntries(message.bill)" :key="entry.key" class="bill__row">
                  <span class="bill__label">{{ entry.label }}</span>
                  <span class="bill__bar"><i :style="{ width: entry.percent + '%' }"></i></span>
                  <span class="bill__value">{{ formatTokens(entry.tokens) }}</span>
                </div>
                <p class="bill__foot">
                  共 {{ message.bill.rounds }} 轮模型调用，实际输入
                  {{ formatTokens(message.bill.promptTokens) }} token
                  <template v-if="message.bill.toolNames.length">
                    ，用到 {{ message.bill.toolNames.length }} 个工具
                  </template>
                </p>
              </div>
            </div>

            <div v-if="message.plan" class="plan">
              <p class="plan__title">
                {{ message.plan.origin === 'BUILTIN' ? '内置流程' : '执行计划' }} ·
                {{ message.plan.name }}
                <span class="plan__status">{{ planStatusLabel(message.plan.status) }}</span>
              </p>
              <ol class="plan__steps">
                <li
                  v-for="step in message.plan.steps"
                  :key="step.id"
                  class="plan__step"
                  :class="`plan__step--${step.status.toLowerCase()}`"
                >
                  <span class="plan__step-title">{{ step.title }}</span>
                  <span class="plan__step-status">{{ planStepLabel(step.status) }}</span>
                  <span v-if="step.error" class="plan__step-error">{{ step.error }}</span>
                </li>
              </ol>
              <div v-if="message.plan.status === 'PENDING_APPROVAL'" class="plan__actions">
                <button type="button" class="btn" @click="resolvePlan(message, true)">
                  批准并执行
                </button>
                <button type="button" class="btn" @click="resolvePlan(message, false)">拒绝</button>
              </div>
              <p v-else-if="message.plan.status === 'REJECTED'" class="plan__hint">
                已拒绝该计划，未执行任何步骤。
              </p>
            </div>

            <div v-if="message.report" class="report">
              <p class="report__title">已取消本次请求</p>
              <p v-if="message.report.completed.length" class="report__line">
                <b>已完成：</b>
                <span v-for="step in message.report.completed" :key="step.operationId || step.tool">
                  {{ step.tool }}{{ step.objectId ? `（ID ${step.objectId}）` : '' }}；
                </span>
              </p>
              <p v-if="message.report.unknown.length" class="report__line report__line--warn">
                <b>结果未知：</b>
                <span v-for="step in message.report.unknown" :key="step.operationId || step.tool">
                  {{ step.tool }}；
                </span>
              </p>
              <p v-if="message.report.failed.length" class="report__line report__line--warn">
                <b>失败（未生效）：</b>
                <span v-for="step in message.report.failed" :key="step.operationId || step.tool">
                  {{ step.tool }}；
                </span>
              </p>
              <p v-if="message.report.notStarted.length" class="report__line">
                <b>未开始：</b>
                <span v-for="step in message.report.notStarted" :key="step.tool">{{ step.tool }}；</span>
              </p>
              <p class="report__hint">取消不会撤销已完成的步骤；如需撤销，请告诉我。</p>
              <div v-if="message.report.resumeToken" class="report__actions">
                <button type="button" class="btn" @click="continueFromReport(message.report)">
                  继续
                </button>
                <button
                  v-if="message.report.resumeToken.captured.length"
                  type="button"
                  class="btn"
                  @click="revokeFromReport(message.report)"
                >
                  撤销
                </button>
              </div>
            </div>

            <AgentArtifactCard
              v-for="artifact in message.artifacts"
              :key="artifact.id"
              :artifact="artifact"
            />
          </div>

          <!-- 非流式：等待期间显示动画，回复一到就整段出现 -->
          <div v-if="showTyping" class="msg msg--assistant msg--typing">
            <span></span><span></span><span></span>
          </div>
        </div>

        <footer v-if="!historyOpen" class="agent__foot">
          <textarea
            ref="inputRef"
            v-model="input"
            rows="3"
            placeholder="输入问题，Enter 发送，Shift+Enter 换行"
            :disabled="sending"
            @keydown="handleKeydown"
          ></textarea>
          <button
            v-if="sending"
            class="btn agent__stop"
            type="button"
            @click="cancelCurrent"
          >
            停止
          </button>
          <button
            class="btn btn--primary agent__send"
            type="button"
            :disabled="sending || !input.trim()"
            @click="send"
          >
            发送
          </button>
        </footer>
      </div>
    </aside>
  </Transition>
</template>

<style scoped>
.agent {
  position: fixed;
  /* 从顶栏下方开始：顶栏的入口按钮保持可见，便于再次点击收起 */
  top: 64px;
  right: 0;
  bottom: 0;
  width: 400px;
  /* 低于任务通知浮层（3000）：有通知时它应当盖在上面 */
  z-index: 1500;
  display: flex;
  background: var(--panel-2);
  border-left: 1px solid var(--line-strong);
  box-shadow: -20px 0 50px rgba(0, 0, 0, 0.35);
  backdrop-filter: blur(18px);
}

/* 全屏：铺满整窗（含顶栏），正文限宽居中 */
.agent.is-fullscreen {
  top: 0;
  left: 0;
  width: auto;
  border-left: none;
}

.agent__inner {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
}

.agent.is-fullscreen .agent__inner {
  max-width: 960px;
  margin: 0 auto;
}

.agent__head {
  flex: none;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 16px 18px;
  border-bottom: 1px solid var(--line);
}

.agent__brand {
  display: flex;
  align-items: center;
  gap: 11px;
}

.agent__mark {
  width: 34px;
  height: 34px;
  flex: none;
  display: grid;
  place-items: center;
  border-radius: 10px;
  color: var(--cyan);
  background: linear-gradient(140deg, rgba(34, 211, 238, 0.2), rgba(139, 92, 246, 0.2));
  border: 1px solid var(--accent-hover);
}

.agent__mark svg {
  width: 19px;
  height: 19px;
}

.agent__title strong {
  display: block;
  font-size: 14px;
  letter-spacing: 1px;
}

.agent__title span {
  font-size: 9.5px;
  letter-spacing: 2.4px;
  color: var(--muted);
  text-transform: uppercase;
}

.agent__actions {
  display: flex;
  align-items: center;
  gap: 6px;
}

.agent__icon-btn {
  width: 30px;
  height: 30px;
  flex: none;
  display: grid;
  place-items: center;
  border-radius: 8px;
  color: var(--muted);
  background: none;
  border: 1px solid transparent;
  cursor: pointer;
  transition: color 0.2s, border-color 0.2s, background 0.2s;
}

.agent__icon-btn:hover,
.agent__icon-btn.is-active {
  color: var(--cyan);
  border-color: var(--accent-hover);
  background: var(--ghost-bg);
}

.agent__icon-btn svg {
  width: 16px;
  height: 16px;
}

.plan {
  margin-top: 10px;
  padding: 10px 12px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  background: var(--el-fill-color-lighter);
}

.plan__title {
  margin: 0 0 8px;
  font-size: 13px;
  font-weight: 600;
}

.plan__status {
  margin-left: 8px;
  font-weight: 400;
  color: var(--el-text-color-secondary);
}

.plan__steps {
  margin: 0;
  padding-left: 20px;
  font-size: 12px;
}

.plan__step {
  margin: 3px 0;
}

.plan__step-status {
  margin-left: 8px;
  color: var(--el-text-color-secondary);
}

.plan__step--done .plan__step-status {
  color: var(--el-color-success);
}

.plan__step--failed .plan__step-status,
.plan__step-error {
  color: var(--el-color-danger);
}

.plan__step-error {
  display: block;
  margin-top: 2px;
  font-size: 12px;
}

.plan__actions {
  display: flex;
  gap: 8px;
  margin-top: 10px;
}

.plan__hint {
  margin: 8px 0 0;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.report {
  margin-top: 8px;
  padding: 8px 10px;
  border: 1px solid var(--line);
  border-radius: 8px;
  background: var(--panel);
  font-size: 12px;
  color: var(--muted);
}

.report__title {
  margin: 0 0 4px;
  color: var(--text-2);
  font-weight: 600;
}

.report__line {
  margin: 2px 0;
  line-height: 1.6;
}

.report__line--warn {
  color: var(--amber, #fbbf24);
}

.report__hint {
  margin: 4px 0 0;
  font-size: 11px;
  color: var(--muted);
}

.report__actions {
  display: flex;
  gap: 8px;
  margin-top: 8px;
}

.report__actions .btn {
  padding: 4px 10px;
  font-size: 12px;
}

.agent__list {
  flex: 1;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 16px 18px;
}

/* ---------- 历史会话 ---------- */
.agent__history {
  flex: 1;
  overflow-y: auto;
  padding: 14px 18px;
}

.sessions {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.session {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 12px;
  border-radius: 10px;
  background: var(--ghost-bg);
  border: 1px solid var(--line);
}

.session__main {
  flex: 1;
  min-width: 0;
}

.session__title {
  display: block;
  font-size: 13px;
  color: var(--text-2);
  /* 标题可能很长，占一行就够 */
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.session__meta {
  display: block;
  margin-top: 3px;
  font-size: 11px;
  color: var(--muted);
}

.session__delete {
  width: 28px;
  height: 28px;
  flex: none;
  display: grid;
  place-items: center;
  border-radius: 8px;
  color: var(--muted);
  background: none;
  border: 1px solid transparent;
  cursor: pointer;
  transition: color 0.2s, border-color 0.2s, background 0.2s;
}

.session__delete:hover {
  color: var(--red);
  border-color: var(--tag-red-bd);
  background: var(--tag-red-bg);
}

.session__delete svg {
  width: 15px;
  height: 15px;
}

.agent__hint {
  margin: 0;
  padding: 10px 12px;
  border-radius: 10px;
  font-size: 12px;
  line-height: 1.7;
  color: var(--muted);
  background: var(--ghost-bg);
  border: 1px dashed var(--line-strong);
}

.msg {
  max-width: 84%;
  padding: 10px 13px;
  border-radius: 12px;
  font-size: 13px;
  line-height: 1.75;
}

.think {
  margin-bottom: 8px;
  padding-left: 10px;
  border-left: 2px solid var(--line-strong);
}

.think__head {
  display: flex;
  align-items: center;
  gap: 6px;
  width: 100%;
  padding: 0;
  border: 0;
  background: none;
  color: var(--text-2);
  font-size: 12px;
  cursor: pointer;
}

.think__title {
  flex: 1;
  text-align: left;
}

.think__chevron {
  display: inline-block;
  transition: transform 0.15s;
}

.think__chevron--open {
  transform: rotate(90deg);
}

.think__body {
  margin-top: 6px;
  max-height: 240px;
  overflow: auto;
  font-size: 12px;
  line-height: 1.7;
  color: var(--text-2);
  white-space: pre-wrap;
  word-break: break-word;
  opacity: 0.85;
}

.msg__status {
  margin-bottom: 6px;
  font-size: 12px;
  color: var(--cyan);
}

.msg--has-artifact {
  /* 报告预览需要完整宽度，不再受气泡 84% 的限制 */
  max-width: 100%;
  width: 100%;
}

.msg p {
  margin: 0;
  /* 保留模型回复里的换行 */
  white-space: pre-wrap;
  word-break: break-word;
}

.msg--assistant {
  align-self: flex-start;
  color: var(--text-2);
  background: var(--ghost-bg);
  border: 1px solid var(--line);
  border-top-left-radius: 4px;
}

.msg--user {
  align-self: flex-end;
  color: var(--text);
  background: linear-gradient(120deg, rgba(34, 211, 238, 0.18), rgba(59, 130, 246, 0.14));
  border: 1px solid var(--accent-hover);
  border-top-right-radius: 4px;
}

.msg--typing {
  display: flex;
  align-items: center;
  gap: 5px;
  padding: 14px 16px;
}

.msg--typing span {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--cyan);
  opacity: 0.5;
  animation: agent-blink 1.2s ease-in-out infinite;
}

.msg--typing span:nth-child(2) {
  animation-delay: 0.2s;
}

.msg--typing span:nth-child(3) {
  animation-delay: 0.4s;
}

@keyframes agent-blink {
  50% {
    opacity: 1;
    transform: translateY(-2px);
  }
}

.agent__foot {
  flex: none;
  display: flex;
  align-items: flex-end;
  gap: 10px;
  padding: 14px 18px 18px;
  border-top: 1px solid var(--line);
}

.agent__foot textarea {
  flex: 1;
  min-height: 72px;
  padding: 10px 12px;
  border-radius: 10px;
  resize: none;
  font-family: inherit;
  font-size: 13px;
  line-height: 1.7;
  color: var(--text);
  background: var(--input-bg);
  border: 1px solid var(--line-strong);
  outline: none;
  transition: border-color 0.2s;
}

.agent__foot textarea:focus {
  border-color: var(--accent-hover);
}

.agent__foot textarea::placeholder {
  color: var(--muted);
}

.agent__send {
  flex: none;
  height: 34px;
}

/* 第二个选择器是为了压过全局的 .btn--primary:hover，否则禁用态仍有 hover 动效 */
.agent__send:disabled,
.agent__send:disabled:hover {
  opacity: 0.45;
  cursor: not-allowed;
  transform: none;
  box-shadow: none;
}

.agent-slide-enter-active,
.agent-slide-leave-active {
  transition: transform 0.26s ease, opacity 0.26s ease;
}

.agent-slide-enter-from,
.agent-slide-leave-to {
  transform: translateX(100%);
  opacity: 0;
}

/* 窄屏：抽屉式会挤没主内容，直接占满 */
@media (max-width: 640px) {
  .agent {
    width: 100%;
    border-left: none;
  }
}
</style>
