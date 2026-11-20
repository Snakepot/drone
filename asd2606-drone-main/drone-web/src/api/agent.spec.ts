import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ElMessage } from 'element-plus'
import { chatStream } from '@/api/agent'

// 用桩件替掉 element-plus：本文件只关心「有没有弹提示」，
// 不关心它的实现，也不该在 node 环境里去加载整个组件库
vi.mock('element-plus', () => ({ ElMessage: { error: vi.fn() } }))

/** 一份合法的请求参数：新契约只有「哪个会话 + 说了什么」 */
const PARAM = { conversationId: 'conv-1', message: '数到3' }

/** 造一个 SSE 响应；每个元素是一段**独立到达**的网络分片 */
function sseResponse(chunks: Array<string | Uint8Array>): Response {
  const encoder = new TextEncoder()
  const body = new ReadableStream<Uint8Array>({
    start(controller) {
      for (const chunk of chunks) {
        controller.enqueue(typeof chunk === 'string' ? encoder.encode(chunk) : chunk)
      }
      controller.close()
    },
  })
  return new Response(body, { status: 200 })
}

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status })
}

/** 一个 content 增量的 SSE 帧 */
function deltaFrame(text: string): string {
  return `data:${JSON.stringify({ delta: text })}\n\n`
}

describe('chatStream —— agent 接口层的流式解析', () => {
  let removeItem: ReturnType<typeof vi.fn>
  let location: { pathname: string; href: string }

  beforeEach(() => {
    removeItem = vi.fn()
    location = { pathname: '/task', href: '' }
    vi.stubGlobal('localStorage', {
      getItem: vi.fn(() => 'fake-token'),
      removeItem,
      setItem: vi.fn(),
    })
    vi.stubGlobal('window', { location })
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    vi.clearAllMocks()
  })

  it('把每个增量依次回调，并带上 Token 与正确的路径', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValue(sseResponse([deltaFrame('1'), deltaFrame('2'), deltaFrame('3')]))
    vi.stubGlobal('fetch', fetchMock)

    const received: string[] = []
    await chatStream(PARAM, { onContent: (text) => received.push(text) })

    expect(received).toEqual(['1', '2', '3'])

    const firstCall = fetchMock.mock.calls.at(0)
    expect(firstCall).toBeDefined()
    const [url, init] = firstCall as [string, RequestInit]
    expect(url).toContain('/agent/chat/stream')
    expect(init.method).toBe('POST')
    expect((init.headers as Record<string, string>).Authorization).toBe('Bearer fake-token')
  })

  it('请求体只带 conversationId 与 message —— 不再回传历史', async () => {
    const fetchMock = vi.fn().mockResolvedValue(sseResponse([deltaFrame('好')]))
    vi.stubGlobal('fetch', fetchMock)

    await chatStream(PARAM, { onContent: vi.fn() })

    const init = (fetchMock.mock.calls.at(0) as [string, RequestInit])[1]
    const body = JSON.parse(init.body as string) as Record<string, unknown>
    expect(body).toEqual({ conversationId: 'conv-1', message: '数到3' })
    // 这一条是重点：历史改由服务端持有，客户端不该再有地方塞轮次
    expect('messages' in body).toBe(false)
  })

  it('同一个分片里含多帧时全部解析', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(sseResponse([deltaFrame('a') + deltaFrame('b')])),
    )

    const received: string[] = []
    await chatStream(PARAM, { onContent: (text) => received.push(text) })

    expect(received).toEqual(['a', 'b'])
  })

  it('多字节字符被网络分片从中间切断也能正确解码', async () => {
    // 按**字节**切，故意把「你好」的一个多字节字符切成两半
    const bytes = new TextEncoder().encode(deltaFrame('你好'))
    const cut = Math.floor(bytes.length / 2)
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(sseResponse([bytes.slice(0, cut), bytes.slice(cut)])),
    )

    const received: string[] = []
    await chatStream(PARAM, { onContent: (text) => received.push(text) })

    expect(received).toEqual(['你好'])
  })

  it('忽略心跳注释与空帧，不打扰调用方', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(sseResponse([': keep-alive\n\n', 'data:\n\n', deltaFrame('ok')])),
    )

    const received: string[] = []
    await chatStream(PARAM, { onContent: (text) => received.push(text) })

    expect(received).toEqual(['ok'])
  })

  it('收到 error 事件时抛出该错误（模型侧失败走这里）', async () => {
    const frame = `data:${JSON.stringify({ error: '未配置 DEEPSEEK_API_KEY，无法调用大模型' })}\n\n`
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(sseResponse([frame])))

    await expect(chatStream(PARAM, { onContent: vi.fn() })).rejects.toThrow(
      '未配置 DEEPSEEK_API_KEY，无法调用大模型',
    )
  })

  it('401 时清掉登录痕迹、弹提示并跳登录页', async () => {
    vi.stubGlobal(
      'fetch',
      vi
        .fn()
        .mockResolvedValue(
          jsonResponse(401, { state: 401, message: '登录状态已失效，请重新登录' }),
        ),
    )

    await expect(chatStream(PARAM, { onContent: vi.fn() })).rejects.toThrow(
      '登录状态已失效，请重新登录',
    )

    expect(removeItem).toHaveBeenCalledWith('token')
    expect(location.href).toBe('/login')
    expect(ElMessage.error).toHaveBeenCalledWith('登录状态已失效，请重新登录')
  })

  it('403 时抛错但不跳登录页（登录了也没权限，跳了没用）', async () => {
    vi.stubGlobal(
      'fetch',
      vi
        .fn()
        .mockResolvedValue(
          jsonResponse(403, { state: 403, message: '没有访问该功能的权限，请联系管理员' }),
        ),
    )

    await expect(chatStream(PARAM, { onContent: vi.fn() })).rejects.toThrow(
      '没有访问该功能的权限',
    )

    expect(location.href).toBe('')
    expect(removeItem).not.toHaveBeenCalled()
  })

  it('409（同一会话正忙）按普通业务错误抛出，不跳登录页', async () => {
    vi.stubGlobal(
      'fetch',
      vi
        .fn()
        .mockResolvedValue(
          jsonResponse(409, { state: 409, message: '该会话正在处理上一条消息，请稍后再发' }),
        ),
    )

    await expect(chatStream(PARAM, { onContent: vi.fn() })).rejects.toThrow(
      '该会话正在处理上一条消息',
    )

    expect(location.href).toBe('')
  })

  it('响应体不是 JSON 时给出兜底文案', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('<html>502</html>', { status: 502 })))

    await expect(chatStream(PARAM, { onContent: vi.fn() })).rejects.toThrow(
      '请求失败（HTTP 502）',
    )
  })

  it('响应体为空（浏览器不支持流式）时明确报错', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(null, { status: 200 })))

    await expect(chatStream(PARAM, { onContent: vi.fn() })).rejects.toThrow(
      '当前浏览器不支持流式响应',
    )
  })

  it('按 type 分发：思考 / 状态 / 正文 / 结束', async () => {
    const reasoning: string[] = []
    const contents: string[] = []
    const statuses: string[] = []
    let done: { reply: string; reasoningMs: number } | null = null

    const body = [
      `data:${JSON.stringify({ type: 'reasoning', delta: '想' })}\n\n`,
      `data:${JSON.stringify({ type: 'status', text: '正在查询' })}\n\n`,
      `data:${JSON.stringify({ type: 'content', delta: '答' })}\n\n`,
      `data:${JSON.stringify({ type: 'done', reply: '答案', reasoningMs: 1234, artifacts: [] })}\n\n`,
    ].join('')

    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(sseResponse([body])))

    await chatStream(PARAM, {
      onReasoning: (delta) => reasoning.push(delta),
      onContent: (delta) => contents.push(delta),
      onStatus: (text) => statuses.push(text),
      onDone: (payload) => {
        done = { reply: payload.reply, reasoningMs: payload.reasoningMs }
      },
    })

    expect(reasoning).toEqual(['想'])
    expect(contents).toEqual(['答'])
    expect(statuses).toEqual(['正在查询'])
    expect(done).toEqual({ reply: '答案', reasoningMs: 1234 })
  })

  it('type=error：先回调 onError，再抛出', async () => {
    const onError = vi.fn()
    const frame = `data:${JSON.stringify({ type: 'error', message: '模型挂了' })}\n\n`
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(sseResponse([frame])))

    await expect(chatStream(PARAM, { onError })).rejects.toThrow('模型挂了')
    expect(onError).toHaveBeenCalledWith('模型挂了')
  })
})
