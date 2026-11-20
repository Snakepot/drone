<template>
  <view class="map-page">
    <!-- ============ 头部：这条任务是什么 ============ -->
    <view class="pilot-card head">
      <view class="head__row">
        <text class="pilot-code">{{ info?.taskNo || '' }}</text>
        <text class="head__name">{{ info?.taskName || '' }}</text>
        <text class="tag tag--cyan">{{ statusLabel }}</text>
      </view>
      <text class="pilot-muted head__meta">
        {{ info?.droneCode || '未指派设备' }} ·
        {{ info?.plannedRoute?.name || '没有计划航线' }} ·
        打卡 {{ info?.checkedPointCount || 0 }}/{{ info?.totalPointCount || 0 }} 个点
      </text>
      <text v-if="pendingCount > 0" class="head__offline">
        有 {{ pendingCount }} 条打卡还在本地（没信号），联网后会自动补传
      </text>
    </view>

    <!--
      ⚠️ map 是**原生组件**：层级最高，普通 view 盖不上去。
      要在地图上放东西只能用 cover-view（这里索性什么都不盖，最省心）。
      另外底图换不了、H5 预览需要一个地图 key（小程序里开箱即用）。
    -->
    <map
      class="map"
      :longitude="center.lng"
      :latitude="center.lat"
      :scale="14"
      :markers="markers"
      :polyline="polylines"
      show-location
    />

    <view class="tips">
      <text class="pilot-muted">
        蓝点是计划航点、橙色是起降点、绿点是实际飞过的轨迹；<b>你自己</b>的位置由地图自己显示（小蓝点）。
      </text>
    </view>

    <!-- ============ 一键导航（最高频的实用功能）============ -->
    <view v-if="info?.takeoffLng != null" class="pilot-card">
      <view class="row">
        <view class="row__main">
          <text class="row__title">起降点：{{ info?.takeoffName || '未命名' }}</text>
          <text class="pilot-muted">到了附近还是找不到，就用系统地图导航过去</text>
        </view>
        <button class="pilot-btn pilot-btn--ghost" @click="navigateToTakeoff">导航</button>
      </view>
    </view>

    <!-- ============ 航点打卡 ============ -->
    <view class="pilot-card">
      <text class="card__title">到点打卡</text>
      <text class="pilot-muted card__hint">
        到点位后点一下就行：记下你的位置和时刻。它和服务器的设备数据是两回事，
        复核时以实际轨迹为准，所以<b>打偏了也没关系，但要如实</b>。
      </text>

      <view v-if="(info?.plannedRoute?.points || []).length === 0" class="empty">
        这条任务没有计划航线，不能打卡（打卡要对着一个目标点位）
      </view>

      <view v-for="point in info?.plannedRoute?.points || []" :key="point.seq" class="row row--point">
        <view class="row__main">
          <text class="row__title">第 {{ point.seq }} 个航点{{ point.pointName ? ' · ' + point.pointName : '' }}</text>
          <text class="pilot-muted">
            {{ point.actionLabel || '飞过' }}<template v-if="point.hoverSeconds"> {{ point.hoverSeconds }} 秒</template>
            <template v-if="checkedMap[point.seq]"> · 已打卡（离目标 {{ checkedMap[point.seq] }} 米）</template>
          </text>
        </view>
        <button
          class="pilot-btn pilot-btn--primary"
          :disabled="busySeq != null"
          @click="checkin(point)"
        >
          {{ checkedMap[point.seq] ? '再打一次' : '打卡' }}
        </button>
      </view>
    </view>

    <!-- ============ 打卡记录 ============ -->
    <view class="pilot-card">
      <text class="card__title">打卡记录</text>
      <view v-if="(info?.checkins || []).length === 0" class="empty">还没有打卡记录</view>
      <view v-for="(item, index) in info?.checkins || []" :key="item.id ?? index" class="row">
        <view class="row__main">
          <text class="row__title">
            第 {{ item.seq }} 个航点 · 离目标 {{ item.deviationM }} 米
          </text>
          <text class="pilot-muted">
            {{ item.observedAt }}
            <template v-if="item.accuracyM"> · 定位精度 ±{{ Math.round(Number(item.accuracyM)) }} 米</template>
            <template v-if="item.submittedLate"> · 补传</template>
            <template v-if="item.remark"> · {{ item.remark }}</template>
          </text>
        </view>
        <text class="tag" :class="item.farAway ? 'tag--amber' : 'tag--green'">
          {{ item.farAway ? '偏远' : '正常' }}
        </text>
      </view>
    </view>
  </view>
</template>

<script setup lang="ts">
/**
 * 飞手端：一条任务的地图与打卡。
 *
 * <h2>只看不编辑</h2>
 * 飞手在这里能看到的：计划航线、起降点、自己飞过的轨迹、自己的位置、打卡记录。
 * 画区、拖航点这类编辑类交互一律在管理端 —— 小程序上做不了精细编辑，
 * 而且「谁能改航线」是个权限问题（见设计文档 7.1 与 2.2 的拍板）。
 *
 * <h2>打卡这件事的定位</h2>
 * 它记的是「飞手说自己在那个位置」，**不能替代设备数据**。
 * 所以界面上它和轨迹是分开的两块，偏远了会标出来 ——
 * 别把「人点了一下」和「设备报上来的坐标」混成一种东西。
 */
import { computed, ref } from 'vue'
import { onLoad, onShow } from '@dcloudio/uni-app'
import {
  fetchMyTaskMap,
  submitCheckin,
  type RoutePointVO,
  type TaskCheckinParam,
  type TaskMapVO,
} from '@/api/task'
import {
  enqueueCheckin,
  flushCheckins,
  isNetworkFailure,
  pendingCheckins,
} from '@/utils/checkin-queue'

const taskId = ref<number>(0)
const info = ref<TaskMapVO | null>(null)
const loading = ref(false)
const busySeq = ref<number | null>(null)
const pendingCount = ref(0)

function nowApiTime(): string {
  const now = new Date()
  const pad = (value: number) => String(value).padStart(2, '0')
  return (
    `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())} ` +
    `${pad(now.getHours())}:${pad(now.getMinutes())}:${pad(now.getSeconds())}`
  )
}

const statusLabel = computed(() => {
  const map: Record<string, string> = {
    pending: '待派发',
    dispatched: '已派发',
    executing: '执行中',
    finished: '已完成',
    failed: '已失败',
    cancelled: '已取消',
  }
  return map[info.value?.status || ''] || info.value?.status || ''
})

/** 地图中心：优先自己的位置 → 起降点 → 第一个航点 → 兜底北京 */
const center = ref({ lng: 116.397, lat: 39.908 })

/** 已经打过卡的航点：序号 → 偏差（列表里直接显示，不用去记录里找） */
const checkedMap = computed<Record<number, number>>(() => {
  const map: Record<number, number> = {}
  for (const item of info.value?.checkins || []) {
    map[item.seq] = item.deviationM
  }
  return map
})

/** 计划航线：蓝色实线 */
const polylines = computed(() => {
  const list: Array<Record<string, unknown>> = []
  const planned = info.value?.plannedRoute?.points || []
  if (planned.length >= 2) {
    list.push({
      points: planned.map((point) => ({ longitude: point.lng, latitude: point.lat })),
      color: '#38bdf8CC',
      width: 5,
      arrowLine: true,
    })
  }
  const track = info.value?.track?.points || []
  if (track.length >= 2) {
    // 实际轨迹用绿色：和计划航线一眼分开（计划是「打算」、轨迹是「实际」）
    list.push({
      points: track.map((point) => ({ longitude: point.lng, latitude: point.lat })),
      color: '#22c55eCC',
      width: 4,
    })
  }
  return list
})

/** 计划航点 + 起降点 */
const markers = computed(() => {
  const list: Array<Record<string, unknown>> = []
  for (const point of info.value?.plannedRoute?.points || []) {
    list.push({
      id: point.seq,
      longitude: point.lng,
      latitude: point.lat,
      width: 22,
      height: 22,
      label: { content: String(point.seq), fontSize: 11, color: '#03121f', bgColor: '#38bdf8', borderRadius: 4, padding: 3 },
    })
  }
  if (info.value?.takeoffLng != null && info.value?.takeoffLat != null) {
    list.push({
      id: 9999,
      longitude: info.value.takeoffLng,
      latitude: info.value.takeoffLat,
      width: 22,
      height: 22,
      label: { content: '起降', fontSize: 10, color: '#03121f', bgColor: '#a78bfa', borderRadius: 4, padding: 3 },
    })
  }
  return list
})

async function load() {
  if (!taskId.value) return
  loading.value = true
  try {
    info.value = await fetchMyTaskMap(taskId.value)
    // 地图中心：有计划的第一个点就用它（飞手大多时候关心「我离第一个点多远」）
    const first = info.value.plannedRoute?.points?.[0]
    if (first) {
      center.value = { lng: first.lng, lat: first.lat }
    } else if (info.value.takeoffLng != null && info.value.takeoffLat != null) {
      center.value = { lng: info.value.takeoffLng, lat: info.value.takeoffLat }
    }
  } catch {
    // 错误提示由 request 统一弹过
  } finally {
    loading.value = false
  }
  pendingCount.value = pendingCheckins(taskId.value).length
}

/**
 * 把定位失败翻译成「该干什么」。
 *
 * @param error uni.getLocation 的失败对象（里面有 errMsg）
 */
function describeLocationFailure(error: { errMsg?: string } | undefined): string {
  const message = (error && error.errMsg) || ''
  if (typeof uni.getLocation !== 'function') {
    return '当前运行环境没有定位能力（H5 预览要配地图 key；真机/小程序上正常）'
  }
  if (/auth deny|authorize:fail|deny/i.test(message)) {
    return `定位权限被拒绝了 —— ${message}`
  }
  if (/requiredPrivateInfos/i.test(message)) {
    return '小程序没声明定位接口权限，请联系管理员（manifest.json 的 requiredPrivateInfos）'
  }
  if (/scope\.userLocation/i.test(message)) {
    return '小程序没声明定位用途说明，请联系管理员（manifest.json 的 permission.scope.userLocation）'
  }
  if (/system permission denied/i.test(message)) {
    return '手机的定位总开关没开，请到系统设置里打开定位'
  }
  return `拿不到定位：${message || '未知原因'}`
}

/**
 * 取当前位置。
 *
 * <h2>两个平台给的不是同一套坐标，所以返回值里带上「这是哪一套」</h2>
 * <ul>
 *   <li><b>小程序</b>：`uni.getLocation({type:'gcj02'})` → GCJ-02（地图那一套，能直接画）</li>
 *   <li><b>H5</b>：uni 的 H5 实现**需要配地图 key 才有 getLocation**，所以这里退回
 *       浏览器原生的 `navigator.geolocation` —— 它给的是 **WGS84**（GPS 原始值）。
 *       服务端两个都收（见 TaskCheckinParam 的 coordinateSystem），所以不用在客户端硬转</li>
 * </ul>
 * H5 这条分支只在开发预览时用得上（真机是小程序），但它省掉了「想在浏览器里
 * 点一遍打卡流程却发现按钮永远报错」这种白费劲。
 */
function getLocation(): Promise<{ lng: number; lat: number; accuracy?: number; coordinateSystem: 'gcj02' | 'wgs84' }> {
  // #ifdef H5
  if (typeof navigator !== 'undefined' && navigator.geolocation) {
    return new Promise((resolve, reject) => {
      navigator.geolocation.getCurrentPosition(
        (position) =>
          resolve({
            lng: Number(position.coords.longitude),
            lat: Number(position.coords.latitude),
            accuracy: position.coords.accuracy ? Math.round(position.coords.accuracy) : undefined,
            coordinateSystem: 'wgs84',
          }),
        () => reject(new Error('浏览器不给定位权限（地址栏左侧可以改）')),
        { enableHighAccuracy: true, timeout: 8000 },
      )
    })
  }
  // #endif

  return new Promise((resolve, reject) => {
    uni.getLocation({
      type: 'gcj02',
      success: (res) =>
        resolve({
          lng: Number(res.longitude),
          lat: Number(res.latitude),
          // H5 上拿不到 accuracy，小程序上有；缺了就不传，不编一个
          accuracy: typeof res.accuracy === 'number' ? Math.round(res.accuracy) : undefined,
          coordinateSystem: 'gcj02',
        }),
      /**
       * 失败要给**能照着做**的说法。
       *
       * <p>小程序的失败原因至少有四种，界面上的处理完全不同：
       * <ul>
       *   <li>用户点了「拒绝」→ 得引导他去设置里打开（{@code uni.openSetting}）</li>
       *   <li>没在 manifest 里声明 {@code permission.scope.userLocation} →
       *       <b>连授权弹窗都不会出现</b>，报的是「需要 scope.userLocation」</li>
       *   <li>没在 {@code requiredPrivateInfos} 里声明 → 报「need to be declared...」</li>
       *   <li>手机系统的定位总开关没开 → 报 system permission denied</li>
       * </ul>
       * 一律说「请允许定位权限」的话，前三种会让人白查半天。
       */
      fail: (error) => reject(new Error(describeLocationFailure(error))),
    })
  })
}

function modal(content: string, editable = false, placeholder = ''): Promise<string | false> {
  return new Promise((resolve) => {
    uni.showModal({
      title: editable ? '记一句为什么' : '确认一下',
      content,
      editable,
      placeholderText: placeholder,
      success: (res) => resolve(res.confirm ? (res.content || '') : false),
      fail: () => resolve(false),
    })
  })
}

async function checkin(point: RoutePointVO) {
  if (busySeq.value != null) return
  busySeq.value = point.seq

  // 先在这里声明：网络失败时要把它塞进本地队列，而那时已经跳出了 try
  let param: TaskCheckinParam | undefined

  try {
    const location = await getLocation()

    param = {
      taskId: taskId.value,
      seq: point.seq,
      lng: location.lng,
      lat: location.lat,
      observedAt: nowApiTime(),
      accuracyM: location.accuracy,
      coordinateSystem: location.coordinateSystem,
      remark: undefined,
    }

    // 先**试算**：偏差由服务端算，它说远才问一句。
    // 不在客户端自己算 —— 浏览器给的是 WGS84、航线快照是 GCJ-02，
    // 自己算会凭空多出 500 米，于是每次都弹「确定到了吗」（这一版真踩到了）。
    const preview = await submitCheckin({ ...param, dryRun: true })
    if (preview.farAway) {
      // 偏得远才问：每次都弹确认会让人闭着眼睛点「确定」
      const confirmed = await modal(`你离第 ${point.seq} 个航点约 ${preview.deviationM} 米，确定算到了吗？`)
      if (confirmed === false) return
      const note = await modal('到了但是偏了这么远，写一句原因（可留空）', true, '如：点位被树挡住，在边上拍的')
      if (typeof note === 'string' && note.trim()) {
        param.remark = note.trim()
      }
    }

    const result = await submitCheckin(param)
    uni.showToast({
      title: result.duplicated ? '这条已经记过了' : `打卡成功，离目标 ${result.deviationM} 米`,
      icon: 'none',
      duration: 2500,
    })
    await load()
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error)
    if (message.includes('定位') || message.includes('权限')) {
      // 定位失败**不排队**：连自己在哪都不知道，排队记下来的是一条没有意义的记录。
      // 权限问题还要给一个「去设置」的入口 —— 只说「请允许权限」，人不知道该去哪儿点
      if (message.includes('拒绝') || message.includes('权限')) {
        uni.showModal({
          title: '需要定位权限',
          content: `${message}\n\n打卡要记下你当时的位置，没有定位就记不了。`,
          confirmText: '去设置',
          success: (res) => {
            if (res.confirm) {
              // openSetting 只对「用户拒绝过 scope.userLocation」有效；
              // 另外几种（没声明、系统关定位）在这里点一下也不会有变化，
              // 但那两种情况上面的提示已经说清该找谁了
              uni.openSetting({})
            }
          },
        })
      } else {
        uni.showToast({ title: message, icon: 'none', duration: 3000 })
      }
    } else if (isNetworkFailure(error) && param) {
      // 网络问题：这条**必须留下**。野外没信号是常态，直接丢了就再也补不回来 ——
      // 服务端按「任务 + 航点 + 时刻」去重，所以补传重发是安全的
      enqueueCheckin(param)
      uni.showToast({ title: '没网，已存到本地；有信号会自动补传', icon: 'none', duration: 3000 })
    }
  } finally {
    busySeq.value = null
  }
}

function navigateToTakeoff() {
  if (info.value?.takeoffLng == null || info.value?.takeoffLat == null) return
  // openLocation 收的正是 GCJ-02，和后端给的一致 —— 不用转换
  uni.openLocation({
    longitude: info.value.takeoffLng,
    latitude: info.value.takeoffLat,
    name: info.value.takeoffName || '起降点',
    fail: () => uni.showToast({ title: '打不开系统地图', icon: 'none' }),
  })
}

onLoad((options) => {
  taskId.value = Number(options?.id || 0)
  void load()
})

onShow(() => {
  // 回到这一页就把本地攒着的打卡补传一次：飞手从没信号的地方走回来，
  // 第一件该发生的事就是「把刚才记下的传上去」
  void flushCheckins().then((result) => {
    if (result.sent > 0) {
      uni.showToast({ title: `补传了 ${result.sent} 条打卡`, icon: 'none' })
      void load()
    }
  })
})
</script>

<style scoped>
.map-page {
  padding: 16rpx 20rpx 40rpx;
}

.head {
  margin-bottom: 16rpx;
}

.head__row {
  display: flex;
  align-items: center;
  gap: 12rpx;
}

.head__name {
  flex: 1;
  font-size: 30rpx;
  color: #e6f1ff;
}

.head__meta {
  display: block;
  margin-top: 8rpx;
  font-size: 24rpx;
}

.head__offline {
  display: block;
  margin-top: 8rpx;
  font-size: 24rpx;
  color: #fcd34d;
}

.map {
  width: 100%;
  height: 520rpx;
  border-radius: 16rpx;
  /* 原生组件也要给确定高度，否则不显示（和小程序地图的脾气一致） */
  overflow: hidden;
}

.tips {
  padding: 12rpx 4rpx 20rpx;
  font-size: 23rpx;
  line-height: 1.7;
}

.card__title {
  display: block;
  font-size: 28rpx;
  color: #e6f1ff;
  margin-bottom: 8rpx;
}

.card__hint {
  display: block;
  font-size: 23rpx;
  line-height: 1.7;
  margin-bottom: 12rpx;
}

.row {
  display: flex;
  align-items: center;
  gap: 16rpx;
  padding: 16rpx 0;
  border-top: 1rpx solid rgba(120, 190, 255, 0.12);
}

.row--point:first-of-type {
  border-top: none;
}

.row__main {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 6rpx;
}

.row__title {
  font-size: 27rpx;
  color: #e6f1ff;
}

.row__main .pilot-muted {
  font-size: 23rpx;
}

.empty {
  padding: 24rpx 0;
  font-size: 24rpx;
  color: #7d93b2;
}
</style>
