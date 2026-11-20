<script setup lang="ts">
/**
 * 禁飞区管理（地图接入方案 第三步：静态校验）。
 *
 * <h2>这一步能做什么、不能做什么（页面上一开始就说清）</h2>
 * 能：在创建任务、派发任务时<b>拦下</b>撞上区域的计划，并留下记录。
 * 不能：拦住已经起飞的飞机 —— 那在飞控侧（厂商的地理围栏）。
 * 所以这里画出来的区域，价值在于「事前提醒 + 事后可对账」，
 * 不是「装个开关就没人能飞进去了」。
 *
 * <h2>画区为什么不用高德的 MouseTool</h2>
 * 它画完要点<b>双击</b>结束（触控板、小屏上很别扭），改顶点又是一整套
 * {@code PolygonEditor} API。这里改成「在地图上依次点顶点」：
 * 交互更直白、和老老实实点一下的选点逻辑共用同一套代码，
 * 而且在浏览器里能自动化验证（双击那种手势很难驱动）。
 */
import { computed, onMounted, reactive, ref } from 'vue'
import {
  addZone,
  deleteZone,
  fetchZoneDetail,
  fetchZoneOptions,
  fetchZonePage,
  toggleZone,
  updateZone,
  type NoFlyZoneQuery,
  type NoFlyZoneVO,
  type ZonePoint,
} from '@/api/zone'
import GeoMap from '@/components/GeoMap.vue'
import { distanceMeters, type GeoMarker, type GeoPoint, type GeoZone } from '@/utils/geo'

const loading = ref(false)
const list = ref<NoFlyZoneVO[]>([])
const total = ref(0)

const query = reactive<NoFlyZoneQuery>({
  keyword: '',
  source: '',
  enabled: undefined,
  page: 1,
  pageSize: 10,
})

/** 形状与来源字典（编码 → 中文名）从后端取 */
const shapes = ref<Record<string, string>>({})
const sources = ref<Record<string, string>>({})

/**
 * 地图上画**全部**区域，而不是只画当前这一页。
 *
 * <p>别的列表页（点位、航线）地图只画当页，因为那些是「一堆点」；
 * 而区域是「一块块地」—— 少画一块会让人以为那块地可以飞，
 * 所以这里单独取一次全量（区域数量本来就不多），与分页互不影响。
 */
const allZones = ref<NoFlyZoneVO[]>([])

const totalPages = computed(() => Math.max(1, Math.ceil(total.value / (query.pageSize ?? 10))))
const currentPage = computed(() => query.page ?? 1)

/** 一个区域都没有时的地图视角：北京（点位库页也是这个兜底） */
const DEFAULT_CENTER: GeoPoint = { lng: 116.397, lat: 39.909 }

function toGeoZone(zone: NoFlyZoneVO): GeoZone {
  return zone.shape === 'circle'
    ? {
        shape: 'circle',
        center: zone.center,
        radius: zone.radius,
        forbidden: zone.forbidden,
        label: zone.name,
      }
    : { shape: 'polygon', points: zone.points, forbidden: zone.forbidden, label: zone.name }
}

const mapZones = computed<GeoZone[]>(() => allZones.value.map(toGeoZone))

async function loadAllZones() {
  try {
    const result = await fetchZonePage({ pageSize: 200 })
    allZones.value = result.list
  } catch {
    allZones.value = []
  }
}

async function loadData() {
  loading.value = true
  try {
    const result = await fetchZonePage(query)
    list.value = result.list
    total.value = result.total
  } catch {
    list.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

onMounted(async () => {
  try {
    const options = await fetchZoneOptions()
    shapes.value = options.shapes
    sources.value = options.sources
  } catch {
    // 字典取不到就先不显示选项，不影响列表
  }
  await Promise.all([loadData(), loadAllZones()])
})

function handleSearch() {
  query.page = 1
  loadData()
}

function handleReset() {
  query.keyword = ''
  query.source = ''
  query.enabled = undefined
  query.page = 1
  loadData()
}

function handlePageChange(page: number) {
  if (page < 1 || page > totalPages.value) return
  query.page = page
  loadData()
}

/** 生效期显示的是一句话，不是一串时间戳 */
function effectiveText(zone: NoFlyZoneVO) {
  const from = zone.effectiveFrom
  const to = zone.effectiveTo
  if (!from && !to) return '长期有效'
  if (from && !to) return `${from} 起`
  if (!from && to) return `至 ${to}`
  return `${from} ~ ${to}`
}

// ---------------------------------------------------------------------------
// 新增 / 编辑 + 画区
// ---------------------------------------------------------------------------
const formVisible = ref(false)
const saving = ref(false)
const isEdit = computed(() => Boolean(form.id))

const form = reactive({
  id: undefined as number | undefined,
  name: '',
  source: 'custom',
  shape: 'polygon',
  /** true = 完全禁飞（不填限高）；false = 限高区 */
  forbidAll: true,
  maxAlt: 120,
  effectiveFrom: '',
  effectiveTo: '',
  remark: '',
  enabled: true,
})

/** 正在画的顶点（多边形） */
const draftPoints = ref<ZonePoint[]>([])
/** 正在画的圆心与半径（圆） */
const draftCenter = ref<ZonePoint | undefined>(undefined)
const radius = ref<number | undefined>(undefined)

/** 地图上的草稿形状 —— 虚线描边，和已经生效的区域区分开 */
const draftZones = computed<GeoZone[]>(() => {
  if (form.shape === 'circle') {
    if (!draftCenter.value) return []
    return [
      {
        shape: 'circle',
        center: draftCenter.value,
        radius: radius.value || 0,
        forbidden: form.forbidAll,
        draft: true,
      },
    ]
  }
  if (draftPoints.value.length < 2) return []
  return [{ shape: 'polygon', points: draftPoints.value, forbidden: form.forbidAll, draft: true }]
})

/** 弹窗里的地图：已生效的区域（排除正在编辑的那条）+ 草稿 */
const dialogZones = computed<GeoZone[]>(() => [
  ...allZones.value.filter((z) => z.id !== form.id).map(toGeoZone),
  ...draftZones.value,
])

const draftMarkers = computed<GeoMarker[]>(() => {
  if (form.shape === 'circle') {
    return draftCenter.value
      ? [{ lng: draftCenter.value.lng, lat: draftCenter.value.lat, label: '圆心', tone: 'takeoff' }]
      : []
  }
  return draftPoints.value.map((p, index) => ({
    lng: p.lng,
    lat: p.lat,
    label: String(index + 1),
    tone: 'plan' as const,
  }))
})

/** 地图中心：编辑时落在原来的区域上；新增时用当页已有区域兜底，再不行就用北京 */
const mapCenter = computed<GeoPoint>(() => {
  const first = draftPoints.value[0]
  if (first) return { lng: first.lng, lat: first.lat }
  if (draftCenter.value) return draftCenter.value

  const existing = list.value[0]
  if (existing) {
    if (existing.shape === 'circle' && existing.center) {
      return existing.center
    }
    const vertex = existing.points?.[0]
    if (vertex) return vertex
  }
  return DEFAULT_CENTER
})

const hasDrawn = computed(() =>
  form.shape === 'circle' ? Boolean(draftCenter.value && radius.value) : draftPoints.value.length >= 3,
)

function resetDraft() {
  draftPoints.value = []
  draftCenter.value = undefined
  radius.value = undefined
}

function openAddDialog() {
  form.id = undefined
  form.name = ''
  form.source = 'custom'
  form.shape = 'polygon'
  form.forbidAll = true
  form.maxAlt = 120
  form.effectiveFrom = ''
  form.effectiveTo = ''
  form.remark = ''
  form.enabled = true
  resetDraft()
  formVisible.value = true
}

async function openEditDialog(zone: NoFlyZoneVO) {
  const detail = await fetchZoneDetail(zone.id)
  form.id = detail.id
  form.name = detail.name
  form.source = detail.source
  form.shape = detail.shape
  form.forbidAll = detail.forbidden
  form.maxAlt = detail.maxAlt ?? 120
  form.effectiveFrom = (detail.effectiveFrom || '').replace(' ', 'T').slice(0, 16)
  form.effectiveTo = (detail.effectiveTo || '').replace(' ', 'T').slice(0, 16)
  form.remark = detail.remark ?? ''
  form.enabled = detail.enabled
  resetDraft()
  // 把原来的形状铺成草稿：编辑时多半只是想挪一个顶点，不该逼人重画一遍
  if (detail.shape === 'circle' && detail.center) {
    draftCenter.value = { lng: detail.center.lng, lat: detail.center.lat }
    radius.value = detail.radius
  } else if (detail.points) {
    draftPoints.value = detail.points.map((p) => ({ lng: p.lng, lat: p.lat }))
  }
  formVisible.value = true
}

/** 切换形状时清空草稿 —— 多边形的顶点对圆没有意义，留着只会让人看不懂图上那个圈 */
function onShapeChange() {
  resetDraft()
}

/** 在地图上点一下 */
function handleDrawClick(point: GeoPoint) {
  const fixed = { lng: Number(point.lng.toFixed(6)), lat: Number(point.lat.toFixed(6)) }
  if (form.shape === 'circle') {
    if (!draftCenter.value) {
      draftCenter.value = fixed
      return
    }
    // 第二次点击定半径：把这两下的距离算出来填进输入框，人还能再手改
    const meters = distanceMeters(draftCenter.value, fixed)
    radius.value = Math.max(1, Math.round(meters))
    return
  }
  draftPoints.value.push(fixed)
}

function undoLast() {
  if (form.shape === 'circle') {
    radius.value = undefined
    return
  }
  draftPoints.value.pop()
}

function clearDraft() {
  resetDraft()
}

function toApiTime(value: string): string | undefined {
  return value ? value.replace('T', ' ') + ':00' : undefined
}

async function submitForm() {
  if (!form.name.trim()) {
    ElMessage.warning('请填写区域名称')
    return
  }
  if (form.shape === 'polygon' && draftPoints.value.length < 3) {
    ElMessage.warning('多边形至少要 3 个顶点（在地图上依次点出来）')
    return
  }
  if (form.shape === 'circle' && (!draftCenter.value || !radius.value)) {
    ElMessage.warning('请先点出圆心，再填半径（或点圆边上的一点）')
    return
  }
  if (!form.forbidAll && (!form.maxAlt || form.maxAlt <= 0)) {
    ElMessage.warning('限高区请填写大于 0 的限高值')
    return
  }
  if (form.effectiveFrom && form.effectiveTo && form.effectiveTo < form.effectiveFrom) {
    ElMessage.warning('生效结束不能早于生效开始')
    return
  }

  const payload = {
    id: form.id,
    name: form.name.trim(),
    source: form.source,
    shape: form.shape,
    // 形状决定填哪一组：多边形给顶点，圆给圆心 + 半径
    ...(form.shape === 'polygon'
      ? { points: draftPoints.value }
      : {
          centerLng: draftCenter.value?.lng,
          centerLat: draftCenter.value?.lat,
          radius: radius.value,
        }),
    // 完全禁飞 = 不传这个字段（服务端据此存 NULL）。
    // 注意**不能传 0**：0 是「限高 0 米」，比完全禁飞还严
    maxAlt: form.forbidAll ? undefined : form.maxAlt,
    effectiveFrom: toApiTime(form.effectiveFrom),
    effectiveTo: toApiTime(form.effectiveTo),
    enabled: form.enabled,
    remark: form.remark.trim() || undefined,
  }

  saving.value = true
  try {
    if (isEdit.value) {
      await updateZone(payload)
    } else {
      await addZone(payload)
    }
    ElMessage.success(isEdit.value ? '保存成功' : '新增成功')
    formVisible.value = false
    await Promise.all([loadData(), loadAllZones()])
  } catch {
    // 错误提示由 axios 响应拦截器统一处理
  } finally {
    saving.value = false
  }
}

async function handleToggle(zone: NoFlyZoneVO) {
  try {
    await toggleZone(zone.id, !zone.enabled)
    ElMessage.success(zone.enabled ? '已停用' : '已启用')
    await Promise.all([loadData(), loadAllZones()])
  } catch {
    // 错误提示由 axios 响应拦截器统一处理
  }
}

async function handleDelete(zone: NoFlyZoneVO) {
  try {
    await ElMessageBox.confirm(
      `确定删除「${zone.name}」吗？\n` +
        '已创建任务的流转流水里仍然留着「当时命中了它」的记录，删掉区域不会抹掉那段留痕。',
      '删除禁飞区',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  try {
    await deleteZone(zone.id)
    ElMessage.success('已删除')
    if (list.value.length === 1 && currentPage.value > 1) {
      query.page = currentPage.value - 1
    }
    await Promise.all([loadData(), loadAllZones()])
  } catch {
    // 错误提示由 axios 响应拦截器统一处理
  }
}
</script>

<template>
  <div>
    <!-- ============ 检索栏 ============ -->
    <div class="card filter">
      <label class="search">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round">
          <circle cx="11" cy="11" r="6.2" /><path d="m19.5 19.5-3.6-3.6" />
        </svg>
        <input v-model="query.keyword" type="text" placeholder="搜索区域名称" @keyup.enter="handleSearch" />
      </label>

      <select v-model="query.source" class="select">
        <option value="">全部来源</option>
        <option v-for="(label, value) in sources" :key="value" :value="value">{{ label }}</option>
      </select>

      <label class="check">
        <input
          type="checkbox"
          :checked="query.enabled === true"
          @change="query.enabled = ($event.target as HTMLInputElement).checked ? true : undefined"
        />
        只看启用的
      </label>

      <button class="btn btn--primary" type="button" @click="handleSearch">查询</button>
      <button class="btn btn--ghost" type="button" @click="handleReset">重置</button>
    </div>

    <p class="notice">
      这里画的区域用于<b>创建任务与派发任务时的静态校验</b>：命中会拦下并要求确认（留痕）。
      它<b>拦不住已经起飞的飞机</b> —— 真正的「拦」在飞控侧；事中的检测与告警是下一步。
    </p>

    <!-- ============ 区域地图 ============ -->
    <div class="card mapcard">
      <div class="card__head">
        <div class="card__title">
          区域分布
          <small>红色是完全禁飞、琥珀色是限高区；都画的是<b>全部</b>区域（不受分页影响）</small>
        </div>
      </div>
      <GeoMap
        :zones="mapZones"
        :height="320"
        empty-hint="还没有禁飞区 —— 点右上角「新增区域」，在地图上把范围点出来"
      />
    </div>

    <!-- ============ 列表 ============ -->
    <div class="card">
      <div class="card__head">
        <div class="card__title">
          禁飞区
          <small>共 {{ total }} 个区域</small>
        </div>
        <div class="card__tools">
          <button class="btn btn--primary" type="button" @click="openAddDialog">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round">
              <path d="M12 5v14M5 12h14" />
            </svg>
            新增区域
          </button>
        </div>
      </div>

      <div class="table-wrap">
        <table>
          <thead>
            <tr>
              <th>区域名称</th>
              <th>来源</th>
              <th>形状</th>
              <th>高度规则</th>
              <th>生效期</th>
              <th>状态</th>
              <th>创建人</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="zone in list" :key="zone.id">
              <td class="strong">{{ zone.name }}</td>
              <td>
                <span class="tag" :class="zone.source === 'official' ? 'tag--violet' : 'tag--grey'">
                  {{ zone.sourceLabel }}
                </span>
              </td>
              <td>{{ zone.shapeLabel }}</td>
              <td>
                <span class="tag" :class="zone.forbidden ? 'tag--red' : 'tag--amber'">
                  {{ zone.altRuleLabel }}
                </span>
              </td>
              <td class="time">{{ effectiveText(zone) }}</td>
              <td>
                <span class="tag" :class="zone.enabled ? 'tag--green' : 'tag--grey'">
                  {{ zone.enabled ? '启用' : '停用' }}
                </span>
              </td>
              <td>{{ zone.createByName || '—' }}</td>
              <td>
                <div class="ops">
                  <span class="op" @click="openEditDialog(zone)">编辑</span>
                  <span class="op" @click="handleToggle(zone)">{{ zone.enabled ? '停用' : '启用' }}</span>
                  <span class="op op--danger" @click="handleDelete(zone)">删除</span>
                </div>
              </td>
            </tr>
            <tr v-if="!loading && list.length === 0">
              <td colspan="8" class="empty">还没有禁飞区</td>
            </tr>
          </tbody>
        </table>
      </div>

      <div class="pager">
        <span>共 {{ total }} 条，第 {{ currentPage }} / {{ totalPages }} 页</span>
        <div class="pager__pages">
          <span class="pager__btn" :class="{ 'is-disabled': currentPage <= 1 }" @click="handlePageChange(currentPage - 1)">
            上一页
          </span>
          <span class="pager__btn is-active">{{ currentPage }}</span>
          <span
            class="pager__btn"
            :class="{ 'is-disabled': currentPage >= totalPages }"
            @click="handlePageChange(currentPage + 1)"
          >
            下一页
          </span>
        </div>
      </div>
    </div>

    <!-- ============ 新增 / 编辑 ============ -->
    <el-dialog v-model="formVisible" :title="isEdit ? '编辑禁飞区' : '新增禁飞区'" width="820px" align-center>
      <div class="form form--inline">
        <label class="form__row">
          <span class="form__label">名称</span>
          <input v-model="form.name" class="form__input" placeholder="如：某机场净空区" />
        </label>
        <label class="form__row form__row--narrow">
          <span class="form__label">来源</span>
          <select v-model="form.source" class="form__input form__select">
            <option v-for="(label, value) in sources" :key="value" :value="value">{{ label }}</option>
          </select>
        </label>
      </div>

      <div class="form form--inline">
        <div class="form__row">
          <span class="form__label">形状</span>
          <select v-model="form.shape" class="form__input form__select" @change="onShapeChange">
            <option v-for="(label, value) in shapes" :key="value" :value="value">{{ label }}</option>
          </select>
        </div>
        <div class="form__row">
          <span class="form__label">高度规则</span>
          <select v-model="form.forbidAll" class="form__input form__select">
            <option :value="true">完全禁飞</option>
            <option :value="false">限高</option>
          </select>
          <input
            v-if="!form.forbidAll"
            v-model.number="form.maxAlt"
            class="form__input form__input--sm"
            type="number"
            min="1"
            max="1000"
            placeholder="限高（米）"
          />
        </div>
      </div>

      <div class="form form--inline">
        <label class="form__row">
          <span class="form__label">生效开始</span>
          <input v-model="form.effectiveFrom" class="form__input" type="datetime-local" />
        </label>
        <label class="form__row">
          <span class="form__label">生效结束</span>
          <input v-model="form.effectiveTo" class="form__input" type="datetime-local" />
        </label>
      </div>
      <p class="form__hint">生效期不填表示<b>长期有效</b>；临时管制就填上时间段，到点自动不再拦。</p>

      <label class="form__row form__row--full">
        <span class="form__label">备注</span>
        <input v-model="form.remark" class="form__input" placeholder="如：客户厂区，全年禁飞（选填）" />
      </label>

      <!-- ---------- 画区 ---------- -->
      <div class="draw">
        <div class="draw__head">
          <span class="draw__title">范围</span>
          <span class="draw__hint">
            <template v-if="form.shape === 'polygon'">
              在地图上<b>依次点出顶点</b>（至少 3 个），已点 {{ draftPoints.length }} 个
            </template>
            <template v-else>
              <b>先点圆心</b>，再点圆边上一点即可定出半径；也可以直接填数值
            </template>
          </span>
          <div class="draw__tools">
            <button class="btn btn--ghost" type="button" @click="undoLast">撤销上一个</button>
            <button class="btn btn--ghost" type="button" @click="clearDraft">清空重画</button>
          </div>
        </div>

        <div v-if="form.shape === 'circle'" class="draw__row">
          <span>半径</span>
          <input v-model.number="radius" class="form__input form__input--sm" type="number" min="1" placeholder="米" />
          <span class="draw__unit">米</span>
          <span v-if="draftCenter" class="draw__coord">
            圆心 {{ draftCenter.lng.toFixed(6) }}, {{ draftCenter.lat.toFixed(6) }}
          </span>
          <span v-else class="draw__coord draw__coord--warn">还没有点圆心</span>
        </div>
        <div v-else class="draw__row">
          <span class="draw__unit">
            {{ draftPoints.length === 0 ? '还没有点顶点' : `${draftPoints.length} 个顶点` }}
          </span>
        </div>

        <p v-if="!hasDrawn" class="draw__warn">范围还没画完，保存会被拦下（这是有意的：没有范围就没有意义）</p>

        <!-- ⚠️ 只在弹窗打开时渲染地图：藏在 display:none 里初始化会画不出来（且不报错） -->
        <GeoMap
          v-if="formVisible"
          pickable
          :fit="false"
          :center="mapCenter"
          :zoom="13"
          :zones="dialogZones"
          :markers="draftMarkers"
          :height="300"
          :pick-hint="form.shape === 'polygon' ? '点击地图加点顶点' : '点击地图定圆心 / 定半径'"
          @pick="handleDrawClick"
        />
      </div>

      <template #footer>
        <button class="btn btn--ghost" type="button" @click="formVisible = false">取消</button>
        <button class="btn btn--primary" type="button" :disabled="saving" @click="submitForm">
          {{ saving ? '保存中' : '确定' }}
        </button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.filter {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
  padding: 16px 20px;
  margin-bottom: 18px;
}

.notice {
  margin: -6px 0 18px;
  padding: 10px 14px;
  border-radius: 10px;
  font-size: 12.5px;
  line-height: 1.9;
  color: var(--muted);
  background: rgba(251, 191, 36, 0.08);
  border: 1px solid rgba(251, 191, 36, 0.28);
}

.mapcard {
  padding-bottom: 16px;
  margin-bottom: 18px;
}

.check {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  color: var(--muted);
  cursor: pointer;
}

.select {
  height: 36px;
  padding: 0 30px 0 12px;
  border-radius: 9px;
  font-size: 13px;
  font-family: inherit;
  color: var(--text);
  background-color: var(--input-bg);
  border: 1px solid var(--line-strong);
  outline: none;
  cursor: pointer;
  appearance: none;
  background-image: linear-gradient(45deg, transparent 50%, var(--muted) 50%),
    linear-gradient(135deg, var(--muted) 50%, transparent 50%);
  background-position: calc(100% - 16px) 15px, calc(100% - 11px) 15px;
  background-size: 5px 5px, 5px 5px;
  background-repeat: no-repeat;
}

.select option {
  color: var(--text);
  background-color: #0b1830;
}

.time {
  font-size: 12.5px;
  color: var(--text-2);
}

.empty {
  padding: 40px 20px;
  text-align: center;
  color: var(--muted);
}

.pager__btn.is-disabled {
  opacity: 0.4;
  cursor: not-allowed;
}

.form {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.form--inline {
  flex-direction: row;
  flex-wrap: wrap;
  margin-bottom: 12px;
}

.form__row {
  display: flex;
  align-items: center;
  gap: 12px;
  flex: 1;
  min-width: 240px;
}

.form__row--narrow {
  flex: 0 0 220px;
  min-width: 0;
}

.form__row--full {
  margin-bottom: 12px;
}

.form__label {
  width: 66px;
  flex: none;
  font-size: 13px;
  color: var(--muted);
}

.form__input {
  flex: 1;
  width: 100%;
  height: 36px;
  padding: 0 12px;
  border-radius: 9px;
  font-size: 13.5px;
  font-family: inherit;
  color: var(--text);
  background: var(--input-bg);
  border: 1px solid var(--line-strong);
  outline: none;
  transition: border-color 0.25s, box-shadow 0.25s;
}

.form__input:focus {
  border-color: var(--accent-hover);
  box-shadow: 0 0 0 3px rgba(34, 211, 238, 0.12);
}

.form__input--sm {
  flex: none;
  width: 120px;
}

.form__input[type='datetime-local'] {
  color-scheme: dark;
}

.form__select {
  appearance: none;
  cursor: pointer;
  background-image: linear-gradient(45deg, transparent 50%, var(--muted) 50%),
    linear-gradient(135deg, var(--muted) 50%, transparent 50%);
  background-position: calc(100% - 16px) 15px, calc(100% - 11px) 15px;
  background-size: 5px 5px, 5px 5px;
  background-repeat: no-repeat;
}

.form__select option {
  color: var(--text);
  background-color: #0b1830;
}

.form__hint {
  margin: 0 0 14px 78px;
  font-size: 12px;
  color: var(--muted);
}

/* ---------- 画区 ---------- */
.draw__head {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 8px;
}

.draw__title {
  font-size: 13px;
  color: var(--muted);
}

.draw__hint {
  flex: 1;
  font-size: 12.5px;
  line-height: 1.7;
  color: var(--text-2);
}

.draw__tools {
  display: flex;
  gap: 8px;
}

.draw__row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
  font-size: 13px;
  color: var(--muted);
}

.draw__unit {
  font-size: 12.5px;
  color: var(--muted);
}

.draw__coord {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 12px;
  color: var(--accent);
}

.draw__coord--warn {
  color: #fbbf24;
}

.draw__warn {
  margin: 0 0 8px;
  font-size: 12px;
  color: #fbbf24;
}
</style>
