<script setup lang="ts">
/**
 * 实时时钟。
 *
 * <p><b>用本机时间，不请求服务端</b> —— 每秒一次请求毫无意义，
 * 而大屏这台机器的时间通常在同一个内网里、由同一台 NTP 校准。
 *
 * <p>顺带显示「数据截至」：整屏都没有一个「刷新中」的转圈，
 * 看的人只能靠这两个时间来判断画面新不新 ——
 * 一块看起来很正常的屏、如果数据是两小时前的，那它就是在骗人。
 */
import { onBeforeUnmount, onMounted, ref } from 'vue'

defineProps<{
  /** 后端给的「这份数据是什么时候算出来的」 */
  generatedAt?: string
}>()

const now = ref('')
let timer: number | null = null

function tick() {
  const date = new Date()
  const pad = (value: number) => String(value).padStart(2, '0')
  const weekdays = ['日', '一', '二', '三', '四', '五', '六']
  now.value =
    `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}` +
    ` 星期${weekdays[date.getDay()]} ` +
    `${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
}

onMounted(() => {
  tick()
  timer = window.setInterval(tick, 1000)
})

onBeforeUnmount(() => {
  if (timer != null) {
    window.clearInterval(timer)
    timer = null
  }
})
</script>

<template>
  <div class="clock">
    <span class="clock__now">{{ now }}</span>
    <!-- 数据时刻只显示到分钟：秒级的差别没人在意，反而显得画面在抖 -->
    <span v-if="generatedAt" class="clock__data">数据截至 {{ generatedAt.slice(11, 16) }}</span>
  </div>
</template>

<style scoped>
.clock {
  display: flex;
  align-items: baseline;
  gap: 16px;
}

.clock__now {
  font-size: 22px;
  font-variant-numeric: tabular-nums;
  color: #eaf4ff;
}

.clock__data {
  font-size: 13px;
  color: rgba(180, 208, 240, 0.55);
}
</style>
