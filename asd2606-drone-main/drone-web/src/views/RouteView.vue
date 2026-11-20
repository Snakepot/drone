<script setup lang="ts">
/**
 * 航线管理。
 *
 * <h2>航线 = 有序的点 + 每个点上的动作</h2>
 * 顺序由数组决定（拖拽/上下移都只是改数组顺序），<b>服务端按数组顺序编号 1、2、3……</b>
 * 所以这里根本不出现「顺序」这个输入框 —— 顺序就是列表的排列。
 *
 * <h2>航点从哪来</h2>
 * 两种都要（设计文档 6.5）：
 * <ul>
 *   <li><b>从点位库挑</b>：可复用、带名字，是常见做法</li>
 *   <li><b>在地图上直接点</b>：临时加一个不打算入库的点（只存在于这条航线上）</li>
 * </ul>
 *
 * <h2>坐标仍然是「进来就是 GCJ-02、出去也是 GCJ-02」</h2>
 * 地图上点出来的位置原样提交，后端负责转成 WGS84 入库。
 */
import { computed, onMounted, reactive, ref } from 'vue'
import {
  addRoute,
  deleteRoute,
  fetchPointPage,
  fetchRouteActions,
  fetchRouteDetail,
  fetchRoutePage,
  updateRoute,
  type RoutePointVO,
  type RouteQuery,
  type RouteSaveParam,
  type RouteVO,
  type SurveyPointVO,
} from '@/api/route'
import { useAuthStore } from '@/stores/auth'
import GeoMap from '@/components/GeoMap.vue'
import { toLine, type GeoLine, type GeoMarker, type GeoPoint } from '@/utils/geo'

const auth = useAuthStore()

const loading = ref(false)
const list = ref<RouteVO[]>([])
const total = ref(0)

const query = reactive<RouteQuery>({ keyword: '', page: 1, pageSize: 10 })

const totalPages = computed(() => Math.max(1, Math.ceil(total.value / (query.pageSize ?? 10))))
const currentPage = computed(() => query.page ?? 1)

async function loadData() {
  loading.value = true
  try {
    const result = await fetchRoutePage(query)
    list.value = result.list
    total.value = result.total
  } catch {
    // 错误提示由 axios 响应拦截器统一处理
    list.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

function handleSearch() {
  query.page = 1
  loadData()
}

function handleReset() {
  query.keyword = ''
  query.page = 1
  loadData()
}

function handlePageChange(page: number) {
  if (page < 1 || page > totalPages.value) return
  query.page = page
  loadData()
}

/** 航程按量级换单位 —— 几十米的短航线说成「0.1 公里」很别扭 */
function formatDistance(meters?: number) {
  if (meters == null) return '—'
  return meters < 1000 ? `${meters} m` : `${(meters / 1000).toFixed(2)} km`
}

// ---------------------------------------------------------------------------
// 航线编辑
// ---------------------------------------------------------------------------

/**
 * 编辑区里的一个航点。
 *
 * <p>{@code label} 只是给人看的（来自点位库的名字 / 或「地图选点」），
 * <b>提交时不会发给后端</b> —— 后端不需要知道它，库里的点是靠 lng/lat 描述的。
 */
interface WaypointRow {
  lng: number
  lat: number
  alt?: number
  action: string
  hoverSeconds?: number
  pointId?: number
  label: string
}

const dialogVisible = ref(false)
const saving = ref(false)
const isEdit = computed(() => Boolean(form.id))

const form = reactive<{ id?: number; name: string; description: string }>({
  id: undefined,
  name: '',
  description: '',
})
const waypoints = ref<WaypointRow[]>([])

/** 动作字典（编码 → 中文名），从后端取 */
const actions = ref<Record<string, string>>({})

/** 候选点位：一次多取一些（选点本来就希望「都在眼前」），带关键字搜索 */
const pointKeyword = ref('')
const candidatePoints = ref<SurveyPointVO[]>([])
const candidateLoading = ref(false)

const markers = computed<GeoMarker[]>(() =>
  waypoints.value.map((w, index) => ({
    lng: w.lng,
    lat: w.lat,
    label: String(index + 1),
    tone: 'plan' as const,
  })),
)

const lines = computed<GeoLine[]>(() => [
  toLine(waypoints.value, { color: '#f59e0b', dashed: true, weight: 4 }),
])

/** 编辑时的地图中心：第一个航点；没有就用当页第一条航线？不 —— 用北京兜底 */
const center = computed<GeoPoint>(() => {
  const first = waypoints.value[0]
  return first ? { lng: first.lng, lat: first.lat } : { lng: 116.397, lat: 39.909 }
})

/** 航点数量的小结（真正的航程以服务端算的为准，列表里已经给了） */
const waypointSummary = computed(() =>
  waypoints.value.length === 0 ? '还没有航点' : `${waypoints.value.length} 个航点`,
)

async function loadCandidates() {
  candidateLoading.value = true
  try {
    const result = await fetchPointPage({ keyword: pointKeyword.value, pageSize: 200 })
    candidatePoints.value = result.list
  } catch {
    candidatePoints.value = []
  } finally {
    candidateLoading.value = false
  }
}

function openAddDialog() {
  form.id = undefined
  form.name = ''
  form.description = ''
  waypoints.value = []
  pointKeyword.value = ''
  dialogVisible.value = true
  void loadCandidates()
}

async function openEditDialog(route: RouteVO) {
  // 列表接口不带航点，所以这里要单独取一次详情
  const detail = await fetchRouteDetail(route.id)
  form.id = detail.id
  form.name = detail.name
  form.description = detail.description ?? ''
  waypoints.value = (detail.points || []).map((p: RoutePointVO) => ({
    lng: p.lng,
    lat: p.lat,
    alt: p.alt,
    action: p.action,
    hoverSeconds: p.hoverSeconds,
    pointId: p.pointId,
    label: p.pointName || '地图选点',
  }))
  pointKeyword.value = ''
  dialogVisible.value = true
  void loadCandidates()
}

function addFromLibrary(point: SurveyPointVO) {
  // 点位库里的建议高度顺手带过来 —— 挑点的人多半就是想要这个高度
  waypoints.value.push({
    lng: point.lng,
    lat: point.lat,
    alt: point.alt,
    action: 'none',
    pointId: point.id,
    label: point.name,
  })
}

/** 在地图上直接加一个点（不进点位库，只属于这条航线） */
function addFromMap(point: GeoPoint) {
  waypoints.value.push({
    lng: Number(point.lng.toFixed(6)),
    lat: Number(point.lat.toFixed(6)),
    action: 'none',
    label: '地图选点',
  })
}

function moveUp(index: number) {
  if (index <= 0) return
  const rows = waypoints.value
  const prev = rows[index - 1]
  const current = rows[index]
  if (!prev || !current) return
  rows[index - 1] = current
  rows[index] = prev
}

function moveDown(index: number) {
  const rows = waypoints.value
  if (index >= rows.length - 1) return
  const next = rows[index + 1]
  const current = rows[index]
  if (!next || !current) return
  rows[index + 1] = current
  rows[index] = next
}

function removeWaypoint(index: number) {
  waypoints.value.splice(index, 1)
}

/** 动作从「悬停」改成别的时，把秒数清掉 —— 留着会让界面显示一条不存在的悬停要求 */
function onActionChange(row: WaypointRow) {
  if (row.action !== 'hover') {
    row.hoverSeconds = undefined
  } else if (!row.hoverSeconds) {
    row.hoverSeconds = 15
  }
}

async function submitForm() {
  if (!form.name.trim()) {
    ElMessage.warning('请填写航线名称')
    return
  }
  if (waypoints.value.length < 2) {
    ElMessage.warning('一条航线至少要 2 个航点（一个点连不成线）')
    return
  }
  const hoverMissing = waypoints.value.findIndex((w) => w.action === 'hover' && !w.hoverSeconds)
  if (hoverMissing >= 0) {
    ElMessage.warning(`第 ${hoverMissing + 1} 个航点选了悬停，请填写悬停秒数`)
    return
  }

  const payload: RouteSaveParam = {
    id: form.id,
    name: form.name.trim(),
    description: form.description.trim() || undefined,
    points: waypoints.value.map((w) => ({
      lng: w.lng,
      lat: w.lat,
      alt: w.alt,
      action: w.action,
      hoverSeconds: w.hoverSeconds,
      pointId: w.pointId,
    })),
  }

  saving.value = true
  try {
    if (isEdit.value) {
      await updateRoute(payload)
    } else {
      await addRoute(payload)
    }
    ElMessage.success(isEdit.value ? '保存成功' : '新增成功')
    dialogVisible.value = false
    await loadData()
  } catch {
    // 错误提示由 axios 响应拦截器统一处理
  } finally {
    saving.value = false
  }
}

async function handleDelete(route: RouteVO) {
  try {
    await ElMessageBox.confirm(
      `确定删除航线「${route.name}」吗？\n` +
        '已经用过它的任务不会受影响（创建任务时复制了一份当时的航线快照）。',
      '删除航线',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  try {
    await deleteRoute(route.id)
    ElMessage.success('已删除')
    if (list.value.length === 1 && currentPage.value > 1) {
      query.page = currentPage.value - 1
    }
    await loadData()
  } catch {
    // 错误提示由 axios 响应拦截器统一处理
  }
}

onMounted(async () => {
  try {
    actions.value = await fetchRouteActions()
  } catch {
    // 字典取不到时下拉会是空的，但不影响列表与地图
  }
  await loadData()
})</script>

<template>
  <div>
    <!-- ============ 检索栏 ============ -->
    <div class="card filter">
      <label class="search">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round">
          <circle cx="11" cy="11" r="6.2" /><path d="m19.5 19.5-3.6-3.6" />
        </svg>
        <input v-model="query.keyword" type="text" placeholder="搜索航线名称" @keyup.enter="handleSearch" />
      </label>
      <button class="btn btn--primary" type="button" @click="handleSearch">查询</button>
      <button class="btn btn--ghost" type="button" @click="handleReset">重置</button>
    </div>

    <!-- ============ 列表 ============ -->
    <div class="card">
      <div class="card__head">
        <div class="card__title">
          航线
          <small>共 {{ total }} 条。创建任务时可以直接引用一条，任务会存下当时的快照</small>
        </div>
        <div class="card__tools">
          <button v-if="auth.hasPermission('route:edit')" class="btn btn--primary" type="button" @click="openAddDialog">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round">
              <path d="M12 5v14M5 12h14" />
            </svg>
            新增航线
          </button>
        </div>
      </div>

      <div class="table-wrap">
        <table>
          <thead>
            <tr>
              <th>航线名称</th>
              <th>航点数</th>
              <th>总航程</th>
              <th>说明</th>
              <th>创建人</th>
              <th>创建时间</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="route in list" :key="route.id">
              <td class="strong">{{ route.name }}</td>
              <td>{{ route.pointCount }}</td>
              <td>{{ formatDistance(route.distanceM) }}</td>
              <td class="remark">{{ route.description || '—' }}</td>
              <td>{{ route.createByName || '—' }}</td>
              <td class="time">{{ route.createTime || '—' }}</td>
              <td>
                <div class="ops">
                  <span v-if="auth.hasPermission('route:edit')" class="op" @click="openEditDialog(route)">编辑</span>
                  <span v-if="auth.hasPermission('route:edit')" class="op op--danger" @click="handleDelete(route)">
                    删除
                  </span>
                </div>
              </td>
            </tr>
            <tr v-if="!loading && list.length === 0">
              <td colspan="7" class="empty">还没有航线</td>
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

    <!-- ============ 新增 / 编辑航线 ============ -->
    <el-dialog v-model="dialogVisible" :title="isEdit ? '编辑航线' : '新增航线'" width="960px" align-center>
      <div class="form form--inline">
        <label class="form__row">
          <span class="form__label">名称</span>
          <input v-model="form.name" class="form__input" placeholder="如：东线巡检" />
        </label>
        <label class="form__row">
          <span class="form__label">说明</span>
          <input v-model="form.description" class="form__input" placeholder="可不填" />
        </label>
      </div>

      <div class="editor">
        <!-- 左：候选点位 -->
        <div class="editor__col">
          <div class="editor__head">
            <span>点位库</span>
            <small>点「加入」按顺序排到航线末尾</small>
          </div>
          <label class="search search--sm">
            <input
              v-model="pointKeyword"
              type="text"
              placeholder="搜索点位名称 / 编号"
              @keyup.enter="loadCandidates"
            />
          </label>
          <div class="editor__list">
            <div v-for="point in candidatePoints" :key="point.id" class="cand">
              <div class="cand__info">
                <span class="cand__name">{{ point.name }}</span>
                <span class="cand__meta">{{ point.categoryLabel }} · {{ point.lng.toFixed(5) }}, {{ point.lat.toFixed(5) }}</span>
              </div>
              <span class="op" @click="addFromLibrary(point)">加入</span>
            </div>
            <p v-if="!candidateLoading && candidatePoints.length === 0" class="editor__empty">
              没有点位 —— 先在「点位库」里建几个，或者直接在地图上点
            </p>
          </div>
        </div>

        <!-- 右：当前航点 -->
        <div class="editor__col">
          <div class="editor__head">
            <span>航点顺序</span>
            <small>{{ waypointSummary }} · 顺序即飞行顺序</small>
          </div>
          <div class="editor__list">
            <div v-for="(row, index) in waypoints" :key="index" class="wp">
              <div class="wp__row">
                <span class="wp__seq">{{ index + 1 }}</span>
                <span class="wp__name">{{ row.label }}</span>
                <span class="wp__coord">{{ row.lng.toFixed(5) }}, {{ row.lat.toFixed(5) }}</span>
                <span class="op" :class="{ 'is-disabled': index === 0 }" @click="moveUp(index)">上移</span>
                <span
                  class="op"
                  :class="{ 'is-disabled': index === waypoints.length - 1 }"
                  @click="moveDown(index)"
                >
                  下移
                </span>
                <span class="op op--danger" @click="removeWaypoint(index)">移除</span>
              </div>
              <div class="wp__row wp__row--sub">
                <select v-model="row.action" class="form__input form__select form__input--sm" @change="onActionChange(row)">
                  <option v-for="(label, value) in actions" :key="value" :value="value">{{ label }}</option>
                </select>
                <input
                  v-if="row.action === 'hover'"
                  v-model.number="row.hoverSeconds"
                  class="form__input form__input--sm"
                  type="number"
                  min="1"
                  placeholder="悬停秒数"
                />
                <input v-model.number="row.alt" class="form__input form__input--sm" type="number" min="0" placeholder="高度(米)" />
              </div>
            </div>
            <p v-if="waypoints.length === 0" class="editor__empty">
              还没有航点。从左边「加入」，或者<b>直接点下面的地图</b>加一个临时点。
            </p>
          </div>
        </div>
      </div>

      <div class="preview">
        <GeoMap
          v-if="dialogVisible"
          :lines="lines"
          :markers="markers"
          :center="center"
          :fit="waypoints.length > 0"
          :pickable="true"
          :height="260"
          pick-hint="点击地图可以把那个位置加为航点（临时点，不进入点位库）"
          empty-hint="还没有航点"
          @pick="addFromMap"
        />
      </div>

      <template #footer>
        <button class="btn btn--ghost" type="button" @click="dialogVisible = false">取消</button>
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

.empty {
  padding: 40px 20px;
  text-align: center;
  color: var(--muted);
}

.remark {
  max-width: 200px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.time {
  font-size: 12.5px;
  color: var(--text-2);
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
  margin-bottom: 14px;
}

.form__row {
  display: flex;
  align-items: center;
  gap: 14px;
  flex: 1;
  min-width: 260px;
}

.form__label {
  width: 48px;
  flex: none;
  font-size: 13px;
  color: var(--muted);
}

.form__input {
  flex: 1;
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
  height: 30px;
  font-size: 12.5px;
  flex: none;
  width: 120px;
}

.form__select {
  appearance: none;
  cursor: pointer;
  background-image: linear-gradient(45deg, transparent 50%, var(--muted) 50%),
    linear-gradient(135deg, var(--muted) 50%, transparent 50%);
  background-position: calc(100% - 16px) 12px, calc(100% - 11px) 12px;
  background-size: 5px 5px, 5px 5px;
  background-repeat: no-repeat;
}

.form__select option {
  color: var(--text);
  background-color: #0b1830;
}

/* ---------- 航点编辑器 ---------- */
.editor {
  display: grid;
  grid-template-columns: 1fr 1.15fr;
  gap: 14px;
}

.editor__col {
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.editor__head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  margin-bottom: 8px;
  font-size: 13px;
  color: var(--text);
}

.editor__head small {
  font-size: 11.5px;
  color: var(--muted);
}

.editor__list {
  height: 260px;
  overflow-y: auto;
  padding: 6px;
  border-radius: 9px;
  border: 1px solid var(--line);
  background: rgba(255, 255, 255, 0.02);
}

.editor__empty {
  margin: 20px 8px;
  font-size: 12.5px;
  line-height: 1.8;
  color: var(--muted);
}

.search--sm {
  margin-bottom: 8px;
  height: 34px;
}

.cand {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 7px 8px;
  border-radius: 7px;
}

.cand:hover {
  background: rgba(255, 255, 255, 0.04);
}

.cand__info {
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.cand__name {
  font-size: 13px;
  color: var(--text);
}

.cand__meta {
  font-size: 11.5px;
  color: var(--muted);
}

.wp {
  padding: 6px 8px;
  border-radius: 7px;
}

.wp:hover {
  background: rgba(255, 255, 255, 0.04);
}

.wp__row {
  display: flex;
  align-items: center;
  gap: 8px;
}

.wp__row--sub {
  margin-top: 6px;
  padding-left: 26px;
}

.wp__seq {
  width: 18px;
  height: 18px;
  flex: none;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  font-size: 11px;
  color: #03121f;
  background: #f59e0b;
}

.wp__name {
  font-size: 12.5px;
  color: var(--text);
  max-width: 120px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.wp__coord {
  flex: 1;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 11px;
  color: var(--muted);
}

.op.is-disabled {
  opacity: 0.35;
  cursor: not-allowed;
}

.op.is-disabled:hover {
  color: var(--accent);
}

.preview {
  margin-top: 14px;
}
</style>
