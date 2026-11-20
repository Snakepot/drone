import { beforeEach, describe, expect, it, vi } from 'vitest'

const { postMock } = vi.hoisted(() => ({ postMock: vi.fn() }))
vi.mock('@/api/request', () => ({ http: { post: postMock, get: vi.fn(), delete: vi.fn() } }))
vi.mock('element-plus', () => ({ ElMessage: { error: vi.fn(), success: vi.fn() } }))

import { chat, confirmChat } from '@/api/agent'

describe('危险操作的确认接口（REQ-AGENT-0006）', () => {
  beforeEach(() => postMock.mockReset())

  it('POST 到 /agent/chat/confirm，带上 pendingId 与 approve', async () => {
    postMock.mockResolvedValue({ reply: '已执行' })

    const result = await confirmChat({ conversationId: 'c1', pendingId: 'p1', approve: true })

    expect(result).toEqual({ reply: '已执行' })
    const [url, body] = postMock.mock.calls[0] as [string, Record<string, unknown>]
    expect(url).toBe('/agent/chat/confirm')
    expect(body).toEqual({ conversationId: 'c1', pendingId: 'p1', approve: true })
  })

  it('chat 响应里的 pending 原样透出（前端据此弹确认框）', async () => {
    postMock.mockResolvedValue({
      reply: '这项操作需要你确认后才会执行。',
      pending: { pendingId: 'p1', tool: 'delete_device', summary: 'delete_device（参数 {"id":12}）' },
    })

    const result = await chat({ conversationId: 'c1', message: '删设备12' })

    expect(result.pending?.pendingId).toBe('p1')
    expect(result.pending?.tool).toBe('delete_device')
  })
})
