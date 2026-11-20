<script setup lang="ts">
/**
 * 通用地图组件：画折线与标记点，可选「点击拾取坐标」。
 *
 * <h2>为什么和高德相关的代码全收在这里</h2>
 * 页面里没人直接碰 `AMap.*`。将来换底图（设计文档 3.2 建议抽 `MapProvider`）
 * 要改的就只有这一个文件，而不是散在四五个页面里。
 * 轨迹那张图（`TrackMap.vue`）也是复用本组件渲染的。
 *
 * <h2>三个必须守住的坑（设计文档「血泪清单」里有原话）</h2>
 * <ol>
 *   <li><b>地图实例用 `shallowRef`</b>：它是第三方的大对象，让 Vue 深度代理它会卡、
 *       会偶发报错；我们也只需要调它的方法，不需要它响应式</li>
 *   <li><b>容器必须有确定高度</b>：高度为 0 时地图不显示，而且<b>不报错</b></li>
 *   <li><b>卸载时 destroy</b>：不然后台来回切页面，实例越堆越多，最后卡死</li>
 * </ol>
 *
 * <h2>传进来的坐标必须是 GCJ-02，这里不做转换</h2>
 * 后端已经转过（见 `SurveyPointVO` / `RouteVO` / `TaskVO` 的说明）。
 * 前端再转一次就是两份实现，而且前端这份没有测试能钉住它。
 */
import { computed, nextTick, onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue'
import { hasAmapKey, loadAmap } from '@/utils/amap'
import type { GeoLine, GeoMarker, GeoPoint, GeoZone } from '@/utils/geo'

const props = withDefaults(
  defineProps<{
    lines?: GeoLine[]
    markers?: GeoMarker[]
    /**
     * 区域（禁飞区）：画成带填充的多边形 / 圆。
     *
     * <p>「怎么画」在这里，「画什么」在页面那边 —— 画区的手势（依次点顶点、
     * 先点圆心再点圆边）由页面用 {@code pick} 事件自己攒草稿，再作为 zones 传进来。
     * 这样本组件不需要知道 MouseTool 那一整套 API，
     * 也不用处理「双击结束绘制」这种在小屏/触控板上很别扭的交互。
     */
    zones?: GeoZone[]
    /** 容器高度（px）。⚠️ 必须给确定值，0 高度时地图不显示且不报错 */
    height?: number
    /** 是否允许点击地图拾取坐标（点位编辑、起降点选址要它） */
    pickable?: boolean
    /** 可拾取时显示的操作提示 */
    pickHint?: string
    /** 没有任何东西可画、又不是可拾取模式时显示的提示 */
    emptyHint?: string
    /** 是否自动缩放视野框住所有内容（可拾取模式下关掉，否则点一下视野就跳） */
    fit?: boolean
    /**
     * 「内容身份」：它变了才自动缩放一次视野。
     *
     * <h2>为什么需要它</h2>
     * 看板每几秒轮询一次，数据一变就重画；如果每次都 `setFitView`，
     * **用户刚拖动的地图会被每 3 秒拽回去一次**。而完全不自动缩放，
     * 换一条任务看的时候又得手动找。
     *
     * <p>所以把「什么时候该重新框视野」交给调用方：它给一个代表当前内容的值
     * （比如选中的任务 id），值变了才缩放一次；不给时保持老行为（每次重画都框）。
     */
    fitKey?: string | number
    /** 初始中心点（只在第一次创建地图时生效） */
    center?: GeoPoint
    /** 初始缩放级别 */
    zoom?: number
  }>(),
  {
    height: 320,
    fit: true,
    zoom: 14,
  },
)

/** 拾取回调：地图上点了一下 */
const emit = defineEmits<{ pick: [GeoPoint] }>()

const TONES: Record<string, string> = {
  start: '#22c55e',
  end: '#f87171',
  plan: '#f59e0b',
  takeoff: '#a78bfa',
  point: '#38bdf8',
  // 看板上「有严重告警」与「超期」两档：红/琥珀，和禁飞区的语义色一致
  alert: '#ef4444',
  warn: '#f59e0b',
}

const container = ref<HTMLElement | null>(null)
/** ⚠️ shallowRef：别让 Vue 深度代理地图实例（见组件注释第 1 条） */
const map = shallowRef<AmapMapInstance | null>(null)
/** 图上当前的覆盖物，重画前先摘掉 */
let overlays: unknown[] = []

const loading = ref(false)
const error = ref('')

/** 上一次自动缩放时的「内容身份」，见 fitKey 的说明 */
let lastFitKey: string | number | undefined = undefined

const configured = hasAmapKey()

/** 有没有东西可画 */
const hasContent = computed(
  () =>
    (props.markers || []).length > 0 ||
    (props.lines || []).some((line) => (line.path || []).length >= 2) ||
    (props.zones || []).length > 0,
)

/**
 * 要不要真的渲染地图容器。
 *
 * <p>可拾取时**永远渲染** —— 哪怕一个点都没有（就是要在空白地图上点第一个点）。
 * 其余情况没内容就不渲染，改为显示一句说得清的提示，比一个空灰框强。
 */
const showMap = computed(() => configured && (props.pickable || hasContent.value))

const showEmptyHint = computed(() => configured && !props.pickable && !hasContent.value)

onMounted(() => {
  if (showMap.value) {
    void render()
  }
})

// ⚠️ `flush: 'post'` 不能省：watch 的默认时机是 DOM 更新**之前**，
// 而容器是 `v-if` 渲染出来的 —— 回调跑起来时容器可能还不存在，
// render 会静默早退，表现就是「地图容器空的、高德脚本一次都没请求、
// 控制台却零报错」。这个 bug 上一轮在轨迹图上真实发生过。
watch(() => [props.lines, props.markers, props.zones], render, { deep: true, flush: 'post' })

onBeforeUnmount(destroyMap)

/**
 * 画（或重画）。
 *
 * <p>每次都是「先清空覆盖物、再重新加」，不做增量更新：
 * 覆盖物最多几百个，重建的开销可以忽略，而增量更新要维护
 * 「哪个数据对应哪个覆盖物」，出错时线会画重或残留 —— 简单做法在这里更可靠。
 */
async function render() {
  if (!showMap.value) {
    return
  }
  if (!container.value) {
    // 容器还没渲染出来 → 等一帧再画（与 watch 的 flush: 'post' 是两道保险）
    await nextTick()
    if (!container.value) {
      // 到这一步还没有容器，说明是模板分支的问题，不是时序问题 ——
      // 记一条，免得又变成「什么都不报」的那种 bug
      console.warn('地图容器仍未渲染，地图未初始化')
      return
    }
  }
  loading.value = true
  error.value = ''
  try {
    const AMap = (await loadAmap()) as AmapNamespace
    if (!map.value) {
      map.value = new AMap.Map(container.value, {
        zoom: props.zoom,
        viewMode: '2D',
        ...(props.center
          ? { center: [props.center.lng, props.center.lat] }
          : {}),
      })
      if (props.pickable) {
        // 监听只挂一次（地图实例只创建一次），挂多次会收到多份回调
        map.value.setDefaultCursor('crosshair')
        map.value.on('click', (e: AmapClickEvent) => {
          emit('pick', { lng: e.lnglat.getLng(), lat: e.lnglat.getLat() })
        })
      }
    }
    redraw(AMap)
  } catch (e) {
    error.value = e instanceof Error ? e.message : '地图加载失败'
  } finally {
    loading.value = false
  }
}

function redraw(AMap: AmapNamespace) {
  const instance = map.value
  if (!instance) {
    return
  }
  if (overlays.length) {
    instance.remove(overlays)
    overlays = []
  }

  // ---- 区域（先画，压在轨迹与航线下面）----
  for (const zone of props.zones || []) {
    if (zone.shape === 'circle') {
      if (!zone.center || !zone.radius) {
        continue
      }
      const circle = new AMap.Circle({
        center: [zone.center.lng, zone.center.lat],
        radius: zone.radius,
        strokeColor: zoneColor(zone),
        strokeWeight: zone.draft ? 2 : 2,
        strokeOpacity: 1,
        ...(zone.draft ? { strokeStyle: 'dashed', strokeDasharray: [8, 6] } : {}),
        fillColor: zoneColor(zone),
        fillOpacity: zone.draft ? 0.12 : 0.22,
      })
      instance.add(circle)
      overlays.push(circle)
      if (zone.label) {
        addZoneLabel(AMap, instance, zone.center.lng, zone.center.lat, zone.label)
      }
    } else {
      const ring = zone.points || []
      if (ring.length < 2) {
        continue
      }
      const polygon = new AMap.Polygon({
        path: ring.map((p) => [p.lng, p.lat]),
        strokeColor: zoneColor(zone),
        strokeWeight: 2,
        strokeOpacity: 1,
        ...(zone.draft ? { strokeStyle: 'dashed', strokeDasharray: [8, 6] } : {}),
        fillColor: zoneColor(zone),
        fillOpacity: zone.draft ? 0.12 : 0.22,
      })
      instance.add(polygon)
      overlays.push(polygon)
      if (zone.label && ring.length >= 3) {
        // 标签放在顶点平均值处（不是严格的几何重心，但给一块区域贴名字够用，
        // 而且就算多边形的重心落在外面，也不会影响「看得见名字」这件事）
        const sum = ring.reduce((acc, p) => ({ lng: acc.lng + p.lng, lat: acc.lat + p.lat }),
          { lng: 0, lat: 0 })
        addZoneLabel(AMap, instance, sum.lng / ring.length, sum.lat / ring.length, zone.label)
      }
    }
  }

  // ---- 折线 ----
  for (const line of props.lines || []) {
    if ((line.path || []).length < 2) {
      continue
    }
    const polyline = new AMap.Polyline({
      path: line.path.map((p) => [p.lng, p.lat]),
      strokeColor: line.color || TONES.point,
      strokeWeight: line.weight || 4,
      strokeOpacity: 0.9,
      // 虚线（计划航线）用 dashArray 表示
      ...(line.dashed ? { strokeStyle: 'dashed', strokeDasharray: [10, 8] } : {}),
      // 圆头，不然折线转弯处看着像有缺口
      lineJoin: 'round',
      lineCap: 'round',
    })
    instance.add(polyline)
    overlays.push(polyline)
  }

  // ---- 标记点 ----
  for (const marker of props.markers || []) {
    const dot = new AMap.CircleMarker({
      center: [marker.lng, marker.lat],
      radius: 6,
      strokeColor: '#ffffff',
      strokeWeight: 2,
      fillColor: TONES[marker.tone || 'point'] || TONES.point,
      fillOpacity: 1,
    })
    instance.add(dot)
    overlays.push(dot)

    if (marker.label) {
      const text = new AMap.Text({
        text: marker.label,
        position: [marker.lng, marker.lat],
        // 往上挪一点，别压住圆点本身
        offset: new AMap.Pixel(0, -18),
        anchor: 'bottom-center',
        style: {
          padding: '1px 4px',
          border: 'none',
          'border-radius': '3px',
          'background-color': 'rgba(15, 23, 42, 0.78)',
          color: '#e2e8f0',
          'font-size': '11px',
        },
      })
      instance.add(text)
      overlays.push(text)
    }
  }

  // 有 fitKey 时只在它变化后缩放一次 —— 否则轮询刷新会把用户拖动的地图拽回去
  const shouldFit = props.fit && hasContent.value
    && (props.fitKey === undefined || props.fitKey !== lastFitKey)
  if (shouldFit) {
    instance.setFitView(overlays)
    lastFitKey = props.fitKey
  }
}

/**
 * 区域的颜色按「严不严重」分：完全禁飞红、限高区琥珀。
 *
 * <p>这是**语义色**，不让页面传十六进制 —— 同一个含义在两个页面里
 * 写成两种颜色，看的人会以为是两回事。
 */
function zoneColor(zone: GeoZone): string {
  return zone.forbidden === false ? '#f59e0b' : '#ef4444'
}

function addZoneLabel(AMap: AmapNamespace, instance: AmapMapInstance, lng: number, lat: number, text: string) {
  const label = new AMap.Text({
    text,
    position: [lng, lat],
    anchor: 'center',
    style: {
      padding: '1px 5px',
      border: 'none',
      'border-radius': '3px',
      'background-color': 'rgba(15, 23, 42, 0.78)',
      color: '#e2e8f0',
      'font-size': '11px',
    },
  })
  instance.add(label)
  overlays.push(label)
}

function destroyMap() {
  if (map.value) {
    map.value.destroy()
    map.value = null
  }
  overlays = []
}

/**
 * 只声明我们用到的那几个 API。
 *
 * <p>高德没有随包发布的官方类型，自己收一个小的就够了 ——
 * 声明得越全，将来升级 SDK 时对不上的地方就越多，而多出来的那些我们并不用。
 */
interface AmapClickEvent {
  lnglat: { getLng: () => number; getLat: () => number }
}

interface AmapMapInstance {
  add: (o: unknown) => void
  remove: (o: unknown) => void
  setFitView: (o: unknown) => void
  setDefaultCursor: (cursor: string) => void
  on: (event: string, handler: (e: AmapClickEvent) => void) => void
  destroy: () => void
}

interface AmapNamespace {
  Map: new (el: HTMLElement, opts: Record<string, unknown>) => AmapMapInstance
  Polyline: new (opts: Record<string, unknown>) => unknown
  Polygon: new (opts: Record<string, unknown>) => unknown
  CircleMarker: new (opts: Record<string, unknown>) => unknown
  Circle: new (opts: Record<string, unknown>) => unknown
  Text: new (opts: Record<string, unknown>) => unknown
  Pixel: new (x: number, y: number) => unknown
}
</script>

<template>
  <div class="geo">
    <!-- 没配 Key：说清怎么回事，而不是留一个灰框让人以为网断了 -->
    <div v-if="!configured" class="geo__hint">
      未配置高德 Key，地图暂不显示。把 <code>VITE_AMAP_KEY</code> /
      <code>VITE_AMAP_SECURITY_CODE</code> 填在 <code>drone-web/.env.local</code>
      （那个文件不进仓库），然后<b>重启开发服务器</b> ——
      见 <code>docs/地图接入方案.md</code> 2.4 节。
    </div>

    <template v-else-if="showMap">
      <!-- 容器必须有确定高度，高度由调用方给 -->
      <div ref="container" class="geo__map" :style="{ height: height + 'px' }"></div>
      <div class="geo__meta">
        <span v-if="loading">地图加载中…</span>
        <span v-else-if="error" class="geo__error">{{ error }}</span>
        <span v-else-if="pickable" class="geo__pick">{{ pickHint || '点击地图选一个位置' }}</span>
      </div>
    </template>

    <div v-else-if="showEmptyHint" class="geo__hint">
      {{ emptyHint || '暂无坐标数据' }}
    </div>
  </div>
</template>

<style scoped>
.geo__map {
  /* ⚠️ 高度由 :style 给出（默认 320px）：0 高度时地图不显示，而且不报错 */
  width: 100%;
  border-radius: 8px;
  overflow: hidden;
  border: 1px solid var(--line);
}

.geo__hint {
  padding: 24px 16px;
  border-radius: 8px;
  font-size: 13px;
  line-height: 1.8;
  color: var(--muted);
  background: rgba(255, 255, 255, 0.03);
  border: 1px dashed var(--line);
}

.geo__hint code {
  padding: 1px 4px;
  border-radius: 3px;
  background: rgba(255, 255, 255, 0.08);
  font-size: 12px;
}

.geo__meta {
  margin-top: 6px;
  font-size: 12px;
  color: var(--muted);
  min-height: 16px;
}

.geo__pick {
  color: #fbbf24;
}

.geo__error {
  color: #fca5a5;
}
</style>
