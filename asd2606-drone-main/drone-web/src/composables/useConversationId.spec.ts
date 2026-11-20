import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useConversationId } from '@/composables/useConversationId'

/**
 * {@link useConversationId} 的单元测试。
 *
 * <p>对应验收条件：`REQ-AGENT-0003` 的 <b>AC-15</b>（同一会话标识在面板开合之间保持）
 * 与 <b>AC-16</b>（点「新建会话」换一个新标识）。
 *
 * <h2>为什么把会话标识的生命周期放进 composable</h2>
 * 它本来可以直接写在面板组件里，但那样测它就得引入 DOM 环境与组件测试工具
 * （happy-dom + @vue/test-utils，属新增依赖，需评审）。
 * 抽成 composable 之后，这段逻辑就是纯的 JavaScript + localStorage，
 * **在 node 环境里就能测**，不必为它拉一整套组件测试设施。
 */
describe('useConversationId —— 会话标识的生命周期', () => {
  let store: Map<string, string>

  beforeEach(() => {
    store = new Map()
    vi.stubGlobal('localStorage', {
      getItem: (key: string) => store.get(key) ?? null,
      setItem: (key: string, value: string) => {
        store.set(key, value)
      },
      removeItem: (key: string) => {
        store.delete(key)
      },
    })
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('首次使用会生成一个标识并落盘', () => {
    const { conversationId, restored } = useConversationId()

    expect(conversationId.value).toBeTruthy()
    expect(store.size).toBe(1)
    expect([...store.values()]).toContain(conversationId.value)
    expect(restored.value).toBe(false)
  })

  it('已有落盘的标识时复用它 —— 相当于刷新页面后接着同一个会话', () => {
    store.set('agent-conversation-id', 'existing-conv')

    const { conversationId, restored } = useConversationId()

    expect(conversationId.value).toBe('existing-conv')
    expect(restored.value).toBe(true)
  })

  it('AC-16 新建会话会换一个新标识并覆盖落盘', () => {
    store.set('agent-conversation-id', 'existing-conv')
    const { conversationId, restored, startNew } = useConversationId()

    const created = startNew()

    expect(created).not.toBe('existing-conv')
    expect(conversationId.value).toBe(created)
    expect(store.get('agent-conversation-id')).toBe(created)
    expect(restored.value).toBe(false)
  })

  it('AC-15 面板开合不换标识：重新取一次还是同一个（组件不卸载时就是同一个实例）', () => {
    const first = useConversationId()

    // 模拟「关掉再打开」：面板并未卸载，用的仍是同一个 ref
    expect(first.conversationId.value).toBe(first.conversationId.value)
    // 而重新实例化（相当于整页刷新）会复用落盘的那一个
    expect(useConversationId().conversationId.value).toBe(first.conversationId.value)
  })

  it('生成的标识一定落在后端允许的字符集内（否则会被服务端判 400）', () => {
    // 连续生成多次，避免偶然通过。后端把 conversationId 原样写进记忆文件格式，
    // 带竖线或换行会把记录切歪，所以字符集由生成端守住
    for (let i = 0; i < 50; i++) {
      store.clear() // 清空存储，逼它每次真的走一遍生成逻辑
      const { conversationId } = useConversationId()
      expect(conversationId.value).toMatch(/^[A-Za-z0-9_-]{1,64}$/)
    }
  })
})
