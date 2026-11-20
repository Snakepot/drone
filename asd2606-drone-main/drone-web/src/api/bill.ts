import { http } from './request'

/**
 * 上下文账单接口（REQ-AGENT-0025）。
 *
 * <p>默认查自己的；`scope=all` 需要管理员角色（后端会再判一次，前端隐藏菜单不算边界）。
 */
export interface BillRecord {
  ts: string
  traceId: string
  userId: number
  conversationId: string
  /** 本次请求内的第几次模型调用 */
  round: number
  model: string
  fingerprint: string
  estimateTotal: number
  kindTokens: Record<string, number>
  toolsTokens: number
  toolCount: number
  promptTokens: number | null
  cachedTokens: number | null
  hitRate: number | null
  latencyMs: number
  toolNames: string[]
}

export interface BillSummary {
  requests: number
  promptTokens: number
  cachedTokens: number
  hitRate: number
  avgContextTokens: number
  /** 出现过的前缀指纹数量；同配置应为 1 */
  fingerprints: number
  byKind: { kind: string; tokens: number }[]
  byDay: { day: string; promptTokens: number; cachedTokens: number }[]
  topTools: { tool: string; calls: number }[]
}

export interface BillQuery {
  /** yyyy-MM-dd */
  from?: string
  /** yyyy-MM-dd */
  to?: string
  conversationId?: string
  limit?: number
  /** self（默认）/ all（仅管理员） */
  scope?: 'self' | 'all'
}

export function fetchBills(params: BillQuery = {}) {
  return http.get<BillRecord[]>('/agent/chat/bills', params)
}

export function fetchBillSummary(params: BillQuery = {}) {
  return http.get<BillSummary>('/agent/chat/bills/summary', params)
}
