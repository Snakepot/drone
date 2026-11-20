import { submitCheckin, type TaskCheckinParam, type TaskCheckinVO } from '@/api/task'

/**
 * 离线打卡队列。
 *
 * <h2>为什么必须有它</h2>
 * 野外作业的常态是**没信号**。飞手到点打卡那一刻如果发不出去，
 * 只是弹一句「失败了，请重试」是不够的 —— 等人走到有信号的地方，
 * 当时那个位置和时刻就再也补不回来了（补打卡记的是「现在的我」，
 * 而不是「当时他在那个点位上」）。
 *
 * <h2>重发为什么是安全的</h2>
 * 队列里的每一条都带着 {@code observedAt}（打卡那一刻），
 * 服务端按「任务 + 航点 + 时刻」唯一 —— 同一条补传多少次都只落一条。
 * 所以这里的重试不需要客户端去对账，也**不能**用「现在」当打卡时刻
 * （那样每次重发的键都不一样，会插出一堆重复记录）。
 *
 * <h2>存在哪</h2>
 * {@code uni.setStorageSync}（小程序的本地存储）。它是**同步** API ——
 * 打卡这种「一按就得存住」的操作，异步存储会在 App 被杀掉时丢数据。
 */
const STORAGE_KEY = 'drone:checkin-queue'

/** 队列里的一条 = 一次没发出去的打卡 + 入队时刻（只为排查用） */
export interface QueuedCheckin extends TaskCheckinParam {
  queuedAt: string
}

function readQueue(): QueuedCheckin[] {
  try {
    const raw = uni.getStorageSync(STORAGE_KEY)
    if (!raw) return []
    const list = typeof raw === 'string' ? JSON.parse(raw) : raw
    return Array.isArray(list) ? (list as QueuedCheckin[]) : []
  } catch {
    // 存储里是坏数据（手工改过、或旧版本的格式）：当空队列处理，
    // 而不是让整个打卡功能崩掉
    return []
  }
}

function writeQueue(list: QueuedCheckin[]): void {
  uni.setStorageSync(STORAGE_KEY, JSON.stringify(list))
}

/** 把一次打卡放进本地队列（网络失败时调用） */
export function enqueueCheckin(param: TaskCheckinParam): void {
  const list = readQueue()
  // 同一条（任务+航点+时刻）只留一份：重复入队会让补传时白跑几次
  const exists = list.some(
    (item) =>
      item.taskId === param.taskId &&
      item.seq === param.seq &&
      item.observedAt === param.observedAt,
  )
  if (exists) return

  list.push({ ...param, queuedAt: new Date().toISOString() })
  writeQueue(list)
}

/** 还没传上去的打卡（可按任务筛 —— 页面只关心当前这条任务） */
export function pendingCheckins(taskId?: number): QueuedCheckin[] {
  const list = readQueue()
  return taskId == null ? list : list.filter((item) => item.taskId === taskId)
}

/**
 * 试着把队列里的打卡补传上去。
 *
 * <p>逐条顺序发，**遇到网络错误就停**：一条发不出去说明现在还是没网，
 * 继续试剩下的只是白耗电和流量；等下一次进页面（或下拉刷新）再试。
 * 服务端说「重复」（{@code duplicated}）也算成功 —— 那条早就在库了。
 *
 * @returns 传成功了几条、队列里还剩几条
 */
export async function flushCheckins(): Promise<{ sent: number; left: number }> {
  const list = readQueue()
  if (list.length === 0) return { sent: 0, left: 0 }

  const left: QueuedCheckin[] = []
  let sent = 0
  let stopped = false

  for (const item of list) {
    if (stopped) {
      left.push(item)
      continue
    }
    try {
      const { queuedAt, ...param } = item
      void queuedAt
      await submitCheckin(param as TaskCheckinParam)
      sent++
    } catch {
      // 失败就停在这儿，并且**把它留在队列里**（不能丢，那是当时的记录）
      stopped = true
      left.push(item)
    }
  }

  writeQueue(left)
  return { sent, left: left.length }
}

/** 清空队列（只在明确知道这些打卡永远不会再有意义时用，比如换账号登录） */
export function clearCheckinQueue(): void {
  uni.removeStorageSync(STORAGE_KEY)
}

/** 判断一个错误是不是「发了但没到」（网络类），而不是「服务端明确拒绝」 */
export function isNetworkFailure(error: unknown): boolean {
  const message = error instanceof Error ? error.message : String(error)
  return /fail|timeout|network|abort/i.test(message)
}

export type { TaskCheckinVO }
