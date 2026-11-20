<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import {
  fetchAlertDetail,
  fetchAlertPage,
  handleAlert,
  type AlertAction,
  type AlertQuery,
  type AlertVO,
} from '@/api/alert'
import AttachmentList from '@/components/AttachmentList.vue'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()

const loading = ref(false)
const list = ref<AlertVO[]>([])
const total = ref(0)

const query = reactive<AlertQuery>({
  keyword: '',
  severity: '',
  status: '',
  page: 1,
  pageSize: 15,
})

/**
 * 「发生时间」区间：给**下钻**用。
 *
 * <p>分析页的 MTTR 与告警趋势都按 observed_at 落窗，点一下跳过来带上这个区间，
 * 条数才能和那个数对上（见 docs/业务分析方案.md 三点五）。
 */
const observedRange = ref<[string, string] | null>(null)

/** 下钻带进来的参数（`/alert?status=resolved&observedFrom=…&observedTo=…`） */
const route = useRoute()

function applyQueryFromUrl() {
  const q = route.query
  if (typeof q.status === 'string') query.status = q.status
  if (typeof q.severity === 'string') query.severity = q.severity
  if (typeof q.observedFrom === 'string' && q.observedFrom) {
    observedRange.value = [q.observedFrom, typeof q.observedTo === 'string' ? q.observedTo : '']
  }
}

/**
 * 级别与状态的选项写在前端，中文名用后端给的那个（alert 里的 label 字段）。
 *
 * <p>这和设备页的做法不同：设备的类型/状态只有几个取值，前端自己写字典很轻；
 * 而告警编码有 27 个、「编码 → 中文名」只在后端维护一处
 * （见 api/alert.ts 的注释）。所以这里的选项表**只管「有哪些选项」**，
 * 展示用的中文名一律用行数据里的 label，前端不重复定义一套。
 */
const severityOptions = [
  { value: 'critical', label: '严重' },
  { value: 'warning', label: '警告' },
  { value: 'info', label: '提示' },
]

const statusOptions = [
  { value: 'pending', label: '待处理' },
  { value: 'handling', label: '处理中' },
  { value: 'resolved', label: '已处理' },
  { value: 'ignored', label: '误报' },
]

/** 级别配色：颜色是展示层的事，后端不管 */
function severityTone(severity?: string) {
  switch (severity) {
    case 'critical':
      return 'red'
    case 'warning':
      return 'amber'
    case 'info':
      return 'grey'
    default:
      return 'grey'
  }
}

function statusTone(status?: string) {
  switch (status) {
    case 'pending':
      return 'red'
    case 'handling':
      return 'amber'
    case 'resolved':
      return 'green'
    case 'ignored':
      return 'grey'
    default:
      return 'grey'
  }
}

const canHandle = computed(() => auth.hasPermission('alert:handle'))

const totalPages = computed(() => Math.max(1, Math.ceil(total.value / (query.pageSize ?? 15))))
const currentPage = computed(() => query.page ?? 1)

async function loadData() {
  loading.value = true
  try {
    const result = await fetchAlertPage({
      ...query,
      observedFrom: observedRange.value?.[0],
      observedTo: observedRange.value?.[1],
    })
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

onMounted(() => {
  // 先吃 URL 上的下钻参数，再拉数据
  applyQueryFromUrl()
  loadData()
})

function handleSearch() {
  query.page = 1
  loadData()
}

function handleReset() {
  query.keyword = ''
  query.severity = ''
  query.status = ''
  // 连下钻带进来的时间区间一起清掉（否则筛选项看着是空的、结果却少）
  observedRange.value = null
  query.page = 1
  loadData()
}

function handlePageChange(page: number) {
  if (page < 1 || page > totalPages.value) return
  query.page = page
  loadData()
}

// ---------------------------------------------------------------------------
// 处理（认领 / 已处理 / 误报）
// ---------------------------------------------------------------------------
const handleVisible = ref(false)
const handleSaving = ref(false)
const handleTarget = ref<AlertVO | null>(null)
const handleAction = ref<AlertAction>('claim')
const handleNote = ref('')

/** 收尾动作（已处理 / 误报）必须写说明 —— 后端也会拦，这里先提示一下体验更好 */
const noteRequired = computed(() => handleAction.value !== 'claim')

const actionTitle = computed(() => {
  switch (handleAction.value) {
    case 'claim':
      return '认领告警'
    case 'resolve':
      return '标记为已处理'
    default:
      return '标记为误报'
  }
})

function openHandle(alert: AlertVO, action: AlertAction) {
  handleTarget.value = alert
  handleAction.value = action
  handleNote.value = ''
  handleVisible.value = true
}

async function submitHandle() {
  if (!handleTarget.value) return
  if (noteRequired.value && !handleNote.value.trim()) {
    ElMessage.warning('请填写处置说明')
    return
  }

  handleSaving.value = true
  try {
    await handleAlert({
      id: handleTarget.value.id,
      action: handleAction.value,
      note: handleNote.value.trim() || undefined,
    })
    ElMessage.success('已处理')
    handleVisible.value = false
    await loadData()
  } catch {
    // 错误提示由 axios 响应拦截器统一处理
  } finally {
    handleSaving.value = false
  }
}

// ---------------------------------------------------------------------------
// 详情
// ---------------------------------------------------------------------------
const detailVisible = ref(false)
const detail = ref<AlertVO | null>(null)

/**
 * 打开详情弹窗。
 *
 * <p>详情比列表多带附件（列表里只显示一个「照片 N」小标）——
 * 查看时后端会连同附件一起返回，所以这里不用再请求一次。
 */
async function openDetail(alert: AlertVO) {
  detail.value = await fetchAlertDetail(alert.id)
  detailVisible.value = true
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
        <input
          v-model="query.keyword"
          type="text"
          placeholder="搜索设备编号 / 告警内容 / 编码"
          @keyup.enter="handleSearch"
        />
      </label>

      <select v-model="query.severity" class="select">
        <option value="">全部级别</option>
        <option v-for="item in severityOptions" :key="item.value" :value="item.value">{{ item.label }}</option>
      </select>

      <select v-model="query.status" class="select">
        <option value="">全部状态</option>
        <option v-for="item in statusOptions" :key="item.value" :value="item.value">{{ item.label }}</option>
      </select>

      <!-- 按「发生时间」筛：分析页下钻过来时用，也可以自己选 -->
      <el-date-picker
        v-model="observedRange"
        type="datetimerange"
        value-format="YYYY-MM-DD HH:mm:ss"
        start-placeholder="发生时间从"
        end-placeholder="到"
        range-separator="~"
        class="filter-range"
      />

      <button class="btn btn--primary" type="button" @click="handleSearch">查询</button>
      <button class="btn btn--ghost" type="button" @click="handleReset">重置</button>
    </div>

    <!-- ============ 列表 ============ -->
    <div class="card">
      <div class="card__head">
        <div class="card__title">
          告警列表
          <small>共 {{ total }} 条 · 未处理的排在前面，同级按时间倒序</small>
        </div>
      </div>

      <div class="table-wrap">
        <table>
          <thead>
            <tr>
              <th>级别</th>
              <th>告警</th>
              <th>内容</th>
              <th>设备</th>
              <th>发生时刻</th>
              <th>状态</th>
              <th>处理人</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="alert in list" :key="alert.id">
              <td>
                <span class="tag" :class="`tag--${severityTone(alert.severity)}`">
                  {{ alert.severityLabel ?? alert.severity }}
                </span>
              </td>
              <td>
                <div class="name">{{ alert.alertCodeLabel ?? alert.alertCode }}</div>
                <div class="code">{{ alert.alertCode }}</div>
              </td>
              <td class="message">
                <!--
                  有附件的标出来。**不标的话等于没有**：列表按紧急程度排序，
                  带照片的那条往往不在第一屏的位置，而「详情」里附件那一行
                  在没有附件时是不显示的 —— 于是点错一条就以为「没有附件」。
                  （实测就是这么发生的：飞手报的那条在列表第 3 位，
                  而前两条是 critical 的设备告警，管理侧点开的是前两条。）
                -->
                <span v-if="(alert.attachments || []).length" class="attach">
                  照片 {{ alert.attachments!.length }}
                </span>
                {{ alert.message || '—' }}
                <span v-if="alert.pilotActionRequired" class="hint" :title="alert.actionHint">
                  · 需飞手处理{{ alert.actionHint ? '：' + alert.actionHint : '' }}
                </span>
              </td>
              <td class="code">{{ alert.deviceCode || '—' }}</td>
              <td class="code">{{ alert.observedAt || '—' }}</td>
              <td>
                <span class="tag" :class="`tag--${statusTone(alert.status)}`">
                  {{ alert.statusLabel ?? alert.status }}
                </span>
              </td>
              <td>
                <div v-if="alert.handledByName">{{ alert.handledByName }}</div>
                <div v-if="alert.handledAt" class="code">{{ alert.handledAt }}</div>
                <span v-if="!alert.handledByName && !alert.handledAt">—</span>
              </td>
              <td>
                <div class="ops">
                  <span class="op" @click="openDetail(alert)">详情</span>
                  <!--
                    认领只对「待处理」显示，处理只对「处理中」显示，误报两个状态都能点。
                    这和后端的状态机一一对应（AlertStatusFlow）—— 前端不显示不可用的动作，
                    省得用户点了才被告知不允许。
                  -->
                  <span
                    v-if="canHandle && alert.status === 'pending'"
                    class="op"
                    @click="openHandle(alert, 'claim')"
                  >
                    认领
                  </span>
                  <span
                    v-if="canHandle && alert.status === 'handling'"
                    class="op"
                    @click="openHandle(alert, 'resolve')"
                  >
                    标记已处理
                  </span>
                  <span
                    v-if="canHandle && (alert.status === 'pending' || alert.status === 'handling')"
                    class="op"
                    @click="openHandle(alert, 'ignore')"
                  >
                    误报
                  </span>
                </div>
              </td>
            </tr>
            <tr v-if="!loading && list.length === 0">
              <td colspan="8" class="empty">没有符合条件的告警</td>
            </tr>
          </tbody>
        </table>
      </div>

      <div class="pager">
        <span>共 {{ total }} 条，第 {{ currentPage }} / {{ totalPages }} 页</span>
        <div class="pager__pages">
          <span
            class="pager__btn"
            :class="{ 'is-disabled': currentPage <= 1 }"
            @click="handlePageChange(currentPage - 1)"
          >
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

    <!-- ============ 处理弹窗 ============ -->
    <el-dialog v-model="handleVisible" :title="actionTitle" width="520px" align-center>
      <div v-if="handleTarget" class="summary">
        <p><b>{{ handleTarget.alertCodeLabel ?? handleTarget.alertCode }}</b> · {{ handleTarget.message }}</p>
        <p class="code">
          设备 {{ handleTarget.deviceCode || '—' }} · 发生 {{ handleTarget.observedAt || '—' }}
        </p>
      </div>

      <p v-if="handleAction === 'claim'" class="tip">
        认领表示「我来负责把这条告警处理完」。认领后状态变成「处理中」，
        处理完再点「标记已处理」。
      </p>
      <p v-else class="tip">
        处置说明是必填的 ——
        <template v-if="handleAction === 'ignore'">
          写清为什么是误报，这是后面调整告警阈值最直接的依据。
        </template>
        <template v-else>
          一个没有说明的「已处理」和不处理没有区别，事后复盘时仍然是黑盒。
        </template>
      </p>

      <textarea
        v-model="handleNote"
        class="note"
        rows="4"
        :placeholder="handleAction === 'claim' ? '选填：打算怎么处理' : '必填：做了什么、结论是什么'"
      ></textarea>

      <template #footer>
        <button class="btn btn--ghost" type="button" @click="handleVisible = false">取消</button>
        <button class="btn btn--primary" type="button" :disabled="handleSaving" @click="submitHandle">
          {{ handleSaving ? '提交中' : '确认' }}
        </button>
      </template>
    </el-dialog>

    <!-- ============ 详情弹窗 ============ -->
    <el-dialog
      v-model="detailVisible"
      title="告警详情"
      width="560px"
      align-center
    >
      <div v-if="detail" class="detail">
        <div class="detail__row"><span>告警</span><b>{{ detail.alertCodeLabel }}（{{ detail.alertCode }}）</b></div>
        <div class="detail__row"><span>级别</span>{{ detail.severityLabel }}</div>
        <div class="detail__row"><span>内容</span>{{ detail.message || '—' }}</div>
        <div class="detail__row"><span>设备</span>{{ detail.deviceCode || '—' }}</div>
        <div class="detail__row"><span>所属任务</span>{{ detail.taskId ?? '不属于任何任务' }}</div>
        <div class="detail__row"><span>来源</span>{{ detail.source || '—' }}</div>
        <div class="detail__row"><span>发生时刻</span>{{ detail.observedAt || '—' }}</div>
        <div class="detail__row"><span>收到时刻</span>{{ detail.reportedAt || '—' }}</div>
        <div class="detail__row"><span>状态</span>{{ detail.statusLabel }}</div>
        <div class="detail__row" v-if="detail.pilotActionRequired">
          <span>需飞手处理</span>{{ detail.actionHint || '是' }}
        </div>
        <div class="detail__row" v-if="detail.handledByName">
          <span>处理人</span>{{ detail.handledByName }} · {{ detail.handledAt }}
        </div>
        <div class="detail__row" v-if="detail.handleNote"><span>处置说明</span>{{ detail.handleNote }}</div>

        <!--
          现场照片。飞手端上报时拍的，管理侧靠它判断「是不是真的坏了」——
          没有这一块的话，飞手拍的照片只有他自己能看到，等于白拍。
          **这一行始终显示**（没有附件时组件内部会写「无」）：
          不显示的话，人分不清「这条没有附件」和「这个功能不存在」—— 实测就是这么误判的。
          图片直接显示、点开大图，不让人先下载再打开（判断「现场什么样」看一眼就够）。
        -->
        <div class="detail__row">
          <span>附件</span>
          <AttachmentList :attachments="detail.attachments" />
        </div>
      </div>

      <template #footer>
        <button class="btn btn--ghost" type="button" @click="detailVisible = false">关闭</button>
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

.name {
  font-size: 13.5px;
  color: var(--text);
}

.code {
  font-size: 12px;
  color: var(--muted);
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
}

.message {
  max-width: 320px;
  font-size: 13px;
  color: var(--text-2);
}

.hint {
  color: var(--amber, #fbbf24);
  font-size: 12px;
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

.pager__btn.is-disabled:hover {
  color: var(--text-2);
  border-color: var(--line);
}

/* ---------- 处理弹窗 ---------- */
.summary {
  padding: 12px 14px;
  border-radius: 10px;
  background: var(--ghost-bg);
  border: 1px solid var(--line);
  margin-bottom: 14px;
}

.summary p {
  font-size: 13px;
  color: var(--text);
  margin-bottom: 6px;
}

.summary p:last-child {
  margin-bottom: 0;
}

.tip {
  font-size: 12.5px;
  line-height: 1.9;
  color: var(--muted);
  margin-bottom: 10px;
}

.note {
  width: 100%;
  padding: 10px 12px;
  border-radius: 9px;
  font-size: 13.5px;
  font-family: inherit;
  color: var(--text);
  background: var(--input-bg);
  border: 1px solid var(--line-strong);
  outline: none;
  resize: vertical;
  transition: border-color 0.25s, box-shadow 0.25s;
}

.note:focus {
  border-color: var(--accent-hover);
  box-shadow: 0 0 0 3px rgba(34, 211, 238, 0.12);
}

/* ---------- 详情弹窗 ---------- */
.detail {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.detail__row {
  display: flex;
  gap: 14px;
  font-size: 13px;
  color: var(--text-2);
  line-height: 1.7;
}

.detail__row > span {
  flex: none;
  width: 84px;
  color: var(--muted);
}

/* 列表里标出「这条有照片」，避免点错一条就以为没这功能 */
.attach {
  display: inline-block;
  margin-right: 8px;
  padding: 1px 8px;
  border-radius: 999rpx;
  font-size: 12px;
  color: var(--accent, #38bdf8);
  border: 1px solid var(--accent, #38bdf8);
  white-space: nowrap;
}
</style>
