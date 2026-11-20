<script setup lang="ts">
/**
 * 业务分析（见 `docs/业务分析方案.md`）。
 *
 * <h2>两屏，各答一个问题</h2>
 * 上面那屏答「这段时间整体怎么样」（任务量、完成率、按时率、告警与处置速度），
 * 下面那屏答「活攒着为什么没派出去」。
 * 它们**共用同一个时间区间** —— 区间只有一处选择，两块不会各看各的。
 *
 * <h2>⚠️ 三块数据的口径不同，页面上都标着</h2>
 * 完成率那一组按**结束时间**落进区间（只看已经有结论的活）；
 * 积压时长是**此刻的快照**；趋势按**天**（缺口补 0）。
 * 混着读会得出「上半屏在涨、下半屏在跌」这种前后不搭的结论。
 *
 * <h2>为什么不做自动刷新</h2>
 * 这是按天/周看的东西，没人会盯着它看数字跳 —— 那是看板和大屏的活。
 * 进来算一次、换区间时再算一次即可。
 */
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  downloadCoverage,
  fetchCoverage,
  fetchDispatchBlockStats,
  fetchEfficiency,
  fetchEvidenceTrend,
  fetchOverview,
  type AnalyticsOverview,
  type Coverage,
  type DispatchBlockStats,
  type Efficiency,
  type EvidenceTrend,
} from '@/api/analytics'
import ChartBox from '@/components/analytics/ChartBox.vue'
import { chartTokens } from '@/utils/chart'
import { saveBlob } from '@/utils/download'

/** 区间快捷项。默认「本月」—— 最常被问的那句就是「这个月怎么样」 */
const RANGES = [
  { key: 'month', label: '本月' },
  { key: '7', label: '近 7 天' },
  { key: '30', label: '近 30 天' },
  { key: '90', label: '近 90 天' },
] as const
type RangeKey = (typeof RANGES)[number]['key']

const range = ref<RangeKey>('month')
const loading = ref(false)
const error = ref('')
const overview = ref<AnalyticsOverview | null>(null)
const stats = ref<DispatchBlockStats | null>(null)
const efficiency = ref<Efficiency | null>(null)
const evidence = ref<EvidenceTrend | null>(null)
const coverage = ref<Coverage | null>(null)
const coverageFilter = ref<'all' | 'missed' | 'covered'>('all')
const exporting = ref(false)

const router = useRouter()

// ---------- 下钻（见 docs/业务分析方案.md 三点五） ----------
//
// 规则只有一条：**把当前区间一起带过去**。列表页按同一个窗口筛，条数才对得上 ——
// 对不上就说明口径没写清，而不是「列表页不够好看」。

/** 下钻到任务列表。withWindow=false 用于「此刻的快照」类数字（待派发）：它们跟区间无关 */
function drillToTask(extra: Record<string, string> = {}, withWindow = true) {
  const params = rangeParams(range.value)
  router.push({
    path: '/task',
    query: withWindow ? { ...extra, endFrom: params.from, endTo: params.to } : extra,
  })
}

/** 下钻到告警中心（MTTR/未处置/告警趋势都按 observed_at 落窗） */
function drillToAlert(extra: Record<string, string> = {}) {
  const params = rangeParams(range.value)
  router.push({
    path: '/alert',
    query: { ...extra, observedFrom: params.from, observedTo: params.to },
  })
}

/** 下钻到设备管理（按编号精确查） */
function drillToDevice(code: string) {
  router.push({ path: '/device', query: { keyword: code } })
}

/** 导出的行：默认全列，也可以只看没到过的（客户最常问的就是这个） */
const coverageRows = computed(() => {
  const rows = coverage.value?.points ?? []
  if (coverageFilter.value === 'missed') return rows.filter((row) => !row.covered)
  if (coverageFilter.value === 'covered') return rows.filter((row) => row.covered)
  return rows
})

async function exportCoverage() {
  exporting.value = true
  try {
    const params = rangeParams(range.value)
    const blob = await downloadCoverage(params)
    // 文件名带上区间，免得一个月导几份之后分不清哪份是哪份
    await saveBlob(blob, `点位覆盖率_${params.from.slice(0, 10)}_${params.to.slice(0, 10)}.xlsx`)
  } finally {
    exporting.value = false
  }
}

// ---------- 榜单的排序 ----------

/**
 * 榜单默认按「干得多的在前」，但真正要看的往往是另一列：
 * 机务要按**百小时故障**找该大修的那台，班组要按**无旁证占比**找「总在只声明」的人。
 * 只有 4 个飞手 6 台设备，前端排序就够，不必再麻烦后端。
 */
const pilotSortOptions = [
  { key: 'taskCount', label: '结束的活' },
  { key: 'onTimeRate', label: '按时率' },
  { key: 'noProofRate', label: '无旁证占比' },
  { key: 'offers', label: '派给他的次数' },
]
const deviceSortOptions = [
  { key: 'hours', label: '飞行时长' },
  { key: 'utilization', label: '利用率' },
  { key: 'faultRatePer100h', label: '百小时故障' },
  { key: 'alerts', label: '告警条数' },
]
const pilotSortKey = ref('taskCount')
const deviceSortKey = ref('hours')

/** 按某一列倒序；**没样本的（null）永远沉底** —— 它不是「最小」，是「无从谈起」 */
function sortedBy<T extends object>(rows: T[], key: string): T[] {
  return [...rows].sort((a, b) => {
    const left = (a as Record<string, unknown>)[key]
    const right = (b as Record<string, unknown>)[key]
    if (left === null || left === undefined) return 1
    if (right === null || right === undefined) return -1
    return Number(right) - Number(left)
  })
}

const pilotRows = computed(() => sortedBy(efficiency.value?.pilots ?? [], pilotSortKey.value))
const deviceRows = computed(() => sortedBy(efficiency.value?.devices ?? [], deviceSortKey.value))

/**
 * 归因配色：按**取值**固定，不按数组下标。
 *
 * <p>接口返回的顺序是「条数从多到少」，如果按下标配色，同一个归因会随着数据变化换颜色 ——
 * 看图的人会以为「缺设备」变成了别的意思。所以颜色只跟 reason 走，与排序无关。
 */
const REASON_TONE: Record<string, 'red' | 'blue' | 'violet' | 'amber'> = {
  blocked_both: 'red',
  blocked_drone: 'blue',
  blocked_pilot: 'violet',
  idle: 'amber',
}

/** 积压时长三桶的配色：同样按 key 固定，不按下标 */
const AGE_TONE: Record<string, 'cyan' | 'amber' | 'red'> = {
  lt1h: 'cyan',
  lt1d: 'amber',
  ge1d: 'red',
}

/** 告警趋势三条线的配色：与告警中心的标签色一致（红 / 琥珀 / 灰） */
const SEVERITY_TONE: Record<string, 'red' | 'amber' | 'muted'> = {
  critical: 'red',
  warning: 'amber',
  info: 'muted',
}

const overdueCount = computed(
  () => stats.value?.backlogAge.find((item) => item.key === 'ge1d')?.count ?? 0,
)

/** 采样覆盖：归因点是定时任务攒的，停过机的那几个小时没有数据 —— 不写出来，占比会看着太自信 */
/** 归因点的采样覆盖（百分比）。注意别和下面的「点位覆盖率」混了：两者是两件事 */
const sampleCoverage = computed(() => {
  const value = stats.value
  if (!value || !value.expectedHours) {
    return 0
  }
  return Math.round((value.sampledHours / value.expectedHours) * 100)
})

const coverageText = computed(() => {
  const value = stats.value
  if (!value) {
    return '—'
  }
  return `${sampleCoverage.value}% · ${value.sampledHours}/${value.expectedHours} 小时`
})

/** 比率：后端给一位小数；**没样本时是 null，显示「—」而不是「0%」** */
function pct(value: number | null | undefined) {
  return value === null || value === undefined ? '—' : `${value}%`
}

/** 分钟 → 人看得懂的写法 */
function fmtMinutes(value: number | null | undefined) {
  if (value === null || value === undefined) {
    return '—'
  }
  if (value < 60) {
    return `${value} 分钟`
  }
  const hours = Math.floor(value / 60)
  const rest = value % 60
  return rest ? `${hours} 小时 ${rest} 分` : `${hours} 小时`
}

/** 小时数（报告里填的飞行时长）：没有报告时是 null，显示「—」而不是 0 */
function fmtHours(value: number | null | undefined) {
  return value === null || value === undefined ? '—' : `${value} 小时`
}

const STATUS_LABEL: Record<string, string> = {
  idle: '待命',
  assigned: '已派出',
  flying: '飞行中',
  charging: '充电中',
  offline: '离线',
  maintenance: '维护中',
}

/** 主要矛盾的一句话结论：只有真的采到了点才说，没有数据时不硬编一句话 */
const conclusion = computed(() => {
  const value = stats.value
  if (!value || !value.blockPoints) {
    return ''
  }
  const top = value.reasonShare[0]
  if (!top || !top.count) {
    return ''
  }
  const idle = value.reasonShare.find((item) => item.reason === 'idle')
  const head = `区间内 ${top.percent}% 的积压是「${top.label}」`
  if (!idle || !idle.count) {
    return `${head} —— 都是资源问题，短期只能靠加人/加设备或调整时间窗。`
  }
  return `${head}；其中 ${idle.percent}% 属于「资源够、但没人点派发」—— 这是唯一一条不花钱就能改善的。`
})

const emptyReasonText = computed(() =>
  stats.value
    ? `这个区间里没有任何归因点。归因点由定时任务每 10 分钟攒一次，服务刚起或停过机时就是空的（区间内采样覆盖：${coverageText.value}）。`
    : '暂无数据',
)

/** 横轴的短标签：'2026-09-30' → '09-30'（90 天时全长标签会挤成一团） */
const axisLabels = computed(() => (overview.value?.days ?? []).map((day) => day.slice(5)))

const createdOption = computed(() => {
  const tokens = chartTokens()
  return {
    grid: { left: 8, right: 16, top: 26, bottom: 8, containLabel: true },
    tooltip: { trigger: 'axis' },
    xAxis: {
      type: 'category',
      data: axisLabels.value,
      boundaryGap: false,
      axisTick: { show: false },
      axisLine: { lineStyle: { color: tokens.line } },
      axisLabel: { color: tokens.muted },
    },
    yAxis: {
      type: 'value',
      minInterval: 1,
      axisLabel: { color: tokens.muted },
      splitLine: { lineStyle: { color: tokens.line } },
    },
    series: [
      {
        name: '新建任务',
        type: 'line',
        smooth: true,
        symbolSize: 6,
        data: overview.value?.tasks.created ?? [],
        itemStyle: { color: tokens.cyan },
        lineStyle: { color: tokens.cyan, width: 2 },
        areaStyle: { color: 'rgba(34, 211, 238, 0.14)' },
      },
    ],
  }
})

const alertOption = computed(() => {
  const tokens = chartTokens()
  const series = overview.value?.alerts.trend ?? []
  return {
    grid: { left: 8, right: 16, top: 34, bottom: 8, containLabel: true },
    tooltip: { trigger: 'axis' },
    legend: {
      top: 0,
      itemWidth: 12,
      itemHeight: 12,
      textStyle: { color: tokens.text, fontSize: 12 },
    },
    xAxis: {
      type: 'category',
      data: axisLabels.value,
      boundaryGap: false,
      axisTick: { show: false },
      axisLine: { lineStyle: { color: tokens.line } },
      axisLabel: { color: tokens.muted },
    },
    yAxis: {
      type: 'value',
      minInterval: 1,
      axisLabel: { color: tokens.muted },
      splitLine: { lineStyle: { color: tokens.line } },
    },
    series: series.map((line) => {
      const tone = SEVERITY_TONE[line.severity] ?? 'muted'
      return {
        name: line.label,
        type: 'line',
        smooth: true,
        symbolSize: 6,
        data: line.values,
        itemStyle: { color: tokens[tone] },
        lineStyle: { color: tokens[tone], width: 2 },
      }
    }),
  }
})

const reasonOption = computed(() => {
  const tokens = chartTokens()
  const rows = stats.value?.reasonShare ?? []
  return {
    tooltip: { trigger: 'item', formatter: '{b}：{c} 个点（{d}%）' },
    legend: {
      bottom: 0,
      itemWidth: 12,
      itemHeight: 12,
      textStyle: { color: tokens.text, fontSize: 12 },
    },
    series: [
      {
        type: 'pie',
        radius: ['46%', '68%'],
        center: ['50%', '44%'],
        avoidLabelOverlap: true,
        label: { show: false },
        itemStyle: { borderColor: tokens.panel, borderWidth: 2 },
        data: rows.map((row) => ({
          name: row.label,
          value: row.count,
          itemStyle: { color: tokens[REASON_TONE[row.reason] ?? 'cyan'] },
        })),
      },
    ],
  }
})

const ageOption = computed(() => {
  const tokens = chartTokens()
  const rows = stats.value?.backlogAge ?? []
  return {
    grid: { left: 8, right: 12, top: 26, bottom: 8, containLabel: true },
    tooltip: { trigger: 'axis', formatter: '{b}：{c} 条' },
    xAxis: {
      type: 'category',
      data: rows.map((row) => row.label),
      axisTick: { show: false },
      axisLine: { lineStyle: { color: tokens.line } },
      axisLabel: { color: tokens.text },
    },
    yAxis: {
      type: 'value',
      minInterval: 1,
      axisLabel: { color: tokens.muted },
      splitLine: { lineStyle: { color: tokens.line } },
    },
    series: [
      {
        type: 'bar',
        barWidth: 44,
        data: rows.map((row) => ({
          value: row.count,
          // 等得越久越刺眼：一小时内是常态（青），一天内该有人管了（琥珀），超过一天是红的
          itemStyle: { color: tokens[AGE_TONE[row.key] ?? 'cyan'], borderRadius: [4, 4, 0, 0] },
        })),
      },
    ],
  }
})

/**
 * 证据量与活跃设备：柱（左轴：条数）+ 线（右轴：台数）。
 *
 * <p>两个量级差得远（一天几千条 vs 几台设备），共用一根轴的话那条线会贴在海底 ——
 * 所以用双轴。这也是「同一张图看两件事」唯一站得住的做法。
 */
const evidenceOption = computed(() => {
  const tokens = chartTokens()
  const data = evidence.value
  return {
    grid: { left: 8, right: 16, top: 34, bottom: 8, containLabel: true },
    tooltip: { trigger: 'axis' },
    legend: {
      top: 0,
      itemWidth: 12,
      itemHeight: 12,
      textStyle: { color: tokens.text, fontSize: 12 },
    },
    xAxis: {
      type: 'category',
      data: (data?.days ?? []).map((day) => day.slice(5)),
      axisTick: { show: false },
      axisLine: { lineStyle: { color: tokens.line } },
      axisLabel: { color: tokens.muted },
    },
    yAxis: [
      {
        type: 'value',
        name: '条',
        axisLabel: { color: tokens.muted },
        splitLine: { lineStyle: { color: tokens.line } },
      },
      {
        type: 'value',
        name: '台',
        minInterval: 1,
        axisLabel: { color: tokens.muted },
        splitLine: { show: false },
      },
    ],
    series: [
      {
        name: '证据条数',
        type: 'bar',
        data: data?.evidenceCounts ?? [],
        itemStyle: { color: tokens.cyan, borderRadius: [3, 3, 0, 0] },
      },
      {
        name: '活跃设备',
        type: 'line',
        yAxisIndex: 1,
        smooth: true,
        symbolSize: 6,
        data: data?.activeDevices ?? [],
        itemStyle: { color: tokens.amber },
        lineStyle: { color: tokens.amber, width: 2 },
      },
    ],
  }
})

/** 「这几天里有多少来自预聚合、多少是实时补的」——界面上要说清，否则看的人以为今天那个点已经定了 */
const evidenceSourceText = computed(() => {
  const data = evidence.value
  if (!data) {
    return ''
  }
  return `其中 ${data.precomputedDays} 天来自预聚合日表，${data.liveDays} 天是实时补算的（今天的数据还在涨）`
})

/**
 * 区间端点：**本地日历**上的整天（今天 0 点 → 明天 0 点）。
 *
 * <p>用 `new Date(年, 月, 日)` 而不是对时间戳加减：「本月」如果从「明天」往回退到 1 号，
 * 在月末那天会算成起点 = 终点（31 号的「明天」已经是下月 1 号）。
 *
 * <p>终点取「明天 0 点」而不是「此刻」：否则今天这一整天的数据有一半落在区间外，
 * 页面上看起来就像「今天什么都没发生」。
 */
function rangeParams(key: RangeKey) {
  const now = new Date()
  const end = new Date(now.getFullYear(), now.getMonth(), now.getDate() + 1)
  const start =
    key === 'month'
      ? new Date(now.getFullYear(), now.getMonth(), 1)
      : new Date(end.getTime() - Number(key) * 24 * 3600 * 1000)
  return { from: formatTime(start), to: formatTime(end) }
}

function formatTime(date: Date) {
  const pad = (value: number) => String(value).padStart(2, '0')
  return (
    `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ` +
    `${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
  )
}

async function load() {
  loading.value = true
  error.value = ''
  const params = rangeParams(range.value)
  try {
    // 四块一起取：它们共用区间，分开取会出现「上半屏是刚才、下半屏是现在」
    const [first, second, third, fourth, fifth] = await Promise.all([
      fetchOverview(params),
      fetchDispatchBlockStats(params),
      fetchEfficiency(params),
      fetchEvidenceTrend(params),
      fetchCoverage(params),
    ])
    overview.value = first
    stats.value = second
    efficiency.value = third
    evidence.value = fourth
    coverage.value = fifth
  } catch (e) {
    // 接口的失败提示已经由 axios 拦截器统一弹出，这里只留一份就地提示，
    // 免得页面上一片空白、看不出是「没数据」还是「没请求成功」
    error.value = e instanceof Error ? e.message : '读取失败'
  } finally {
    loading.value = false
  }
}

function setRange(key: RangeKey) {
  if (key === range.value) {
    return
  }
  range.value = key
  load()
}

onMounted(load)
</script>

<template>
  <div>
    <div class="toolbar">
      <div class="toolbar__ranges">
        <button
          v-for="item in RANGES"
          :key="item.key"
          class="btn btn--ghost"
          :class="{ 'is-on': item.key === range }"
          type="button"
          @click="setRange(item.key)"
        >
          {{ item.label }}
        </button>
      </div>
      <span class="toolbar__meta">
        <template v-if="loading">刷新中…</template>
        <template v-else-if="overview">{{ overview.from }} ~ {{ overview.to }}</template>
      </span>
    </div>

    <p v-if="error" class="error">{{ error }}（下面的数字可能不是最新的）</p>

    <!-- ============ 一、任务与告警概览 ============ -->
    <h2 class="section__title">
      任务与告警概览
      <small>这段时间整体怎么样；完成率只算已经有结论的活（按实际结束时间落进区间）</small>
    </h2>

    <div class="stats">
      <div class="stat stat--link" title="点开看这段时间有结论的活" @click="drillToTask()">
        <span class="stat__label">完成率</span>
        <span class="stat__value">{{ pct(overview?.tasks.finishRate) }}</span>
        <span class="stat__sub">
          完成 {{ overview?.tasks.finished ?? 0 }} · 失败 {{ overview?.tasks.failed ?? 0 }}（点开看这几条）
        </span>
      </div>
      <div class="stat stat--link" title="点开到任务列表按同一个区间复算" @click="drillToTask()">
        <span class="stat__label">按时完成率</span>
        <span class="stat__value">{{ pct(overview?.tasks.onTimeRate) }}</span>
        <span class="stat__sub">样本 {{ overview?.tasks.onTimeBase ?? 0 }} 条（有计划结束时间）</span>
      </div>
      <div class="stat stat--link" title="点开到任务列表按同一个区间复算" @click="drillToTask()">
        <span class="stat__label">平均执行时长</span>
        <span class="stat__value stat__value--mid">
          {{ fmtMinutes(overview?.tasks.avgMinutes) }}
        </span>
        <span class="stat__sub">样本 {{ overview?.tasks.durationBase ?? 0 }} 条（有实际起止）</span>
      </div>
      <div class="stat stat--link" title="点开看这段时间里已处置的告警" @click="drillToAlert({ status: 'resolved' })">
        <span class="stat__label">告警平均处置时长</span>
        <span class="stat__value stat__value--mid">
          {{ fmtMinutes(overview?.alerts.mttrMinutes) }}
        </span>
        <span class="stat__sub">
          已处置 {{ overview?.alerts.handled ?? 0 }} / 共 {{ overview?.alerts.total ?? 0 }} 条
        </span>
      </div>
      <div class="stat stat--link" title="点开看还没处置的告警（此刻的快照，不带时间区间）"
           @click="router.push({ path: '/alert', query: { status: 'pending' } })">
        <span class="stat__label">此刻未处置告警</span>
        <span class="stat__value" :class="{ 'is-violet': (overview?.alerts.openNow ?? 0) > 0 }">
          {{ overview?.alerts.openNow ?? '—' }}
        </span>
        <span class="stat__sub">快照，不看区间（点开看这几条）</span>
      </div>
    </div>

    <div class="board">
      <div class="card">
        <div class="card__head">
          <div class="card__title">
            任务量趋势
            <small>每天新建了多少任务；没有任务的那天是 0，不是断线</small>
          </div>
        </div>
        <ChartBox
          :option="createdOption"
          :empty="!overview?.tasks.createdTotal"
          empty-text="这段区间里没有新建任务"
        />
      </div>

      <div class="card">
        <div class="card__head">
          <div class="card__title">
            告警趋势
            <small>按级别分线；「未处置」是另一个数（此刻的积压）</small>
          </div>
        </div>
        <ChartBox
          :option="alertOption"
          :empty="!overview?.alerts.total"
          empty-text="这段区间里没有告警 —— 这是最好的状态"
        />
      </div>
    </div>

    <!-- ============ 二、待派发积压归因 ============ -->
    <h2 class="section__title">
      待派发积压归因
      <small>活攒着为什么没派出去；归因占比按区间，积压时长是此刻的快照</small>
    </h2>

    <div class="stats">
      <div class="stat stat--link" title="点开看待派发的任务（此刻的快照，不带时间区间）"
           @click="drillToTask({ status: 'pending' }, false)">
        <span class="stat__label">待派发（此刻）</span>
        <span class="stat__value">{{ stats?.pendingTotal ?? '—' }}</span>
      </div>
      <div class="stat">
        <span class="stat__label">其中等了 1 天以上</span>
        <span class="stat__value" :class="{ 'is-violet': overdueCount > 0 }">
          {{ overdueCount }}
        </span>
      </div>
      <div class="stat">
        <span class="stat__label">创建 → 派发（中位）</span>
        <span class="stat__value stat__value--mid">{{ fmtMinutes(stats?.medianMinutes) }}</span>
        <span class="stat__sub">样本 {{ stats?.dispatchedSamples ?? 0 }} 条（区间内被派发的）</span>
      </div>
      <div class="stat">
        <span class="stat__label">归因采样覆盖</span>
        <span class="stat__value stat__value--small" :class="{ 'is-warn': sampleCoverage < 90 }">
          {{ coverageText }}
        </span>
        <span class="stat__sub">覆盖不足时，占比要打折看</span>
      </div>
    </div>

    <div class="board">
      <div class="card">
        <div class="card__head">
          <div class="card__title">
            积压归因占比
            <small>区间内的历史：这些活为什么没派出去</small>
          </div>
        </div>
        <ChartBox
          :option="reasonOption"
          :empty="!stats?.blockPoints"
          :empty-text="emptyReasonText"
        />
        <p v-if="conclusion" class="conclusion">{{ conclusion }}</p>
      </div>

      <div class="card">
        <div class="card__head">
          <div class="card__title">
            待派发积压时长
            <small>此刻的快照：还攒着多少、各自等了多久</small>
          </div>
        </div>
        <ChartBox
          :option="ageOption"
          :empty="!stats?.pendingTotal"
          empty-text="此刻没有待派发的任务 —— 这是最好的状态"
        />
      </div>
    </div>

    <!-- ============ 五、点位覆盖 ============ -->
    <h2 class="section__title">
      点位覆盖
      <small>该去的点去了没有（「到过」按打卡算，偏差 ≤
        {{ coverage?.radiusMeters ?? 100 }} 米）；这一屏就是能直接给客户看的那份</small>
    </h2>

    <div class="stats">
      <div class="stat">
        <span class="stat__label">覆盖率</span>
        <span class="stat__value">{{ pct(coverage?.coverageRate) }}</span>
        <span class="stat__sub">
          到过 {{ coverage?.visitedPoints ?? 0 }} / 计划 {{ coverage?.plannedPoints ?? 0 }} 个航点
        </span>
      </div>
      <div class="stat">
        <span class="stat__label">已结束的任务</span>
        <span class="stat__value">{{ coverage?.taskCount ?? '—' }}</span>
        <span class="stat__sub">其中没配航线 {{ coverage?.tasksWithoutRoute ?? 0 }} 条（没有计划点）</span>
      </div>
      <div class="stat">
        <span class="stat__label">涉及航线</span>
        <span class="stat__value">{{ coverage?.routes?.length ?? 0 }}</span>
        <span class="stat__sub">按覆盖率从低到高排</span>
      </div>
      <div class="stat">
        <span class="stat__label">导出给客户</span>
        <button class="btn btn--primary" type="button" :disabled="exporting" @click="exportCoverage">
          {{ exporting ? '导出中…' : '下载 Excel' }}
        </button>
        <span class="stat__sub">汇总 + 点位明细两张表，与这一屏同一份数据</span>
      </div>
    </div>

    <div class="board board--stack">
      <div class="card">
        <div class="card__head">
          <div class="card__title">
            点位明细
            <small>没到过的排在最前面 —— 这一屏就是给「这个月还差哪些点」用的</small>
          </div>
          <div class="card__tools">
            <select v-model="coverageFilter" class="select">
              <option value="all">全部点位</option>
              <option value="missed">只看没到过的</option>
              <option value="covered">只看到过的</option>
            </select>
          </div>
        </div>

        <div class="table-wrap">
          <table>
            <thead>
              <tr>
                <th>点位</th>
                <th>编号</th>
                <th>本月是否覆盖</th>
                <th>被计划</th>
                <th>到过</th>
                <th>经度</th>
                <th>纬度</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="row in coverageRows" :key="row.pointId ?? `${row.lng},${row.lat}`">
                <td class="strong">{{ row.name }}</td>
                <td class="code">{{ row.code || '—' }}</td>
                <td>
                  <span class="tag" :class="row.covered ? 'tag--green' : 'tag--red'">
                    {{ row.covered ? '已覆盖' : '未覆盖' }}
                  </span>
                </td>
                <td>{{ row.plannedTimes }}</td>
                <td>{{ row.visitedTimes }}</td>
                <td>{{ row.lng?.toFixed(5) }}</td>
                <td>{{ row.lat?.toFixed(5) }}</td>
              </tr>
              <tr v-if="!coverageRows.length">
                <td colspan="7" class="empty">这段时间没有可统计的航点</td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
    </div>

    <p class="note">
      「创建 → 派发」用的是<b>中位数</b>而不是平均数：一条被忘了一周的任务能把平均数拉到毫无意义，
      而「被忘了」恰恰是这个指标要暴露的事。它只统计区间内<b>被派发过</b>的任务；
      归因占比由定时任务攒的归因点算出，采样没覆盖到的时段不计入分母。
      完成率的分母是<b>已经有结论的活</b>（完成 + 失败）—— 还在飞的和被撤回的都不计入，
      它们不是「没完成」，而是「还没有 / 不需要结论」。
      证据量那一屏的历史来自<b>预聚合日表</b>（由补算任务按天算好），
      今天那一段是当场从明细算的 —— 也就是说：图上<b>最后一个点还会涨</b>。
    </p>
    <!-- ============ 三、证据量与在线设备 ============ -->
    <h2 class="section__title">
      证据量与在线设备
      <small>历史读预聚合日表、今天实时补；明细表上这条查询要 0.58 秒、走预聚合 0.04 秒</small>
    </h2>

    <div class="board board--stack">
      <div class="card">
        <div class="card__head">
          <div class="card__title">
            每天多少条证据 · 多少台设备活跃
            <small>{{ evidenceSourceText }}</small>
          </div>
        </div>
        <ChartBox
          :option="evidenceOption"
          :empty="!evidence?.days?.length"
          empty-text="这段区间还没有证据数据"
        />
      </div>
    </div>

    <!-- ============ 四、飞手与设备效率 ============ -->
    <h2 class="section__title">
      飞手与设备效率
      <small>谁忙谁闲、哪台该大修；两个榜都列全部在册飞手与设备（0 值行就是「闲」）</small>
    </h2>

    <div class="board board--stack">
      <div class="card">
        <div class="card__head">
          <div class="card__title">
            飞手榜
            <small>「无旁证」= 他在飞、但没有任何设备数据佐证（设计里最独特的一个指标）</small>
          </div>
          <div class="card__tools">
            <select v-model="pilotSortKey" class="select">
              <option v-for="opt in pilotSortOptions" :key="opt.key" :value="opt.key">
                按{{ opt.label }}排
              </option>
            </select>
          </div>
        </div>

        <div class="table-wrap">
          <table>
            <thead>
              <tr>
                <th>姓名</th>
                <th>派给他</th>
                <th>拒绝</th>
                <th>拒绝率</th>
                <th>结束的活</th>
                <th>完成</th>
                <th>按时率</th>
                <th>开过的活</th>
                <th>无旁证</th>
                <th>无旁证占比</th>
                <th>飞行时长</th>
                <th>架次</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="row in pilotRows" :key="row.pilotId">
                <td class="strong">
                  <span class="drill" title="点开看他这段时间的活" @click="drillToTask({ pilotId: String(row.pilotId) })">
                    {{ row.name }}
                  </span>
                </td>
                <td>{{ row.offers }}</td>
                <td>{{ row.refused }}</td>
                <td>{{ pct(row.refuseRate) }}</td>
                <td>{{ row.taskCount }}</td>
                <td>{{ row.finished }}</td>
                <td>{{ pct(row.onTimeRate) }}</td>
                <td>{{ row.started }}</td>
                <td :class="{ 'warn-cell': row.noProof > 0 }">{{ row.noProof }}</td>
                <td>{{ pct(row.noProofRate) }}</td>
                <td>{{ fmtHours(row.hours) }}</td>
                <td>{{ row.sorties }}</td>
              </tr>
              <tr v-if="!pilotRows.length">
                <td colspan="12" class="empty">没有在册飞手</td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>

      <div class="card">
        <div class="card__head">
          <div class="card__title">
            设备榜
            <small>「可飞时长」就是利用率的分母，列出来方便核对它是怎么算的</small>
          </div>
          <div class="card__tools">
            <select v-model="deviceSortKey" class="select">
              <option v-for="opt in deviceSortOptions" :key="opt.key" :value="opt.key">
                按{{ opt.label }}排
              </option>
            </select>
          </div>
        </div>

        <div class="table-wrap">
          <table>
            <thead>
              <tr>
                <th>编号</th>
                <th>名称</th>
                <th>状态</th>
                <th>在役天数</th>
                <th>可飞时长</th>
                <th>飞行时长</th>
                <th>架次</th>
                <th>利用率</th>
                <th>告警</th>
                <th>其中严重</th>
                <th>百小时故障</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="row in deviceRows" :key="row.deviceId">
                <td class="code">
                  <span class="drill" title="点开到设备管理看这台" @click="drillToDevice(row.code)">
                    {{ row.code }}
                  </span>
                </td>
                <td class="strong">{{ row.name }}</td>
                <td>
                  <span class="tag" :class="{ 'tag--amber': row.status === 'maintenance' }">
                    {{ STATUS_LABEL[row.status] || row.status }}
                  </span>
                </td>
                <td>{{ row.inServiceDays }}</td>
                <td>{{ fmtHours(row.flyableHours) }}</td>
                <td>{{ fmtHours(row.hours) }}</td>
                <td>{{ row.sorties }}</td>
                <td>{{ pct(row.utilization) }}</td>
                <td :class="{ 'warn-cell': row.alerts > 0 }">{{ row.alerts }}</td>
                <td :class="{ 'warn-cell': row.criticalAlerts > 0 }">{{ row.criticalAlerts }}</td>
                <td>{{ pct(row.faultRatePer100h) }}</td>
              </tr>
              <tr v-if="!deviceRows.length">
                <td colspan="11" class="empty">没有设备</td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
    </div>

    <p class="note">
      效率榜这一屏里，<b>三组数的时间基准不一样</b>，别拿两列相互除：工作量/按时率按「任务的结束时间」、
      无旁证占比按「任务的开始时间」（含还在飞的 —— 「他说在飞却没证据」正是要在飞行中暴露）、
      派发与拒绝按「流水时间」。利用率 = 飞行时长 ÷ <b>可飞时长</b>，而可飞时长 = 在役天数 ×
      每天 {{ efficiency?.flyableHoursPerDay ?? 8 }} 小时（配置项）——
      它把维护/充电的日子也算进了分母，所以这个数<b>偏低、偏保守</b>；
      要做准就得有设备状态变更历史，而那件事已经拍板不做。没有样本的比率显示「—」而不是 0%。
    </p>
  </div>
</template>

<style scoped>
.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 14px;
}

.toolbar__ranges {
  display: flex;
  gap: 8px;
}

.toolbar__meta {
  font-size: 12px;
  color: var(--muted);
}

/* 区间按钮是一组单选：选中的那个要看得出来 */
.btn.is-on {
  color: var(--cyan);
  border-color: var(--accent-hover);
  background: var(--tag-cyan-bg);
}

.error {
  margin: 0 0 14px;
  padding: 10px 14px;
  font-size: 13px;
  color: var(--tag-amber-fg);
  background: var(--tag-amber-bg);
  border: 1px solid var(--tag-amber-bd);
  border-radius: 8px;
}

/* 分区标题：这一页有两屏，得让人一眼看出「下面那些数是另一件事」 */
.section__title {
  display: flex;
  align-items: baseline;
  gap: 10px;
  margin: 22px 0 12px;
  font-size: 15px;
  font-weight: 600;
  color: var(--text);
}

.section__title small {
  font-size: 12px;
  font-weight: 400;
  color: var(--muted);
}

/* 卡片里竖着排三行：标签 / 数值 / 脚注（全局那套是行内排，这一页要堆起来） */
.stat__label,
.stat__value,
.stat__sub {
  display: block;
}

.stat__sub {
  margin-top: 6px;
  font-size: 12px;
  color: var(--muted);
}

/* 时长这类「不是纯数字」的值，字号收一档，免得比标签还抢眼 */
.stat__value--mid {
  font-size: 20px;
}

.stat__value--small {
  font-size: 16px;
  line-height: 1.5;
}

/* ⚠️ 只改 color 是没用的：.stat__value 用的是渐变文字（background-clip: text），
   警告色得通过 background 覆盖，否则改了色号也看不出来 */
.stat__value.is-warn {
  background: linear-gradient(100deg, var(--tag-amber-fg), var(--amber));
  -webkit-background-clip: text;
  background-clip: text;
}

/* 两块图并排；窄屏时落成上下两块 */
.board {
  display: grid;
  grid-template-columns: 1.1fr 1fr;
  gap: 14px;
}

/* 榜单要的是宽度（十几列），所以强制单列铺满 */
.board--stack {
  grid-template-columns: 1fr;
}

@media (max-width: 1100px) {
  .board {
    grid-template-columns: 1fr;
  }
}

/* 需要一眼看见的「不太好」的数：无旁证、告警 */
.warn-cell {
  color: var(--tag-amber-fg);
  font-weight: 600;
}

/* 可点下钻的卡片与单元格：给一个能看出来的手感（下钻规则见脚本里的注释） */
.stat--link {
  cursor: pointer;
}

.drill {
  cursor: pointer;
  border-bottom: 1px dashed var(--line-strong);
}

.drill:hover {
  color: var(--cyan);
}

.conclusion {
  margin: 0;
  padding: 0 18px 16px;
  font-size: 13px;
  line-height: 1.8;
  color: var(--text-2);
}

.note {
  margin: 14px 0 0;
  font-size: 12px;
  line-height: 1.9;
  color: var(--muted);
}
</style>
