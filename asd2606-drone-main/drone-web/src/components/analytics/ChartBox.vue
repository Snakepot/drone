<script setup lang="ts">
/**
 * 图表壳：只管 ECharts 实例的**生命周期**，不管画什么。
 *
 * <h2>为什么把生命周期单独抽出来</h2>
 * 「初始化 / 跟随容器尺寸 / 数据变了重画 / 卸载时销毁」这四件事，每个图表都要写一遍，
 * 而且每一处写错都是同一类 bug（尺寸不跟、内存泄漏、残影）。
 * 抽出来之后，页面里只剩「数据 → option」这一步。
 *
 * <h2>三个与 StatusDonut 同样的要点</h2>
 * <ol>
 *   <li><b>按需引入</b>：只引用得到的图表与组件，别全量引 echarts</li>
 *   <li><b>实例用 shallowRef</b>：它是第三方大对象，让 Vue 深度代理会卡</li>
 *   <li><b>尺寸用 ResizeObserver 观察容器</b>，不要听 window.resize ——
 *       窗口没变但容器变了的场景（侧栏收起、栅格换行）它观察不到</li>
 * </ol>
 */
import { onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue'
import * as echarts from 'echarts/core'
import { BarChart, LineChart, PieChart } from 'echarts/charts'
import { GridComponent, LegendComponent, TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'

echarts.use([
  PieChart,
  BarChart,
  LineChart,
  GridComponent,
  LegendComponent,
  TooltipComponent,
  CanvasRenderer,
])

const props = defineProps<{
  /** 直接给 ECharts 的配置；为 null 或 empty 时只显示空态 */
  option: Record<string, unknown> | null
  empty?: boolean
  emptyText?: string
}>()

const container = ref<HTMLElement | null>(null)
/** ⚠️ shallowRef：别让 Vue 深度代理 ECharts 实例 */
const chart = shallowRef<echarts.ECharts | null>(null)
let observer: ResizeObserver | null = null

function render() {
  if (props.empty || !props.option || !container.value) {
    return
  }
  if (!chart.value) {
    chart.value = echarts.init(container.value)
  }
  // 第二个参数 notMerge=true：换了区间之后扇区/柱子可能变少，
  // 不整体替换会留着上一次的残影
  chart.value.setOption(props.option, true)
}

onMounted(() => {
  render()
  if (container.value) {
    observer = new ResizeObserver(() => chart.value?.resize())
    observer.observe(container.value)
  }
})

// flush: 'post' 是关键：空态里的容器是 v-if 出来的，
// 要在 DOM 更新之后再 init，否则第一次会 init 到一个 null
watch(() => [props.option, props.empty], render, { deep: true, flush: 'post' })

onBeforeUnmount(() => {
  observer?.disconnect()
  observer = null
  chart.value?.dispose()
  chart.value = null
})
</script>

<template>
  <div class="chart">
    <div v-if="!empty" ref="container" class="chart__canvas"></div>
    <p v-else class="chart__empty">{{ emptyText || '暂无数据' }}</p>
  </div>
</template>

<style scoped>
.chart {
  width: 100%;
  height: 100%;
  min-height: 220px;
}

.chart__canvas {
  width: 100%;
  height: 100%;
  min-height: 220px;
}

.chart__empty {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100%;
  min-height: 220px;
  margin: 0;
  padding: 0 18px;
  font-size: 13px;
  line-height: 1.8;
  color: var(--muted);
  text-align: center;
}
</style>
