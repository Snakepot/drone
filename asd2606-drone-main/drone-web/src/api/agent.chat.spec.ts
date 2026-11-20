import { beforeEach, describe, expect, it, vi } from 'vitest'

// agent.ts 的 chat() 只依赖 request.ts 的 http.post：用桩件替掉，不在 node 里真发请求。
// element-plus 也一并替掉 —— agent.ts 顶部 import 了它，不替会在 node 环境里去加载组件库样式。
const { postMock } = vi.hoisted(() => ({ postMock: vi.fn() }))
vi.mock('@/api/request', () => ({ http: { post: postMock, get: vi.fn(), delete: vi.fn() } }))
vi.mock('element-plus', () => ({ ElMessage: { error: vi.fn(), success: vi.fn() } }))

import { chat } from '@/api/agent'

describe('chat —— 非流式对话接口', () => {
  beforeEach(() => postMock.mockReset())

  it('POST 到 /agent/chat，只带 conversationId 与 message —— 不再回传历史', async () => {
    postMock.mockResolvedValue({ reply: '明天多云，适合起飞。' })

    const result = await chat({ conversationId: 'conv-1', message: '北京天气' })

    expect(result).toEqual({ reply: '明天多云，适合起飞。' })
    const [url, body] = postMock.mock.calls[0] as [string, Record<string, unknown>]
    expect(url).toBe('/agent/chat')
    expect(body).toEqual({ conversationId: 'conv-1', message: '北京天气' })
    // 历史由服务端持有，客户端不该再有地方塞轮次
    expect('messages' in body).toBe(false)
  })

  it('为慢请求放宽了超时（工具调用会连调两次模型 + 两次外部 HTTP）', async () => {
    postMock.mockResolvedValue({ reply: 'x' })

    await chat({ conversationId: 'c', message: 'm' })

    const config = (postMock.mock.calls[0] as unknown[])[2] as { timeout?: number } | undefined
    expect(config?.timeout).toBeGreaterThan(10_000)
  })
})
