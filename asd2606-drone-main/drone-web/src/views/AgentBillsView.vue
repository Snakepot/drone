<script setup lang="ts">
/**
 * 助手账单（REQ-AGENT-0025）。
 *
 * <p>回答三个问题：花了多少（KPI）、花在哪（分类 / 工具）、什么时候花的（按天）。
 * 数据是**元数据**：只有 token、工具名与时间，没有对话正文。
 *
 * <p>权限：路由用 `system:rbac:list` 拦（目前只有管理员有），后端 `scope=all` 还会再判一次
 * admin 角色 —— 前端隐藏入口不是安全边界。
 */
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { fetchBillSummary, fetchBills, type BillRecord, type BillSummary } from '@/api/bill'
import ChartBox from '@/components/analytics/ChartBox.vue'
import { chartTokens } from '@/utils/chart'

const tokens = chartTokens()

const RANGES = [
  { key: 'today', label: '今天', days: 1 },
  { key: 'week', label: '近 7 天', days: 7 },
  { key: 'month', label: '近 30 天', days: 30 },
]

const KIND_LABELS: Record<string, string> = {
  SYSTEM: '系统提示词',
  TOOL_DEFINITIONS: '工具定义',
  SESSION: '当前会话',
  LONG_TERM: '长期记忆',
  TOOL_CALL: '工具调用',
  TOOL_RESULT: '工具结果',
  COMPACTION: '历史摘要',
  RESUME: '续跑上下文',
}

const rangeKey = ref('week')
const loading = ref(false)
const summary = ref<BillSummary | null>(null)
const bills = ref<BillRecord[]>([])

function ymd(date: Date): string {
  const pad = (value: number) => String(value).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`
}

function queryRange() {
  const preset = RANGES.find((item) => item.key === rangeKey.value) ?? {
    key: 'week',
    label: '近 7 天',
    days: 7,
  }
  const to = new Date()
  const from = new Date()
  from.setDate(to.getDate() - preset.days + 1)
  return { from: ymd(from), to: ymd(to) }
}

function formatTokens(value: number | null | undefined): string {
  if (value == null) return '—'
  if (value >= 1_000_000) return `${(value / 1_000_000).toFixed(1)}M`
  if (value >= 1000) return `${(value / 1000).toFixed(1)}k`
  return String(value)
}

async function load() {
  loading.value = true
  try {
    const range = queryRange()
    const [summaryData, records] = await Promise.all([
      fetchBillSummary({ ...range, scope: 'all' }),
      fetchBills({ ...range, scope: 'all', limit: 100 }),
    ])
    summary.value = summaryData
    bills.value = records
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '账单加载失败')
  } finally {
    loading.value = false
  }
}

const dailyOption = computed(() => {
  const data = summary.value?.byDay ?? []
  if (!data.length) return null
  return {
    tooltip: { trigger: 'axis' },
    // 图例固定在顶部；底部只留给日期标签，避免图例压住横轴
    legend: { top: 0, textStyle: { color: tokens.text } },
    grid: { left: 48, right: 16, top: 44, bottom: 30, containLabel: true },
    xAxis: {
      type: 'category',
      // 只显示「月-日」；30 天时由 ECharts 自动抽稀，避免日期挤成一团
      data: data.map((item) => item.day.slice(5)),
      axisLabel: { color: tokens.muted, hideOverlap: true, interval: 'auto' },
      axisLine: { lineStyle: { color: tokens.line } },
    },
    yAxis: {
      type: 'value',
      axisLabel: { color: tokens.muted, formatter: (value: number) => formatTokens(value) },
      splitLine: { lineStyle: { color: tokens.line } },
    },
    series: [
      {
        name: '输入 token',
        type: 'bar',
        stack: 'tokens',
        data: data.map((item) => item.promptTokens),
        itemStyle: { color: tokens.blue },
      },
      {
        name: '缓存命中',
        type: 'bar',
        stack: 'tokens',
        data: data.map((item) => item.cachedTokens),
        itemStyle: { color: tokens.cyan },
      },
    ],
  }
})

const kindOption = computed(() => {
  const data = summary.value?.byKind ?? []
  if (!data.length) return null
  return {
    tooltip: { trigger: 'axis' },
    // containLabel：把左侧类别名算进布局，窄卡片里也不会溢出
    grid: { left: 8, right: 24, top: 8, bottom: 8, containLabel: true },
    xAxis: {
      type: 'value',
      axisLabel: { color: tokens.muted, formatter: (value: number) => formatTokens(value) },
      splitLine: { lineStyle: { color: tokens.line } },
    },
    yAxis: {
      type: 'category',
      // 后端已按 token 降序；inverse 让最大的排在最上面
      inverse: true,
      data: data.map((item) => KIND_LABELS[item.kind] ?? item.kind),
      axisLabel: { color: tokens.muted, width: 110, overflow: 'truncate' },
      axisLine: { lineStyle: { color: tokens.line } },
    },
    series: [
      {
        type: 'bar',
        data: data.map((item) => item.tokens),
        itemStyle: { color: tokens.violet },
      },
    ],
  }
})

const toolOption = computed(() => {
  const data = summary.value?.topTools ?? []
  if (!data.length) return null
  return {
    tooltip: { trigger: 'axis' },
    grid: { left: 8, right: 24, top: 8, bottom: 8, containLabel: true },
    xAxis: {
      type: 'value',
      axisLabel: { color: tokens.muted },
      splitLine: { lineStyle: { color: tokens.line } },
    },
    yAxis: {
      type: 'category',
      inverse: true,
      data: data.map((item) => item.tool),
      axisLabel: { color: tokens.muted, width: 150, overflow: 'truncate' },
      axisLine: { lineStyle: { color: tokens.line } },
    },
    series: [
      {
        type: 'bar',
        data: data.map((item) => item.calls),
        itemStyle: { color: tokens.green },
      },
    ],
  }
})

onMounted(load)
</script>

<template>
  <section class="bills" v-loading="loading">
    <header class="bills__head">
      <div>
        <h1>助手账单</h1>
        <p class="bills__sub">上下文 token 的消耗与构成；「输入 token」含每轮重发的工具定义，只统计元数据，不含对话正文。</p>
      </div>
      <div class="bills__ranges">
        <button
          v-for="item in RANGES"
          :key="item.key"
          type="button"
          class="bills__range"
          :class="{ 'is-active': rangeKey === item.key }"
          @click="rangeKey = item.key; load()"
        >
          {{ item.label }}
        </button>
      </div>
    </header>

    <div class="bills__kpis">
      <div class="kpi">
        <span class="kpi__label">请求数</span>
        <strong class="kpi__value">{{ summary?.requests ?? 0 }}</strong>
      </div>
      <div class="kpi">
        <span class="kpi__label">输入 token</span>
        <strong class="kpi__value">{{ formatTokens(summary?.promptTokens) }}</strong>
      </div>
      <div class="kpi">
        <span class="kpi__label">平均上下文</span>
        <strong class="kpi__value">{{ formatTokens(summary?.avgContextTokens) }}</strong>
      </div>
      <div class="kpi">
        <span class="kpi__label">缓存命中率</span>
        <strong class="kpi__value">{{ (summary?.hitRate ?? 0).toFixed(1) }}%</strong>
      </div>
      <div class="kpi">
        <span class="kpi__label">前缀指纹</span>
        <strong class="kpi__value">{{ summary?.fingerprints ?? 0 }}</strong>
      </div>
    </div>

    <div class="bills__charts">
      <div class="card">
        <h2>每天花了多少</h2>
        <p class="card__hint">按天统计实际输入的 token，以及其中命中缓存的部分；命中越高越省。</p>
        <div class="chart-wrap">
          <ChartBox :option="dailyOption" :empty="!dailyOption" empty-text="暂无数据" />
        </div>
      </div>
      <div class="card">
        <h2>花在了哪里</h2>
        <p class="card__hint">
          区间内累计：系统提示词、工具定义、会话与工具结果各占多少；工具定义通常是最大头。
        </p>
        <div class="chart-wrap">
          <ChartBox :option="kindOption" :empty="!kindOption" empty-text="暂无数据" />
        </div>
      </div>
      <div class="card">
        <h2>哪些工具最常被用</h2>
        <p class="card__hint">按请求次数排序；同一请求内多轮调用只算一次，用来看能力使用频次。</p>
        <div class="chart-wrap">
          <ChartBox :option="toolOption" :empty="!toolOption" empty-text="暂无数据" />
        </div>
      </div>
    </div>

    <div class="card">
      <h2>最近请求</h2>
      <el-table :data="bills" size="small" height="360">
        <el-table-column prop="ts" label="时间" width="180">
          <template #default="{ row }">{{ row.ts.replace('T', ' ').slice(0, 19) }}</template>
        </el-table-column>
        <el-table-column prop="userId" label="用户" width="70" />
        <el-table-column prop="conversationId" label="会话" min-width="150" show-overflow-tooltip />
        <el-table-column prop="round" label="轮次" width="60" />
        <el-table-column label="工具" width="60">
          <template #default="{ row }">{{ row.toolCount }}</template>
        </el-table-column>
        <el-table-column label="上下文" width="90">
          <template #default="{ row }">{{ formatTokens(row.estimateTotal) }}</template>
        </el-table-column>
        <el-table-column label="命中率" width="80">
          <template #default="{ row }">
            {{ row.hitRate == null ? '—' : `${row.hitRate.toFixed(1)}%` }}
          </template>
        </el-table-column>
        <el-table-column prop="fingerprint" label="指纹" width="120" />
        <el-table-column label="耗时" width="80">
          <template #default="{ row }">{{ row.latencyMs }}ms</template>
        </el-table-column>
      </el-table>
    </div>
  </section>
</template>

<style scoped>
.bills {
  display: flex;
  flex-direction: column;
  gap: 16px;
  padding: 4px 4px 32px;
}

.bills__head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
}

.bills__head h1 {
  margin: 0;
  font-size: 22px;
}

.bills__sub {
  margin: 4px 0 0;
  color: var(--muted);
  font-size: 13px;
}

.bills__ranges {
  display: flex;
  gap: 8px;
}

.bills__range {
  padding: 6px 12px;
  border: 1px solid var(--line);
  border-radius: 999px;
  background: transparent;
  color: var(--text-2);
  cursor: pointer;
  font-size: 13px;
}

.bills__range.is-active {
  border-color: var(--cyan);
  color: var(--cyan);
}

.bills__kpis {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(150px, 1fr));
  gap: 12px;
}

.kpi {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 14px 16px;
  border: 1px solid var(--line);
  border-radius: 12px;
  background: var(--panel);
}

.kpi__label {
  color: var(--muted);
  font-size: 12px;
}

.kpi__value {
  font-size: 22px;
  color: var(--text-1);
}

.bills__charts {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(320px, 1fr));
  gap: 12px;
}

.card {
  min-width: 0;
  padding: 14px 16px;
  border: 1px solid var(--line);
  border-radius: 12px;
  background: var(--panel);
}

.card__hint {
  margin: 0 0 10px;
  color: var(--muted);
  font-size: 12px;
  line-height: 1.6;
}

/* ChartBox 的高度是 100%，这里给一个确定高度，避免图例/坐标轴挤在一起 */
.chart-wrap {
  height: 260px;
}

.card h2 {
  margin: 0 0 8px;
  font-size: 14px;
  color: var(--text-2);
  font-weight: 600;
}
</style>
