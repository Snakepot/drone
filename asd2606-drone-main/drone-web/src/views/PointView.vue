<script setup lang="ts">
/**
 * 点位库。
 *
 * <h2>它解决什么问题</h2>
 * 巡检的点位是<b>复用</b>的（这条线上那 12 个塔，下个月还要巡一次）。
 * 没有点位库，每次派活都得重新在地图上点一遍，而且点出来的位置每次都不一样。
 *
 * <h2>坐标在这条链路上只转一次，而且不在前端</h2>
 * 地图上点出来的是 GCJ-02 → 原样提交 → 后端转成 WGS84 入库；
 * 读出来时后端已经转回 GCJ-02。所以这个页面里<b>一个字都不提坐标系</b>，
 * 拿来就画、点完就传。
 */
import { computed, onMounted, reactive, ref } from 'vue'
import {
  addPoint,
  deletePoint,
  fetchPointCategories,
  fetchPointPage,
  updatePoint,
  type SurveyPointQuery,
  type SurveyPointVO,
} from '@/api/route'
import GeoMap from '@/components/GeoMap.vue'
import type { GeoMarker, GeoPoint } from '@/utils/geo'

const loading = ref(false)
const list = ref<SurveyPointVO[]>([])
const total = ref(0)

const query = reactive<SurveyPointQuery>({
  keyword: '',
  category: '',
  page: 1,
  pageSize: 10,
})

/** 类型字典（编码 → 中文名）从后端取 —— 后端加了类型，这里下拉自动就有 */
const categories = ref<Record<string, string>>({})

/** 点位库为空时地图的起始视角：北京。等有数据了就用第一个点作中心 */
const DEFAULT_CENTER: GeoPoint = { lng: 116.397, lat: 39.909 }

const markers = computed<GeoMarker[]>(() =>
  list.value.map((p) => ({
    lng: p.lng,
    lat: p.lat,
    label: p.name,
    // 起降点单独一个颜色：它是安全相关的，混在巡检点里看不出来
    tone: p.category === 'takeoff' ? 'takeoff' : 'point',
  })),
)

const totalPages = computed(() => Math.max(1, Math.ceil(total.value / (query.pageSize ?? 10))))
const currentPage = computed(() => query.page ?? 1)

async function loadData() {
  loading.value = true
  try {
    const result = await fetchPointPage(query)
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

onMounted(async () => {
  try {
    categories.value = await fetchPointCategories()
  } catch {
    // 字典取不到就先不显示类型选项，不影响列表
  }
  await loadData()
})

function handleSearch() {
  query.page = 1
  loadData()
}

function handleReset() {
  query.keyword = ''
  query.category = ''
  query.page = 1
  loadData()
}

function handlePageChange(page: number) {
  if (page < 1 || page > totalPages.value) return
  query.page = page
  loadData()
}

// ---------------------------------------------------------------------------
// 新增 / 编辑
// ---------------------------------------------------------------------------
const formVisible = ref(false)
const formSaving = ref(false)

/**
 * 表单。
 *
 * <p>{@code lng/lat} 初始为 undefined 而不是 0 —— <b>0,0 会被后端当成
 * 「地图没加载好就点了确定」直接拒收</b>（那在几内亚湾）。用 undefined
 * 表示「还没选位置」，是在前端就把这件事说清楚，而不是传一个假坐标过去。
 */
const form = reactive({
  id: undefined as number | undefined,
  code: '',
  name: '',
  category: 'survey',
  lng: undefined as number | undefined,
  lat: undefined as number | undefined,
  alt: undefined as number | undefined,
  remark: '',
})

const isEdit = computed(() => Boolean(form.id))
const hasPicked = computed(() => form.lng !== undefined && form.lat !== undefined)

/** 编辑时以原来的位置为中心；新增时以当页第一个点位为中心（比固定北京合理） */
const pickCenter = computed<GeoPoint>(() => {
  if (hasPicked.value) {
    return { lng: form.lng as number, lat: form.lat as number }
  }
  const first = markers.value[0]
  return first ? { lng: first.lng, lat: first.lat } : DEFAULT_CENTER
})

/** 选中的那一个点，画在地图上 */
const pickedMarker = computed<GeoMarker[]>(() =>
  hasPicked.value
    ? [{ lng: form.lng as number, lat: form.lat as number, label: '选中的位置', tone: 'takeoff' }]
    : [],
)

function resetForm() {
  form.id = undefined
  form.code = ''
  form.name = ''
  form.category = 'survey'
  form.lng = undefined
  form.lat = undefined
  form.alt = undefined
  form.remark = ''
}

function openAddDialog() {
  resetForm()
  formVisible.value = true
}

function openEditDialog(point: SurveyPointVO) {
  resetForm()
  form.id = point.id
  form.code = point.code ?? ''
  form.name = point.name
  form.category = point.category
  // 接口给的就是 GCJ-02，直接回显；点一下地图就会换成新的位置
  form.lng = point.lng
  form.lat = point.lat
  form.alt = point.alt
  form.remark = point.remark ?? ''
  formVisible.value = true
}

/** 地图上点了一下 */
function handlePick(point: GeoPoint) {
  // 保留 6 位小数：和库里 decimal(10,6) 的精度一致，
  // 多留几位只会在界面上显示一长串没意义的数字
  form.lng = Number(point.lng.toFixed(6))
  form.lat = Number(point.lat.toFixed(6))
}

async function submitForm() {
  if (!form.name.trim()) {
    ElMessage.warning('请填写点位名称')
    return
  }
  if (!hasPicked.value) {
    ElMessage.warning('请在地图上点选点位位置')
    return
  }

  const payload = {
    id: form.id,
    code: form.code.trim() || undefined,
    name: form.name.trim(),
    lng: form.lng as number,
    lat: form.lat as number,
    alt: form.alt,
    category: form.category,
    remark: form.remark.trim() || undefined,
  }

  formSaving.value = true
  try {
    if (isEdit.value) {
      await updatePoint(payload)
    } else {
      await addPoint(payload)
    }
    ElMessage.success(isEdit.value ? '保存成功' : '新增成功')
    formVisible.value = false
    await loadData()
  } catch {
    // 错误提示由 axios 响应拦截器统一处理
  } finally {
    formSaving.value = false
  }
}

async function handleDelete(point: SurveyPointVO) {
  try {
    await ElMessageBox.confirm(
      `确定删除点位「${point.name}」吗？\n` +
        '已经用过它的航线不会受影响（航线上存的是复制过去的坐标）。',
      '删除点位',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  try {
    await deletePoint(point.id)
    ElMessage.success('已删除')
    // 删掉当页最后一条时往前翻一页，免得停在一张空页上
    if (list.value.length === 1 && currentPage.value > 1) {
      query.page = currentPage.value - 1
    }
    await loadData()
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
        <input v-model="query.keyword" type="text" placeholder="搜索名称 / 编号" @keyup.enter="handleSearch" />
      </label>

      <select v-model="query.category" class="select">
        <option value="">全部类型</option>
        <option v-for="(label, value) in categories" :key="value" :value="value">{{ label }}</option>
      </select>

      <button class="btn btn--primary" type="button" @click="handleSearch">查询</button>
      <button class="btn btn--ghost" type="button" @click="handleReset">重置</button>
    </div>

    <!-- ============ 点位地图 ============ -->
    <div class="card mapcard">
      <div class="card__head">
        <div class="card__title">
          点位分布
          <small>画的是<b>当前这一页</b>的点位（共 {{ list.length }} 个）</small>
        </div>
      </div>
      <GeoMap
        :markers="markers"
        :height="300"
        empty-hint="这一页没有点位 —— 点右上角「新增点位」，在地图上点一下就能建一个"
      />
    </div>

    <!-- ============ 列表 ============ -->
    <div class="card">
      <div class="card__head">
        <div class="card__title">
          点位库
          <small>共 {{ total }} 个点位</small>
        </div>
        <div class="card__tools">
          <button class="btn btn--primary" type="button" @click="openAddDialog">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round">
              <path d="M12 5v14M5 12h14" />
            </svg>
            新增点位
          </button>
        </div>
      </div>

      <div class="table-wrap">
        <table>
          <thead>
            <tr>
              <th>名称</th>
              <th>编号</th>
              <th>类型</th>
              <th>坐标（GCJ-02）</th>
              <th>作业高度</th>
              <th>备注</th>
              <th>创建人</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="point in list" :key="point.id">
              <td class="strong">{{ point.name }}</td>
              <td class="code">{{ point.code || '—' }}</td>
              <td>
                <span class="tag" :class="point.category === 'takeoff' ? 'tag--violet' : 'tag--cyan'">
                  {{ point.categoryLabel }}
                </span>
              </td>
              <td class="coord">{{ point.lng.toFixed(6) }}, {{ point.lat.toFixed(6) }}</td>
              <td>{{ point.alt == null ? '—' : point.alt + ' m' }}</td>
              <td class="remark">{{ point.remark || '—' }}</td>
              <td>{{ point.createByName || '—' }}</td>
              <td>
                <div class="ops">
                  <span class="op" @click="openEditDialog(point)">编辑</span>
                  <span class="op op--danger" @click="handleDelete(point)">删除</span>
                </div>
              </td>
            </tr>
            <tr v-if="!loading && list.length === 0">
              <td colspan="8" class="empty">还没有点位</td>
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

    <!-- ============ 新增 / 编辑弹窗 ============ -->
    <el-dialog v-model="formVisible" :title="isEdit ? '编辑点位' : '新增点位'" width="680px" align-center>
      <div class="form">
        <label class="form__row">
          <span class="form__label">名称</span>
          <input v-model="form.name" class="form__input" placeholder="如：东线 12 号塔" />
        </label>

        <label class="form__row">
          <span class="form__label">编号</span>
          <input v-model="form.code" class="form__input" placeholder="客户给的点位编号，可不填" />
        </label>

        <label class="form__row">
          <span class="form__label">类型</span>
          <select v-model="form.category" class="form__input form__select">
            <option v-for="(label, value) in categories" :key="value" :value="value">{{ label }}</option>
          </select>
        </label>

        <label class="form__row">
          <span class="form__label">作业高度</span>
          <input v-model.number="form.alt" class="form__input" type="number" min="0" placeholder="米，相对起飞点，可不填" />
        </label>

        <label class="form__row">
          <span class="form__label">备注</span>
          <input v-model="form.remark" class="form__input" placeholder="可不填" />
        </label>

        <div class="pick">
          <div class="pick__head">
            <span>位置</span>
            <span v-if="hasPicked" class="pick__coord">
              {{ (form.lng as number).toFixed(6) }}, {{ (form.lat as number).toFixed(6) }}
            </span>
            <span v-else class="pick__coord pick__coord--warn">还没有选位置</span>
          </div>
          <!--
            ⚠️ 只在弹窗打开时渲染地图：地图容器必须有真实宽度高度，
            藏在 display:none 里初始化会画不出来（而且不报错）
          -->
          <GeoMap
            v-if="formVisible"
            pickable
            :fit="false"
            :center="pickCenter"
            :zoom="15"
            :markers="pickedMarker"
            :height="300"
            pick-hint="点击地图上的位置即可选中（选中的点会显示成紫色）"
            @pick="handlePick"
          />
        </div>
      </div>

      <template #footer>
        <button class="btn btn--ghost" type="button" @click="formVisible = false">取消</button>
        <button class="btn btn--primary" type="button" :disabled="formSaving" @click="submitForm">
          {{ formSaving ? '保存中' : '确定' }}
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

.mapcard {
  padding-bottom: 16px;
  margin-bottom: 18px;
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
  transition: border-color 0.25s, box-shadow 0.25s;
  appearance: none;
  background-image: linear-gradient(45deg, transparent 50%, var(--muted) 50%),
    linear-gradient(135deg, var(--muted) 50%, transparent 50%);
  background-position: calc(100% - 16px) 15px, calc(100% - 11px) 15px;
  background-size: 5px 5px, 5px 5px;
  background-repeat: no-repeat;
}

.select:focus {
  border-color: var(--accent-hover);
  box-shadow: 0 0 0 3px rgba(34, 211, 238, 0.12);
}

.select option {
  color: var(--text);
  background-color: #0b1830;
}

.coord {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 12px;
  color: var(--text-2);
}

.remark {
  max-width: 180px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
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

.form__row {
  display: flex;
  align-items: center;
  gap: 14px;
}

.form__label {
  width: 74px;
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

.pick__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
  font-size: 13px;
  color: var(--muted);
}

.pick__coord {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 12px;
  color: var(--accent);
}

.pick__coord--warn {
  color: #fbbf24;
}
</style>
