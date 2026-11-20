import { beforeEach, describe, expect, it, vi } from 'vitest'

// fetchArtifact 只依赖 http.download；替掉 axios 封装，不在 node 里真发请求。
const { downloadMock } = vi.hoisted(() => ({ downloadMock: vi.fn() }))
vi.mock('@/api/request', () => ({
  http: { post: vi.fn(), get: vi.fn(), delete: vi.fn(), download: downloadMock },
}))
vi.mock('element-plus', () => ({ ElMessage: { error: vi.fn(), success: vi.fn() } }))

import { fetchArtifact } from '@/api/agent'

describe('fetchArtifact —— 取回 HTML 产物', () => {
  beforeEach(() => downloadMock.mockReset())

  it('走统一封装（自带鉴权）取 Blob，而不是把 URL 直接塞进 iframe', async () => {
    const blob = new Blob(['<html></html>'], { type: 'text/html' })
    downloadMock.mockResolvedValue(blob)

    const result = await fetchArtifact('abc-123')

    expect(downloadMock).toHaveBeenCalledWith('/agent/chat/artifacts/abc-123')
    expect(result).toBe(blob)
  })

  it('对 id 做 URL 编码', async () => {
    downloadMock.mockResolvedValue(new Blob(['x']))

    await fetchArtifact('a b/c')

    expect(downloadMock).toHaveBeenCalledWith('/agent/chat/artifacts/a%20b%2Fc')
  })
})
