<script setup lang="ts">
/**
 * 任务地图：把「计划」和「实际」画在同一张图上。
 *
 * <h2>实线是实际飞出来的，虚线是当时计划的</h2>
 * 这是这块地图最先交付的价值：<b>一眼看出哪没飞到、哪飞偏了</b>。
 * 两条线分别来自两个地方 ——
 * 实际轨迹来自设备上报的坐标（`task_evidence`），
 * 计划航线来自任务自己的<b>快照</b>（`task.route_snapshot`，模板后来改了也不影响它）。
 *
 * <h2>这里的活儿只是「把数据摆成 GeoMap 要的样子」</h2>
 * 真正碰高德 API 的只有 `GeoMap.vue` 一个文件，本组件不 import 任何 AMap 相关的东西。
 */
import { computed } from 'vue'
import GeoMap from '@/components/GeoMap.vue'
import { toLine, type GeoLine, type GeoMarker } from '@/utils/geo'
import type { TaskTrackPoint } from '@/api/task'
import type { RoutePointVO } from '@/api/route'

const props = defineProps<{
  /** 实际轨迹点（GCJ-02，已按时间升序） */
  points?: TaskTrackPoint[]
  /** 抽稀前的原始点数，用来告诉用户「画的是抽稀后的」 */
  rawCount?: number
  /** 当时的计划航线（GCJ-02）；没选航线或快照读不出来时为空 */
  planned?: RoutePointVO[]
  /** 起降点（GCJ-02） */
  takeoff?: { lng?: number; lat?: number; name?: string }
}>()

/** 有没有实际轨迹 */
const hasTrack = computed(() => (props.points || []).length > 0)
/** 有没有计划航线（两点以上才画得成线） */
const hasPlan = computed(() => (props.planned || []).length >= 2)
/** 有没有起降点可标 */
const hasTakeoff = computed(() => props.takeoff?.lng != null && props.takeoff?.lat != null)

const lines = computed<GeoLine[]>(() => [
  toLine(props.planned, { color: '#f59e0b', dashed: true, weight: 3 }),
  toLine(props.points, { color: '#38bdf8', weight: 4 }),
])

const markers = computed<GeoMarker[]>(() => {
  const result: GeoMarker[] = []

  // 计划航点：标序号，方便对着飞手的执行情况核
  ;(props.planned || []).forEach((p, index) => {
    result.push({ lng: p.lng, lat: p.lat, label: String(p.seq || index + 1), tone: 'plan' })
  })

  if (hasTakeoff.value) {
    result.push({
      lng: props.takeoff?.lng as number,
      lat: props.takeoff?.lat as number,
      label: props.takeoff?.name || '起降点',
      tone: 'takeoff',
    })
  }

  const track = props.points || []
  const first = track[0]
  const last = track[track.length - 1]
  if (first && last && track.length >= 2) {
    result.push({ lng: first.lng, lat: first.lat, label: '起飞', tone: 'start' })
    result.push({ lng: last.lng, lat: last.lat, label: '最后位置', tone: 'end' })
  }

  return result
})

/** 有没有被抽稀过（用来决定要不要显示「原始 N 个」） */
const showSampledHint = computed(
  () => Boolean(props.rawCount) && (props.rawCount as number) > (props.points || []).length,
)
</script>

<template>
  <div class="track">
    <!-- 计划、实际、起降点三样都没有：说清是「没有数据」，不是「坏了」 -->
    <GeoMap
      v-if="hasTrack || hasPlan || hasTakeoff"
      :lines="lines"
      :markers="markers"
      :height="320"
    />
    <div v-else class="track__hint">
      这条任务没有可用的位置数据 —— 既没有选航线/起降点，设备也没上报过经纬度
    </div>

    <div v-if="hasTrack || hasPlan || hasTakeoff" class="track__meta">
      <span v-if="hasTrack">
        轨迹 {{ (points || []).length }} 个点<template v-if="showSampledHint">
          （原始 {{ rawCount }} 个，已抽稀）</template
        >
      </span>
      <span v-else>这条任务还没有实际轨迹 —— 设备还没上报过带经纬度的数据</span>

      <em v-if="hasPlan">橙色虚线是当时计划的航线</em>
      <em v-if="hasTakeoff">紫色是起降点</em>
    </div>
  </div>
</template>

<style scoped>
.track__hint {
  padding: 24px 16px;
  border-radius: 8px;
  font-size: 13px;
  line-height: 1.8;
  color: var(--muted);
  background: rgba(255, 255, 255, 0.03);
  border: 1px dashed var(--line);
}

.track__meta {
  margin-top: 8px;
  font-size: 12px;
  color: var(--muted);
}

.track__meta em {
  margin-left: 10px;
  font-style: normal;
}
</style>
