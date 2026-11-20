<script setup lang="ts">
/**
 * 监控看板：现在有哪些飞机在天上、在哪、刚才怎么飞的。
 *
 * <h2>这一页回答的是「现场怎么样了」，不是「有哪些任务」</h2>
 * 任务列表回答「要做什么」，这一页回答「正在做的那些，现在到哪了」。
 * 所以它只装**执行中**的任务 —— 待派发的还没飞、终态的飞完了，
 * 挂在看板上只会让真正紧要的那几条被淹掉。
 *
 * <h2>四个信息一起刷新</h2>
 * 谁在飞、现在在哪、刚才怎么飞、打算怎么飞 —— 后端一个接口给全，
 * 这里只管画。前端拆开去拼就会出现「这一行的位置是 3 秒前、航线是 10 秒前」。
 *
 * <h2>为什么是轮询而不是 WebSocket 推</h2>
 * 遥测是每秒一条、每台设备一份，直接把它推到浏览器上，
 * 看板会变成「一秒重画一次」，而人眼根本不需要那个频率；
 * 3 秒一次的轮询简单、可靠、没有连接状态要维护。
 * 真正需要「立刻知道」的是**告警**，那条走的是 WebSocket（已经做了）。
 */
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { fetchMonitor, fetchTaskTrack, type TaskMonitorVO, type TaskTrackVO } from '@/api/task'
import GeoMap from '@/components/GeoMap.vue'
import { toLine, type GeoLine, type GeoMarker } from '@/utils/geo'

/** 轮询间隔：3 秒。比遥测本身慢，但看板上人是「看个大概」，不需要每秒跳一次 */
const POLL_MS = 3000

const rows = ref<TaskMonitorVO[]>([])
const loading = ref(false)
const error = ref('')
const selectedId = ref<number | null>(null)
const track = ref<TaskTrackVO | null>(null)

let timer: number | null = null

const selected = computed(() => rows.value.find((row) => row.taskId === selectedId.value) || null)

const stats = computed(() => {
  const total = rows.value.length
  const critical = rows.value.filter((row) => (row.criticalAlertCount ?? 0) > 0).length
  const overdue = rows.value.filter((row) => row.overdue).length
  const noPosition = rows.value.filter((row) => !row.current).length
  return { total, critical, overdue, noPosition }
})

/** 一条任务在地图上的颜色：有严重告警优先（那是要立刻处置的），其次超期 */
function toneOf(row: TaskMonitorVO): GeoMarker['tone'] {
  if ((row.criticalAlertCount ?? 0) > 0) return 'alert'
  if (row.overdue) return 'warn'
  return 'point'
}

const markers = computed<GeoMarker[]>(() => {
  const result: GeoMarker[] = []
  for (const row of rows.value) {
    const point = row.current
    if (point) {
      result.push({
        lng: point.lng,
        lat: point.lat,
        // 标签写设备编号：看板上同时有十几架飞机时，「谁是谁」只能靠它
        label: row.droneCode || row.taskNo,
        tone: toneOf(row),
      })
    }
    // 起降点只画**选中那条**的：全部画上会变成一堆紫点，反而看不清飞机在哪
    if (row.taskId === selectedId.value && row.takeoffLng != null && row.takeoffLat != null) {
      result.push({
        lng: row.takeoffLng,
        lat: row.takeoffLat,
        label: row.takeoffName || '起降点',
        tone: 'takeoff',
      })
    }
  }
  return result
})

const lines = computed<GeoLine[]>(() => {
  const result: GeoLine[] = []
  for (const row of rows.value) {
    const isSelected = row.taskId === selectedId.value
    // 选中的那条画**完整轨迹**（下面单独加载），其余只画最近那一小段 ——
    // 否则同一条线上会叠两条颜色不同的线，看不出哪个是哪个
    if (!isSelected && (row.trail || []).length >= 2) {
      result.push(toLine(row.trail, { color: '#64748b', weight: 2 }))
    }
  }
  // 选中那条的计划航线：虚线，与实线的实际轨迹一眼分开
  const planned = selected.value?.plannedRoute?.points
  if (planned && planned.length >= 2) {
    result.push(toLine(planned, { color: '#f59e0b', dashed: true, weight: 3 }))
  }
  if (track.value?.points?.length) {
    result.push(toLine(track.value.points, { color: '#38bdf8', weight: 4 }))
  }
  return result
})

async function load() {
  loading.value = true
  try {
    rows.value = await fetchMonitor()
    error.value = ''
    // 选中的任务可能已经飞完、从看板上消失了 —— 那就把选中状态清掉，
    // 否则右边会停在一块「已经不在这页上了」的地图上
    if (selectedId.value != null && !rows.value.some((row) => row.taskId === selectedId.value)) {
      selectedId.value = null
      track.value = null
    }
  } catch (e) {
    error.value = e instanceof Error ? e.message : '看板数据加载失败'
  } finally {
    loading.value = false
  }
}

/** 选中一条：加载它的完整轨迹（看板上每条都只带一小段航迹，完整的按需取） */
async function select(taskId: number) {
  selectedId.value = taskId
  track.value = null
  try {
    track.value = await fetchTaskTrack(taskId)
  } catch (e) {
    console.warn('任务轨迹加载失败（不影响其余看板）', e)
  }
}

function clearSelection() {
  selectedId.value = null
  track.value = null
}

/** 超期时长写成「1 小时 20 分」这种人话 */
function overdueText(row: TaskMonitorVO) {
  const minutes = row.overdueMinutes
  if (minutes == null) return ''
  if (minutes < 60) return `超期 ${minutes} 分钟`
  return `超期 ${Math.floor(minutes / 60)} 小时 ${minutes % 60} 分`
}

/** 位置的时间够不够新（设备停发时，看板上那一行会「停在原地」） */
function positionAge(row: TaskMonitorVO) {
  if (!row.current?.observedAt) return ''
  return row.current.observedAt.slice(11, 16)
}

onMounted(() => {
  void load()
  timer = window.setInterval(() => {
    // 页面不可见时不轮询：切到别的标签页还每 3 秒打一次接口是纯浪费
    if (document.visibilityState === 'visible') {
      void load()
    }
  }, POLL_MS)
})

onBeforeUnmount(() => {
  if (timer != null) {
    window.clearInterval(timer)
    timer = null
  }
})
</script>

<template>
  <div>
    <!-- ============ 统计 ============ -->
    <div class="stats">
      <div class="stat">
        <span class="stat__label">执行中</span>
        <span class="stat__value">{{ stats.total }}</span>
      </div>
      <div class="stat">
        <span class="stat__label">有严重告警</span>
        <span class="stat__value" :class="{ 'is-violet': stats.critical > 0 }">{{ stats.critical }}</span>
      </div>
      <div class="stat">
        <span class="stat__label">超期</span>
        <span class="stat__value" :class="{ 'is-violet': stats.overdue > 0 }">{{ stats.overdue }}</span>
      </div>
      <div class="stat">
        <span class="stat__label">暂无位置</span>
        <span class="stat__value">{{ stats.noPosition }}</span>
      </div>
      <div class="stat stat--hint">
        <span class="stat__label">{{ loading ? '刷新中…' : '每 3 秒自动刷新' }}</span>
        <span class="stat__value stat__value--small">{{ error || '红=严重告警 · 琥珀=超期 · 蓝=正常' }}</span>
      </div>
    </div>

    <div class="board">
      <!-- ============ 左：执行中任务 ============ -->
      <div class="card board__list">
        <div class="card__head">
          <div class="card__title">
            执行中任务
            <small>点一条在地图上看它的完整轨迹与计划航线</small>
          </div>
        </div>

        <div class="rows">
          <div
            v-for="row in rows"
            :key="row.taskId"
            class="row"
            :class="{ 'is-active': row.taskId === selectedId }"
            @click="select(row.taskId)"
          >
            <div class="row__head">
              <span class="code">{{ row.taskNo }}</span>
              <span class="row__name">{{ row.taskName }}</span>
              <span v-if="row.criticalAlertCount" class="tag tag--red">告警 {{ row.criticalAlertCount }}</span>
              <span v-else-if="row.alertCount" class="tag tag--amber">告警 {{ row.alertCount }}</span>
            </div>
            <div class="row__meta">
              <span>{{ row.pilotName || '未指派' }} · {{ row.droneCode || '未指派设备' }}</span>
            </div>
            <div class="row__meta">
              <template v-if="row.current">
                <span>最后位置 {{ positionAge(row) }}</span>
                <em>{{ row.overdue ? overdueText(row) : '计划 ' + (row.planEndTime || '').slice(11, 16) + ' 结束' }}</em>
              </template>
              <span v-else class="row__warn">暂无位置（设备还没上报带坐标的数据）</span>
            </div>
            <div v-if="row.overdue" class="row__overdue">{{ overdueText(row) }}</div>
          </div>

          <p v-if="!loading && rows.length === 0" class="rows__empty">
            现在没有执行中的任务。<br />
            任务列表里把任务<b>派发</b>给飞手、飞手在小程序里点<b>开始执行</b>之后，它才会出现在这里。
          </p>
        </div>
      </div>

      <!-- ============ 右：地图 ============ -->
      <div class="card board__map">
        <div class="card__head">
          <div class="card__title">
            {{ selected ? selected.taskName + ' · ' + (selected.droneCode || '') : '全部在飞的位置' }}
            <small v-if="selected">
              蓝色实线是实际轨迹、橙色虚线是当时的计划航线；点空白处回到全部
            </small>
            <small v-else>每架飞机一个点（标签是设备编号），灰线是它最近这段航迹</small>
          </div>
          <div class="card__tools">
            <button v-if="selected" class="btn btn--ghost" type="button" @click="clearSelection">
              回到全部
            </button>
          </div>
        </div>

        <GeoMap
          :markers="markers"
          :lines="lines"
          :height="560"
          :fit-key="selectedId ?? 'all'"
          empty-hint="当前没有可显示的位置数据"
        />

        <div v-if="selected && track" class="trackmeta">
          实际轨迹 {{ (track.points || []).length }} 个点<template v-if="track.rawCount && track.rawCount > (track.points || []).length">
            （原始 {{ track.rawCount }} 个，已抽稀）</template
          >
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.stats {
  margin-bottom: 18px;
}

.stat--hint {
  margin-left: auto;
  text-align: right;
}

.stat__value--small {
  font-size: 13px;
  font-weight: 500;
  color: var(--muted);
}

.board {
  display: grid;
  grid-template-columns: 340px minmax(0, 1fr);
  gap: 18px;
  align-items: start;
}

.board__list {
  padding-bottom: 12px;
}

.rows {
  max-height: 560px;
  overflow-y: auto;
  padding: 0 6px;
}

.row {
  padding: 10px 12px;
  margin-bottom: 6px;
  border-radius: 10px;
  cursor: pointer;
  border: 1px solid transparent;
  transition: background 0.2s, border-color 0.2s;
}

.row:hover {
  background: rgba(255, 255, 255, 0.04);
}

.row.is-active {
  background: rgba(56, 189, 248, 0.1);
  border-color: rgba(56, 189, 248, 0.4);
}

.row__head {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  font-size: 13px;
}

.row__name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--text);
}

.row__meta {
  margin-top: 4px;
  font-size: 12px;
  color: var(--muted);
}

.row__meta em {
  margin-left: 8px;
  font-style: normal;
}

.row__warn {
  color: #fbbf24;
}

.row__overdue {
  margin-top: 4px;
  font-size: 12px;
  color: #fca5a5;
}

.rows__empty {
  padding: 30px 16px;
  font-size: 13px;
  line-height: 1.9;
  color: var(--muted);
}

.board__map {
  padding-bottom: 14px;
}

.trackmeta {
  margin-top: 8px;
  font-size: 12px;
  color: var(--muted);
}
</style>
