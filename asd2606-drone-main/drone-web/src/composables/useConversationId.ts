import { ref } from 'vue'

/**
 * 会话标识在 localStorage 里的键。
 *
 * <p>只存会话 id，**不存聊天内容**：内容留在服务端，浏览器本地不留一份
 * ——少一个地方可能泄露，也少一份会跟服务端不一致的副本。
 */
const STORAGE_KEY = 'agent-conversation-id'

/**
 * 生成一个会话标识。
 *
 * <p>刻意只用字母、数字与连字符：后端对 `conversationId` 有字符集约束
 * （它会原样写进记忆文件的格式里，带 `|` 或换行会把记录切歪）。
 * 生成端就守住这一点，别把校验留给服务端去发现。
 */
function newConversationId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID()
  }
  // 老浏览器兜底：时间戳 + 随机串，同样落在允许的字符集内
  return `c-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`
}

function existingOrCreate(): { id: string; restored: boolean } {
  const stored = localStorage.getItem(STORAGE_KEY)
  if (stored) {
    return { id: stored, restored: true }
  }
  const created = newConversationId()
  localStorage.setItem(STORAGE_KEY, created)
  return { id: created, restored: false }
}

/**
 * 会话标识的生命周期：**关闭面板再打开保持不变**，点「新建会话」才换一个；
 * id 记在本地，所以刷新页面会接着上一次的会话。
 *
 * <h3>刷新之后为什么面板是空的</h3>
 * id 会恢复，服务端那边的上下文也是连续的（助手确实还记得），
 * 但**面板上不会自动回填历史消息**——接口里没有「取某会话聊天记录」这一条。
 * 所以 {@code restored} 要暴露出去，由界面给一句提示，
 * 免得用户以为助手失忆了、又把话重复问一遍。
 */
export function useConversationId() {
  const initial = existingOrCreate()
  const conversationId = ref(initial.id)

  /** 当前会话是不是从本地恢复来的（用于界面提示） */
  const restored = ref(initial.restored)

  /** 换一个新会话并落盘；返回新 id */
  function startNew(): string {
    conversationId.value = newConversationId()
    localStorage.setItem(STORAGE_KEY, conversationId.value)
    restored.value = false
    return conversationId.value
  }

  return { conversationId, restored, startNew }
}
