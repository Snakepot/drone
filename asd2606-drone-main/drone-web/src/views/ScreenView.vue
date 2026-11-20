<script setup lang="ts">
/**
 * 数字大屏（见 `docs/数字大屏方案.md`）。
 *
 * <h2>它和监控看板的区别：这一屏是给「站着看的人」用的</h2>
 * 没有侧边栏、没有筛选、基本不点 —— 自动刷新、自动轮播。
 * 屏幕按 **1920×1080** 设计，实际多大就整体缩放多少（见 `utils/screen-scale`）。
 *
 * <h2>三件事按「数据变得多快」分了三档刷新</h2>
 * <ul>
 *   <li>地图上的飞机：3 秒（每秒都在变）</li>
 *   <li>告警流：10 秒</li>
 *   <li>各类总数、分布图：60 秒（分钟级都不变）</li>
 * </ul>
 * 一整屏都按最快的那档刷，等于每 3 秒重画一遍，而大屏机器往往是低配小主机。
 *
 * <h2>只有当前这一屏在请求</h2>
 * 轮播到地图时，统计那一屏**不再请求** —— 否则「三屏各刷各的」把请求量乘以三，
 * 而屏上只看得到一屏。
 *
 * <h2>卡片按「服务端返回了哪些块」显隐</h2>
 * 不在这里判角色：`summary` 里没有那个 key，就不渲染那张卡。
 * 权限只有一处实现（后端），前端只管画。
 */
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import {
  fetchRecentAlerts,
  fetchScreenConfig,
  fetchSummary,
  type DashboardSummary,
  type RecentAlert,
  type ScreenConfig,
} from '@/api/dashboard'
import { fetchMonitor, fetchTaskTrack, type TaskMonitorVO, type TaskTrackVO } from '@/api/task'
import { useScreenScale } from '@/utils/screen-scale'
import { toLine, type GeoLine, type GeoMarker } from '@/utils/geo'
import { screenAuthExpired } from '@/api/request'
import StatCard from '@/components/screen/StatCard.vue'
import StatusDonut from '@/components/screen/StatusDonut.vue'
import AlertFeed from '@/components/screen/AlertFeed.vue'
import DataClock from '@/components/screen/DataClock.vue'
import GeoMap from '@/components/GeoMap.vue'

/** 三屏的定义。取值与后端 `drone.screen.carousel-pages` 里的字符串对应 */
type ScreenKey = 'overview' | 'map' | 'alerts'

const SCREEN_TITLES: Record<ScreenKey, string> = {
  overview: '运行总览',
  map: '在飞位置',
  alerts: '告警态势',
}

/** 后端配置的兜底：拿不到配置时也得能跑（默认值与 application.yml 一致） */
const FALLBACK_CONFIG: ScreenConfig = {
  carouselEnabled: true,
  carouselPages: ['overview', 'map', 'alerts'],
  carouselSeconds: 15,
  carouselPinOnCritical: false,
  carouselPinMaxSeconds: 300,
  refreshMapSeconds: 3,
  refreshLiveSeconds: 10,
  refreshStatSeconds: 60,
  pauseWhenHidden: true,
  nightPauseEnabled: false,
  nightPauseFrom: '02:00',
  nightPauseTo: '06:00',
}

const config = ref<ScreenConfig>(FALLBACK_CONFIG)
const current = ref<ScreenKey>('overview')
const summary = ref<DashboardSummary | null>(null)
const recent = ref<RecentAlert[]>([])
const liveRows = ref<TaskMonitorVO[]>([])
const focusedTrack = ref<TaskTrackVO | null>(null)
const loadError = ref('')

const { shellStyle } = useScreenScale()

const pages = computed<ScreenKey[]>(() => {
  const list = (config.value.carouselPages || []).filter((item): item is ScreenKey =>
    ['overview', 'map', 'alerts'].includes(item),
  )
  // 配置写错（或写空）时至少要有一屏，否则大屏会白屏
  return list.length ? list : ['overview']
})

// ---------------------------------------------------------------------------
// 刷新：三档 + 夜里 + 不可见
// ---------------------------------------------------------------------------

const timers: number[] = []
let carouselTimer: number | null = null
/** 被 critical 钉住的起始时刻（用于 carouselPinMaxSeconds 的兜底） */
let pinnedSince = 0

/** 现在该不该刷：页面不可见、或落在夜间停刷区间里，就都不刷 */
function shouldRefresh(): boolean {
  if (config.value.pauseWhenHidden && document.visibilityState !== 'visible') {
    return false
  }
  if (!config.value.nightPauseEnabled) {
    return true
  }
  const now = new Date()
  const minutes = now.getHours() * 60 + now.getMinutes()
  const from = toMinutes(config.value.nightPauseFrom)
  const to = toMinutes(config.value.nightPauseTo)
  if (from == null || to == null) {
    return true
  }
  // 跨零点也支持（如 22:00 ~ 06:00）
  return from <= to ? !(minutes >= from && minutes < to) : !(minutes >= from || minutes < to)
}

function toMinutes(text: string): number | null {
  const match = /^(\d{1,2}):(\d{2})$/.exec((text || '').trim())
  if (!match) {
    return null
  }
  return Number(match[1]) * 60 + Number(match[2])
}

/** 每一档一个定时器；回调自己判断「当前这一屏要不要这份数据」 */
function startTimers() {
  stopTimers()
  const tiers: Array<[number, () => void]> = [
    [config.value.refreshStatSeconds, loadStat],
    [config.value.refreshLiveSeconds, loadLive],
    [config.value.refreshMapSeconds, loadMap],
  ]
  for (const [seconds, action] of tiers) {
    timers.push(
      window.setInterval(() => {
        if (shouldRefresh()) {
          void action()
        }
      }, Math.max(2, seconds) * 1000),
    )
  }
}

function stopTimers() {
  while (timers.length) {
    const timer = timers.pop()
    if (timer != null) {
      window.clearInterval(timer)
    }
  }
}

// ---------------------------------------------------------------------------
// 取数：每块只在自己那一屏（或告警屏上的那份 live 数据）需要时才请求
// ---------------------------------------------------------------------------

async function loadStat() {
  // 总览屏要它；告警屏要它的 bySeverity / open
  if (current.value !== 'overview' && current.value !== 'alerts') {
    return
  }
  try {
    summary.value = await fetchSummary()
    loadError.value = ''
  } catch (e) {
    loadError.value = e instanceof Error ? e.message : '数据加载失败'
  }
}

async function loadLive() {
  if (current.value !== 'alerts') {
    return
  }
  try {
    recent.value = await fetchRecentAlerts(20)
  } catch {
    // 提示由 request 统一弹过；大屏上继续显示上一次的数据比清空好
  }
}

async function loadMap() {
  // 总览屏中间也有一张小地图，所以这两屏都要这份数据；只有告警屏不需要
  if (current.value !== 'map' && current.value !== 'overview') {
    return
  }
  try {
    liveRows.value = await fetchMonitor()
    // 选中第一条的完整轨迹（大屏要看得见「飞过的线」）
    const first = liveRows.value[0]
    if (first) {
      focusedTrack.value = await fetchTaskTrack(first.taskId)
    } else {
      focusedTrack.value = null
    }
  } catch {
    // 同上
  }
}

// ---------------------------------------------------------------------------
// 轮播
// ---------------------------------------------------------------------------

function scheduleNext() {
  if (carouselTimer != null) {
    window.clearTimeout(carouselTimer)
  }
  if (!config.value.carouselEnabled || pages.value.length <= 1) {
    return
  }
  const pinned = isPinned()
  const seconds = pinned ? config.value.carouselPinMaxSeconds : config.value.carouselSeconds
  // ⚠️ 递归 setTimeout 而不是 setInterval：后者在「切屏 + 刷新」同时发生时会叠加计时器
  carouselTimer = window.setTimeout(() => {
    if (!shouldRefresh()) {
      scheduleNext()
      return
    }
    goNext()
    scheduleNext()
  }, Math.max(5, seconds) * 1000)
}

/** 有未处理的 critical 时是否停在告警屏（要配 carouselPinOnCritical） */
function isPinned(): boolean {
  if (!config.value.carouselPinOnCritical || current.value !== 'alerts') {
    return false
  }
  const critical = summary.value?.alerts?.bySeverity?.critical || 0
  if (critical <= 0) {
    pinnedSince = 0
    return false
  }
  if (pinnedSince === 0) {
    pinnedSince = Date.now()
  }
  // 兜底：就算钉住，最长也只停 carouselPinMaxSeconds ——
  // 没有它，一条没人处理的 critical 会让大屏永远卡在告警屏
  const maxMs = Math.max(30, config.value.carouselPinMaxSeconds) * 1000
  return Date.now() - pinnedSince < maxMs
}

function goNext() {
  const list = pages.value
  const index = list.indexOf(current.value)
  goTo(list[(index + 1) % list.length] || list[0]!)
}

function goTo(screen: ScreenKey) {
  current.value = screen
  // 切过去立刻拉一次，不要等下一个刷新周期（否则先看到的是旧数据）
  if (screen === 'overview' || screen === 'alerts') {
    void loadStat()
  }
  if (screen === 'alerts') {
    void loadLive()
  }
  if (screen === 'map') {
    void loadMap()
  }
  // 人手动点了一下，说明他正在看这一屏 —— 重新计时，别马上转走
  scheduleNext()
}

function toggleFullscreen() {
  if (document.fullscreenElement) {
    void document.exitFullscreen()
  } else {
    void document.documentElement.requestFullscreen()
  }
}

// ---------------------------------------------------------------------------
// 显示用的小工具
// ---------------------------------------------------------------------------

const TASK_STATUS_LABELS: Record<string, string> = {
  pending: '待派发',
  dispatched: '已派发',
  executing: '执行中',
  finished: '已完成',
  failed: '已失败',
  cancelled: '已取消',
}

const DEVICE_STATUS_LABELS: Record<string, string> = {
  idle: '待命',
  assigned: '已派出',
  flying: '飞行中',
  charging: '充电中',
  maintenance: '维护中',
  offline: '离线',
}

const SEVERITY_LABELS: Record<string, string> = {
  critical: '严重',
  warning: '警告',
  info: '提示',
}

/** 在线率显示成百分比，保留一位小数 */
const onlineRateText = computed(() => {
  const rate = summary.value?.devices?.onlineRate
  return rate == null ? '—' : `${(Number(rate) * 100).toFixed(1)}%`
})

const liveMarkers = computed<GeoMarker[]>(() =>
  liveRows.value.flatMap((row) => {
    if (!row.current) {
      return []
    }
    const tone = (row.criticalAlertCount || 0) > 0 ? 'alert' : row.overdue ? 'warn' : 'point'
    return [
      { lng: row.current.lng, lat: row.current.lat, label: row.droneCode || row.taskNo, tone } as GeoMarker,
    ]
  }),
)

const liveLines = computed<GeoLine[]>(() => {
  const lines: GeoLine[] = []
  for (const row of liveRows.value) {
    if ((row.trail || []).length >= 2) {
      lines.push(toLine(row.trail, { color: '#64748b', weight: 2 }))
    }
  }
  const first = liveRows.value[0]
  if (first?.plannedRoute?.points?.length) {
    lines.push(toLine(first.plannedRoute.points, { color: '#f59e0b', dashed: true, weight: 3 }))
  }
  if (focusedTrack.value?.points?.length) {
    lines.push(toLine(focusedTrack.value.points, { color: '#38bdf8', weight: 4 }))
  }
  return lines
})

function enterFullscreenOnFirstClick() {
  // 大屏通常由人打开一次就挂着；不强制全屏（浏览器也要求用户手势），
  // 所以在顶栏给一个按钮，需要的人自己按
}

onMounted(async () => {
  try {
    config.value = await fetchScreenConfig()
  } catch {
    // 拿不到配置就用兜底值跑起来 —— 大屏白屏比「刷新间隔不理想」严重得多
  }
  goTo(pages.value[0]!)
  startTimers()
  enterFullscreenOnFirstClick()
})

onBeforeUnmount(() => {
  stopTimers()
  if (carouselTimer != null) {
    window.clearTimeout(carouselTimer)
    carouselTimer = null
  }
})
</script>

<template>
  <div id="screen-viewport" class="viewport">
    <div class="shell" :style="shellStyle">
      <!-- ============ 顶栏 ============ -->
      <header class="topbar">
        <div class="topbar__brand">
          <span class="topbar__title">无人机管理平台</span>
          <span class="topbar__sub">{{ SCREEN_TITLES[current] }}</span>
        </div>
        <DataClock :generated-at="summary?.generatedAt" />
        <div class="topbar__actions">
          <span v-if="loadError" class="topbar__error">{{ loadError }}</span>
          <button class="topbar__btn" type="button" @click="toggleFullscreen">全屏</button>
        </div>
      </header>

      <!-- ============ 总览屏 ============ -->
      <main v-if="current === 'overview'" class="body body--overview">
        <!-- 任务 -->
        <section v-if="summary?.tasks" class="panel panel--tasks">
          <div class="panel__title">任务</div>
          <div class="panel__grid">
            <StatCard label="任务总数" :value="summary.tasks.total" to="/task" />
            <StatCard
              label="执行中"
              :value="summary.tasks.byStatus?.executing || 0"
              tone="good"
              to="/monitor"
            />
            <StatCard label="待派发" :value="summary.tasks.byStatus?.pending || 0" />
            <StatCard label="今日新增" :value="summary.tasks.todayNew" />
            <StatCard
              label="超期"
              :value="summary.tasks.overdue"
              :tone="summary.tasks.overdue > 0 ? 'danger' : 'normal'"
              hint="执行中且已过计划结束时间"
            />
          </div>
        </section>

        <!-- 我的任务：只有飞手才拿得到这一块 -->
        <section v-else-if="summary?.myTasks" class="panel panel--tasks">
          <div class="panel__title">我的任务</div>
          <div class="panel__grid">
            <StatCard label="我的任务" :value="summary.myTasks.total" />
            <StatCard label="待办" :value="summary.myTasks.pending" tone="warn" />
            <StatCard label="进行中" :value="summary.myTasks.doing" tone="good" />
            <StatCard label="已结束" :value="summary.myTasks.done" />
            <StatCard
              v-if="summary.myAlerts"
              label="我的告警"
              :value="summary.myAlerts.open"
              :tone="summary.myAlerts.open > 0 ? 'danger' : 'normal'"
            />
          </div>
        </section>

        <!-- 设备 + 人员 + 地理 -->
        <section class="panel panel--left">
          <div class="panel__title">设备与人员</div>
          <div v-if="summary?.devices" class="panel__grid">
            <StatCard label="设备总数" :value="summary.devices.total" to="/device" />
            <StatCard
              label="在飞"
              :value="summary.devices.byStatus?.flying || 0"
              tone="good"
              to="/monitor"
            />
            <StatCard label="维护中" :value="summary.devices.byStatus?.maintenance || 0" />
            <StatCard
              label="在线率"
              :value="onlineRateText"
              :hint="`${summary.devices.onlineWindowMinutes} 分钟内有数据上报`"
            />
            <StatCard label="累计架次" :value="summary.devices.totalFlights" />
            <StatCard label="累计时长" :value="`${summary.devices.totalHours} h`" />
          </div>
          <div v-if="summary?.staff" class="panel__grid panel__grid--tight">
            <StatCard label="启用账号" :value="summary.staff.total" to="/staff" />
            <StatCard label="空闲" :value="summary.staff.idle" />
            <StatCard label="作业中" :value="summary.staff.busy" tone="good" />
          </div>
        </section>

        <section class="panel panel--map">
          <div class="panel__title">
            在飞位置
            <span class="panel__hint">{{ liveRows.length }} 条执行中任务</span>
          </div>
          <GeoMap
            :markers="liveMarkers"
            :lines="liveLines"
            :height="300"
            :fit="false"
            empty-hint="当前没有执行中的任务"
          />
        </section>

        <section class="panel panel--right">
          <AlertFeed v-if="summary?.alerts" :items="summary.alerts.recent || []" :limit="6" />
          <div v-else class="panel panel__empty">本账号没有告警查看权限</div>

          <div v-if="summary?.geo" class="panel__grid panel__grid--geo">
            <StatCard label="点位" :value="summary.geo.points" to="/point" />
            <StatCard label="航线" :value="summary.geo.routes" to="/route" />
            <StatCard label="禁飞区" :value="summary.geo.zones" to="/zone" />
          </div>

          <div v-if="summary?.evidence" class="panel__grid panel__grid--tight">
            <StatCard
              label="今日数据"
              :value="summary.evidence.today"
              :hint="`最近一小时 ${summary.evidence.lastHour} 条`"
            />
          </div>
        </section>

        <!-- 两块分布图：有权限才有数据，没权限整块不渲染 -->
        <section v-if="summary?.tasks?.byStatus" class="panel panel--donut">
          <StatusDonut
            title="任务状态分布"
            :data="summary.tasks.byStatus"
            :label-of="(key: string) => TASK_STATUS_LABELS[key] || key"
          />
        </section>
        <section v-if="summary?.devices?.byStatus" class="panel panel--donut">
          <StatusDonut
            title="设备状态分布"
            :data="summary.devices.byStatus"
            :label-of="(key: string) => DEVICE_STATUS_LABELS[key] || key"
          />
        </section>
      </main>

      <!-- ============ 地图屏 ============ -->
      <main v-else-if="current === 'map'" class="body body--map">
        <section class="panel panel--mapFull">
          <div class="panel__title">
            在飞位置与航迹
            <span class="panel__hint">
              {{ liveRows.length }} 条执行中任务 · 蓝线是第一条的实际轨迹 · 橙色虚线是它的计划航线
            </span>
          </div>
          <GeoMap
            :markers="liveMarkers"
            :lines="liveLines"
            :height="820"
            :fit="false"
            empty-hint="当前没有执行中的任务"
          />
        </section>
        <section class="panel panel--mapSide">
          <div class="panel__title">执行中任务</div>
          <ul class="tasklist">
            <li v-for="row in liveRows" :key="row.taskId" class="tasklist__item">
              <span class="tasklist__no">{{ row.taskNo }}</span>
              <span class="tasklist__name">{{ row.taskName }}</span>
              <span class="tasklist__meta">{{ row.pilotName || '—' }} · {{ row.droneCode || '—' }}</span>
              <span v-if="row.overdue" class="tasklist__badge is-danger">超期</span>
              <span v-else-if="(row.criticalAlertCount || 0) > 0" class="tasklist__badge is-danger">
                告警 {{ row.criticalAlertCount }}
              </span>
            </li>
          </ul>
          <p v-if="liveRows.length === 0" class="panel__empty">当前没有执行中的任务</p>
        </section>
      </main>

      <!-- ============ 告警屏 ============ -->
      <main v-else class="body body--alerts">
        <section class="panel panel--alertStat">
          <div class="panel__title">未处理告警</div>
          <div class="panel__grid">
            <StatCard
              label="未处理"
              :value="summary?.alerts?.open ?? 0"
              :tone="(summary?.alerts?.open ?? 0) > 0 ? 'danger' : 'good'"
              hint="待处理 + 处理中"
            />
            <StatCard
              label="严重"
              :value="summary?.alerts?.bySeverity?.critical || 0"
              tone="danger"
            />
            <StatCard
              label="警告"
              :value="summary?.alerts?.bySeverity?.warning || 0"
              tone="warn"
            />
            <StatCard label="今日新增" :value="summary?.alerts?.todayNew ?? 0" hint="含已处理" />
          </div>
        </section>

        <section v-if="summary?.alerts?.bySeverity" class="panel panel--donut">
          <StatusDonut
            title="未处理告警级别分布"
            :data="summary.alerts.bySeverity"
            :label-of="(key: string) => SEVERITY_LABELS[key] || key"
          />
        </section>

        <section class="panel panel--alertFeed">
          <AlertFeed :items="recent" :limit="18" />
        </section>
      </main>

      <!--
        登录过期：就地盖一层大字，**不跳登录页**（跳走了就再也没人把它切回来）。
        底下保留已经取到的数据 —— 看到一份旧数据，也比全黑强。
      -->
      <div v-if="screenAuthExpired" class="expired">
        <div class="expired__box">
          <div class="expired__title">登录已过期</div>
          <div class="expired__desc">
            请在这台机器上重新登录后继续使用（数据仍显示的是过期前的那一份）
          </div>
        </div>
      </div>

      <!-- ============ 轮播指示（不做可点的标签栏 —— 大屏上没人点）============ -->
      <footer class="dots">
        <span
          v-for="page in pages"
          :key="page"
          class="dots__dot"
          :class="{ 'is-active': page === current }"
        ></span>
      </footer>
    </div>
  </div>
</template>

<style scoped>
/* ---------- 缩放外壳 ---------- */
.viewport {
  position: fixed;
  inset: 0;
  overflow: hidden;
  background: radial-gradient(1200px 700px at 50% 0%, #0b1c33 0%, #050b16 60%, #03060f 100%);
}

.shell {
  display: flex;
  flex-direction: column;
  padding: 20px 28px 12px;
  color: #eaf4ff;
}

/* ---------- 顶栏 ---------- */
.topbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: 72px;
  flex: none;
}

.topbar__brand {
  display: flex;
  align-items: baseline;
  gap: 14px;
}

.topbar__title {
  font-size: 28px;
  font-weight: 700;
  letter-spacing: 2px;
}

.topbar__sub {
  font-size: 17px;
  color: rgba(125, 211, 252, 0.85);
}

.topbar__actions {
  display: flex;
  align-items: center;
  gap: 14px;
}

.topbar__error {
  font-size: 14px;
  color: #fca5a5;
}

.topbar__btn {
  padding: 6px 16px;
  border-radius: 8px;
  font-size: 14px;
  color: #eaf4ff;
  background: rgba(56, 189, 248, 0.16);
  border: 1px solid rgba(56, 189, 248, 0.4);
  cursor: pointer;
}

/* ---------- 版面 ---------- */
.body {
  flex: 1;
  min-height: 0;
  display: grid;
  gap: 16px;
  padding-bottom: 8px;
}

/* 总览：左边任务/设备纵列、中间地图、右边告警与地理、底部两块分布图 */
.body--overview {
  grid-template-columns: 640px minmax(0, 1fr) 460px;
  grid-template-rows: minmax(0, 1fr) 300px;
  grid-template-areas:
    'tasks  map    right'
    'left   donut  donut';
}

.body--map {
  grid-template-columns: minmax(0, 1fr) 460px;
}

.body--alerts {
  grid-template-columns: 560px 420px minmax(0, 1fr);
}

.panel {
  display: flex;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
}

.panel--tasks { grid-area: tasks; }
.panel--left { grid-area: left; }
.panel--map { grid-area: map; }
.panel--right { grid-area: right; gap: 14px; }
.panel--donut { min-height: 260px; }
.panel--mapFull { min-height: 0; }
.panel--mapSide { min-height: 0; }

.panel__title {
  display: flex;
  align-items: baseline;
  gap: 12px;
  margin-bottom: 10px;
  font-size: 17px;
  letter-spacing: 1px;
  color: rgba(200, 224, 255, 0.8);
}

.panel__hint {
  font-size: 13px;
  color: rgba(180, 208, 240, 0.5);
}

.panel__empty {
  padding: 24px 0;
  font-size: 15px;
  color: rgba(180, 208, 240, 0.45);
}

.panel__grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(150px, 1fr));
  gap: 12px;
}

.panel__grid--tight {
  margin-top: 12px;
}

.panel__grid--geo {
  grid-template-columns: repeat(3, minmax(0, 1fr));
}

/* ---------- 地图屏的任务清单 ---------- */
.tasklist {
  margin: 0;
  padding: 14px;
  list-style: none;
  border-radius: 12px;
  overflow: hidden;
  background: linear-gradient(160deg, rgba(38, 84, 140, 0.28), rgba(12, 26, 48, 0.5));
  border: 1px solid rgba(90, 150, 220, 0.22);
}

.tasklist__item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 0;
  font-size: 14px;
  border-bottom: 1px solid rgba(90, 150, 220, 0.12);
}

.tasklist__no {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 12.5px;
  color: rgba(125, 211, 252, 0.9);
}

.tasklist__name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.tasklist__meta {
  flex: none;
  font-size: 13px;
  color: rgba(180, 208, 240, 0.55);
}

.tasklist__badge {
  flex: none;
  padding: 1px 8px;
  border-radius: 4px;
  font-size: 12px;
  color: #03121f;
  background: #7dd3fc;
}

.tasklist__badge.is-danger {
  background: #fca5a5;
}

/* ---------- 轮播指示 ---------- */
.dots {
  display: flex;
  justify-content: center;
  align-items: center;
  gap: 10px;
  height: 28px;
  flex: none;
}

.dots__dot {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  background: rgba(120, 190, 255, 0.25);
}

.dots__dot.is-active {
  background: #38bdf8;
}

/* ---------- 登录过期覆盖层 ---------- */
.expired {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  /* 半透明：底下的数据还看得见 —— 屏不至于全黑 */
  background: rgba(3, 10, 22, 0.72);
  z-index: 20;
}

.expired__box {
  padding: 40px 64px;
  border-radius: 16px;
  text-align: center;
  background: rgba(12, 26, 48, 0.95);
  border: 1px solid rgba(248, 113, 113, 0.45);
}

.expired__title {
  font-size: 40px;
  font-weight: 700;
  color: #fca5a5;
}

.expired__desc {
  margin-top: 14px;
  font-size: 18px;
  color: rgba(200, 224, 255, 0.75);
}
</style>
