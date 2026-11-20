<script setup lang="ts">
/**
 * 状态分布环图（ECharts，**按需引入**）。
 *
 * <h2>三个必须守住的点</h2>
 * <ol>
 *   <li><b>按需引入</b>：只引 PieChart + CanvasRenderer。全量引入 echarts 会让首屏
 *       包大几百 KB，而大屏恰恰是「打开就要秒出」的场景</li>
 *   <li><b>实例用 shallowRef</b>：它是第三方大对象，让 Vue 深度代理会卡、会偶发报错</li>
 *   <li><b>尺寸用 ResizeObserver 观察容器</b>，不要监听 window.resize ——
 *       大屏嵌在 iframe 或用了整体 scale 时，窗口没变但容器变了</li>
 * </ol>
 */
import { onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue'
import * as echarts from 'echarts/core'
import { PieChart } from 'echarts/charts'
import { GraphicComponent, LegendComponent, TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'

// ⚠️ GraphicComponent 不能省：环图中间那个「总数」是用 graphic 画的，
// 少了它 ECharts 会在控制台报「Component graphic is used but not imported」，
// 而中间的数字**不显示**（其余部分照常渲染，所以很容易漏掉）
echarts.use([PieChart, GraphicComponent, LegendComponent, TooltipComponent, CanvasRenderer])

const props = defineProps<{
  title: string
  /** 值 → 条数；key 是编码（如 flying），labelOf 负责翻译成中文 */
  data: Record<string, number>
  /** 编码 → 中文名；认不出来就原样显示（后端加了新状态也不至于显示空白） */
  labelOf?: (key: string) => string
  /** 编码 → 颜色；不传用内置的调色板 */
  colorOf?: (key: string) => string
  /** 中心要不要显示总数 */
  showTotal?: boolean
}>()

const container = ref<HTMLElement | null>(null)
/** ⚠️ shallowRef：别让 Vue 深度代理 ECharts 实例 */
const chart = shallowRef<echarts.ECharts | null>(null)
let observer: ResizeObserver | null = null

const DEFAULT_COLORS = ['#38bdf8', '#a78bfa', '#6ee7b7', '#fcd34d', '#fca5a5', '#7dd3fc', '#c4b5fd']

function render() {
  if (!container.value) {
    return
  }
  if (!chart.value) {
    chart.value = echarts.init(container.value)
  }
  const entries = Object.entries(props.data || {})
  const total = entries.reduce((sum, [, value]) => sum + value, 0)

  chart.value.setOption(
    {
      tooltip: { trigger: 'item', formatter: '{b}: {c} ({d}%)' },
      legend: {
        // 大屏上字号要比网页大一档，否则两三米外看不清
        textStyle: { color: 'rgba(200, 224, 255, 0.8)', fontSize: 14 },
        bottom: 0,
        itemWidth: 12,
        itemHeight: 12,
      },
      series: [
        {
          type: 'pie',
          radius: ['48%', '70%'],
          center: ['50%', '45%'],
          avoidLabelOverlap: true,
          itemStyle: { borderColor: 'rgba(6, 18, 36, 0.9)', borderWidth: 2 },
          label: { show: false },
          data: entries.map(([key, value], index) => ({
            name: props.labelOf ? props.labelOf(key) : key,
            value,
            itemStyle: props.colorOf
              ? { color: props.colorOf(key) }
              : { color: DEFAULT_COLORS[index % DEFAULT_COLORS.length] },
          })),
        },
      ],
      // 中间那个总数：大屏上「一共多少」比「各占多少」更常被问
      graphic: props.showTotal === false ? [] : [
        {
          type: 'text',
          left: 'center',
          top: '40%',
          style: {
            text: String(total),
            fontSize: 34,
            fontWeight: 700,
            fill: '#eaf4ff',
            textAlign: 'center',
          },
        },
      ],
    },
    true,
  )
}

function dispose() {
  chart.value?.dispose()
  chart.value = null
}

onMounted(() => {
  render()
  if (container.value) {
    observer = new ResizeObserver(() => chart.value?.resize())
    observer.observe(container.value)
  }
})

// 数据每次刷新都重画（几十个扇区，重建的开销可以忽略）
watch(() => props.data, render, { deep: true })

onBeforeUnmount(() => {
  observer?.disconnect()
  observer = null
  dispose()
})
</script>

<template>
  <div class="donut">
    <div class="donut__title">{{ title }}</div>
    <div ref="container" class="donut__chart"></div>
  </div>
</template>

<style scoped>
.donut {
  display: flex;
  flex-direction: column;
  height: 100%;
  padding: 14px 16px;
  border-radius: 12px;
  background: linear-gradient(160deg, rgba(38, 84, 140, 0.28), rgba(12, 26, 48, 0.5));
  border: 1px solid rgba(90, 150, 220, 0.22);
}

.donut__title {
  font-size: 15px;
  letter-spacing: 1px;
  color: rgba(200, 224, 255, 0.68);
}

.donut__chart {
  flex: 1;
  /* ⚠️ ECharts 在容器高度为 0 时不渲染，而且不报错 —— 必须给确定的高度 */
  min-height: 180px;
}
</style>
