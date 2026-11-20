<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import {
  addDevice,
  downloadDeviceTemplate,
  exportDevices,
  fetchDeviceDetail,
  fetchDevicePage,
  importDevices,
  updateDevice,
  type DeviceQuery,
  type DeviceSaveParam,
  type DeviceVO,
} from '@/api/device'
import type { ImportResult } from '@/api/request'
import { useAuthStore } from '@/stores/auth'
import { saveBlob } from '@/utils/download'

const auth = useAuthStore()

const loading = ref(false)
const list = ref<DeviceVO[]>([])
const total = ref(0)

const query = reactive<DeviceQuery>({
  keyword: '',
  type: '',
  status: '',
  page: 1,
  pageSize: 10,
})

/**
 * 类型与状态的选项直接写在前端。
 * 它们是相对固定的业务枚举（不像角色那样可以随时新增），
 * 为此单独开一个接口不值得；真调整时前后端一起改即可。
 * 展示映射与后端导出用的字典保持一致。
 */
const typeOptions = [
  { value: 'multi-rotor', label: '多旋翼' },
  { value: 'fixed-wing', label: '固定翼' },
]

const statusOptions = [
  { value: 'idle', label: '待命' },
  { value: 'assigned', label: '已派出' },
  { value: 'flying', label: '飞行中' },
  { value: 'charging', label: '充电中' },
  { value: 'offline', label: '离线' },
  { value: 'maintenance', label: '维护中' },
]

/** 未知编码原样显示 —— 后端将来加了新状态，这里不至于显示空白 */
function labelOf(options: { value: string; label: string }[], value?: string) {
  return options.find((item) => item.value === value)?.label ?? value ?? '—'
}

function statusTone(status?: string) {
  switch (status) {
    case 'idle':
      return 'green'
    // 已派出：派了任务还没起飞，用紫色跟真正在飞的「飞行中」区分开
    case 'assigned':
      return 'violet'
    case 'flying':
      return 'cyan'
    case 'charging':
      return 'amber'
    case 'maintenance':
      return 'red'
    case 'offline':
      return 'grey'
    default:
      return 'grey'
  }
}

const totalPages = computed(() =>
  Math.max(1, Math.ceil(total.value / (query.pageSize ?? 10))),
)

/** 当前页码，兜底为 1 —— 免得模板里到处写 ?? 1 */
const currentPage = computed(() => query.page ?? 1)

async function loadData() {
  loading.value = true
  try {
    const result = await fetchDevicePage(query)
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

onMounted(loadData)

function handleSearch() {
  query.page = 1
  loadData()
}

function handleReset() {
  query.keyword = ''
  query.type = ''
  query.status = ''
  query.page = 1
  loadData()
}

function handlePageChange(page: number) {
  if (page < 1 || page > totalPages.value) return
  query.page = page
  loadData()
}

// ---------------------------------------------------------------------------
// 导出
// ---------------------------------------------------------------------------
const exporting = ref(false)

async function handleExport() {
  exporting.value = true
  try {
    // 复用当前筛选条件，但刻意不带分页 —— 导出的是符合条件的全部数据
    const condition: DeviceQuery = {
      keyword: query.keyword,
      type: query.type,
      status: query.status,
    }
    const blob = await exportDevices(condition)
    if (await saveBlob(blob, '设备列表.xlsx')) {
      ElMessage.success('导出成功')
    }
  } catch {
    // 错误提示由 axios 响应拦截器统一处理
  } finally {
    exporting.value = false
  }
}

// ---------------------------------------------------------------------------
// 导入
// ---------------------------------------------------------------------------
const importVisible = ref(false)
const importFile = ref<File | null>(null)
const importing = ref(false)
const importResult = ref<ImportResult | null>(null)

function openImport() {
  importFile.value = null
  importResult.value = null
  importVisible.value = true
}

function handleFileChange(event: Event) {
  const input = event.target as HTMLInputElement
  importFile.value = input.files?.[0] ?? null
  importResult.value = null
}

async function handleDownloadTemplate() {
  const blob = await downloadDeviceTemplate()
  await saveBlob(blob, '设备导入模版.xlsx')
}

async function submitImport() {
  if (!importFile.value) {
    ElMessage.warning('请先选择要导入的文件')
    return
  }

  importing.value = true
  try {
    const result = await importDevices(importFile.value)
    importResult.value = result
    await loadData()

    if (result.failed === 0) {
      ElMessage.success(`导入成功，共 ${result.success} 条`)
    } else {
      ElMessage.warning(`成功 ${result.success} 条，失败 ${result.failed} 条（见下方明细）`)
    }
  } catch {
    // 错误提示由 axios 响应拦截器统一处理
  } finally {
    importing.value = false
  }
}

// ---------------------------------------------------------------------------
// 新增 / 编辑
// ---------------------------------------------------------------------------
const formVisible = ref(false)
const formSaving = ref(false)
const isEdit = computed(() => Boolean(form.id))

const form = reactive<DeviceSaveParam>({
  id: undefined,
  code: '',
  name: '',
  model: '',
  type: 'multi-rotor',
  purchaseDate: '',
  location: '',
  status: 'idle',
  flightCount: '0',
  flightHours: '0',
})

function resetForm() {
  form.id = undefined
  form.code = ''
  form.name = ''
  form.model = ''
  form.type = 'multi-rotor'
  form.purchaseDate = ''
  form.location = ''
  form.status = 'idle'
  form.flightCount = '0'
  form.flightHours = '0'
}

function openAddDialog() {
  resetForm()
  formVisible.value = true
}

async function openEditDialog(device: DeviceVO) {
  resetForm()
  const detail = await fetchDeviceDetail(device.id)
  form.id = detail.id
  form.code = detail.code
  form.name = detail.name
  form.model = detail.model
  form.type = detail.type ?? 'multi-rotor'
  form.purchaseDate = detail.purchaseDate ?? ''
  form.location = detail.location
  form.status = detail.status ?? 'idle'
  form.flightCount = detail.flightCount ?? '0'
  form.flightHours = detail.flightHours ?? '0'
  formVisible.value = true
}

async function submitForm() {
  if (!isEdit.value && !form.code?.trim()) {
    ElMessage.warning('请填写设备编号')
    return
  }
  if (!form.name?.trim()) {
    ElMessage.warning('请填写设备名称')
    return
  }

  formSaving.value = true
  try {
    if (isEdit.value) {
      await updateDevice(form)
    } else {
      await addDevice(form)
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
          placeholder="搜索编号 / 名称 / 型号"
          @keyup.enter="handleSearch"
        />
      </label>

      <select v-model="query.type" class="select">
        <option value="">全部类型</option>
        <option v-for="item in typeOptions" :key="item.value" :value="item.value">{{ item.label }}</option>
      </select>

      <select v-model="query.status" class="select">
        <option value="">全部状态</option>
        <option v-for="item in statusOptions" :key="item.value" :value="item.value">{{ item.label }}</option>
      </select>

      <button class="btn btn--primary" type="button" @click="handleSearch">查询</button>
      <button class="btn btn--ghost" type="button" @click="handleReset">重置</button>
    </div>

    <!-- ============ 列表 ============ -->
    <div class="card">
      <div class="card__head">
        <div class="card__title">
          设备列表
          <small>共 {{ total }} 条记录，导出会带上当前的筛选条件</small>
        </div>
        <div class="card__tools">
          <button
            v-if="auth.hasPermission('device:import')"
            class="btn btn--ghost"
            type="button"
            @click="openImport"
          >
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round">
              <path d="M12 19V6M7 11l5-5 5 5M4.5 19h15" />
            </svg>
            导入
          </button>
          <button
            v-if="auth.hasPermission('device:export')"
            class="btn btn--ghost"
            type="button"
            :disabled="exporting"
            @click="handleExport"
          >
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round">
              <path d="M12 5v13M7 13l5 5 5-5M4.5 21h15" />
            </svg>
            {{ exporting ? '导出中' : '导出' }}
          </button>
          <button
            v-if="auth.hasPermission('device:add')"
            class="btn btn--primary"
            type="button"
            @click="openAddDialog"
          >
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round">
              <path d="M12 5v14M5 12h14" />
            </svg>
            新增设备
          </button>
        </div>
      </div>

      <div class="table-wrap">
        <table>
          <thead>
            <tr>
              <th>设备编号</th>
              <th>名称</th>
              <th>型号</th>
              <th>类型</th>
              <th>当前位置</th>
              <th>状态</th>
              <th>飞行次数</th>
              <th>飞行时长</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="device in list" :key="device.id">
              <td class="code">{{ device.code }}</td>
              <td class="strong">{{ device.name }}</td>
              <td>{{ device.model || '—' }}</td>
              <td>{{ labelOf(typeOptions, device.type) }}</td>
              <td>{{ device.location || '—' }}</td>
              <td>
                <span class="tag" :class="`tag--${statusTone(device.status)}`">
                  {{ labelOf(statusOptions, device.status) }}
                </span>
              </td>
              <td>{{ device.flightCount ?? '0' }}</td>
              <td>{{ device.flightHours ?? '0' }} h</td>
              <td>
                <div class="ops">
                  <span
                    v-if="auth.hasPermission('device:edit')"
                    class="op"
                    @click="openEditDialog(device)"
                  >
                    编辑
                  </span>
                </div>
              </td>
            </tr>
            <tr v-if="!loading && list.length === 0">
              <td colspan="9" class="empty">没有符合条件的设备</td>
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

    <!-- ============ 导入弹窗 ============ -->
    <el-dialog v-model="importVisible" title="导入设备" width="520px" align-center>
      <p class="dialog__tip">
        先下载模版并按表头填写（类型填 multi-rotor、fixed-wing，状态填 idle、assigned、flying 等编码）。<br />
        上传后系统会逐行校验：<b>合法的导入，不合法的跳过</b>，并在下方列出原因。
      </p>

      <div class="import">
        <button class="btn btn--ghost" type="button" @click="handleDownloadTemplate">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round">
            <path d="M12 5v13M7 13l5 5 5-5M4.5 21h15" />
          </svg>
          下载导入模版
        </button>

        <label class="file">
          <input type="file" accept=".xlsx,.xls" @change="handleFileChange" />
          <span>{{ importFile ? importFile.name : '选择 Excel 文件' }}</span>
        </label>
      </div>

      <div v-if="importResult" class="result">
        <p class="result__summary">
          共 {{ importResult.total }} 条：成功
          <b class="result__ok">{{ importResult.success }}</b> 条，失败
          <b class="result__bad">{{ importResult.failed }}</b> 条
        </p>
        <ul v-if="importResult.errors.length" class="result__errors">
          <li v-for="(error, index) in importResult.errors" :key="index">{{ error }}</li>
        </ul>
      </div>

      <template #footer>
        <button class="btn btn--ghost" type="button" @click="importVisible = false">关闭</button>
        <button class="btn btn--primary" type="button" :disabled="importing" @click="submitImport">
          {{ importing ? '导入中' : '开始导入' }}
        </button>
      </template>
    </el-dialog>

    <!-- ============ 新增 / 编辑弹窗 ============ -->
    <el-dialog v-model="formVisible" :title="isEdit ? '编辑设备' : '新增设备'" width="520px" align-center>
      <div class="form">
        <label class="form__row">
          <span class="form__label">设备编号</span>
          <input
            v-model="form.code"
            class="form__input"
            type="text"
            :disabled="isEdit"
            placeholder="如 DRN-0001"
          />
        </label>
        <p v-if="isEdit" class="form__hint">编号是设备档案的业务主键，创建后不可修改</p>

        <label class="form__row">
          <span class="form__label">设备名称</span>
          <input v-model="form.name" class="form__input" type="text" placeholder="如 赤鸢一号" />
        </label>

        <label class="form__row">
          <span class="form__label">型号</span>
          <input v-model="form.model" class="form__input" type="text" placeholder="选填，如 Matrice 300 RTK" />
        </label>

        <div class="form__row">
          <span class="form__label">类型</span>
          <select v-model="form.type" class="form__input form__select">
            <option v-for="item in typeOptions" :key="item.value" :value="item.value">{{ item.label }}</option>
          </select>
        </div>

        <label class="form__row">
          <span class="form__label">采购日期</span>
          <input v-model="form.purchaseDate" class="form__input" type="date" />
        </label>

        <label class="form__row">
          <span class="form__label">当前位置</span>
          <input v-model="form.location" class="form__input" type="text" placeholder="选填，如 A 区机库" />
        </label>

        <div class="form__row">
          <span class="form__label">状态</span>
          <select v-model="form.status" class="form__input form__select">
            <option v-for="item in statusOptions" :key="item.value" :value="item.value">{{ item.label }}</option>
          </select>
        </div>

        <label class="form__row">
          <span class="form__label">飞行次数</span>
          <input v-model="form.flightCount" class="form__input" type="text" placeholder="累计次数" />
        </label>

        <label class="form__row">
          <span class="form__label">飞行时长</span>
          <input v-model="form.flightHours" class="form__input" type="text" placeholder="累计小时数" />
        </label>
      </div>

      <template #footer>
        <button class="btn btn--ghost" type="button" @click="formVisible = false">取消</button>
        <button class="btn btn--primary" type="button" :disabled="formSaving" @click="submitForm">
          {{ formSaving ? '保存中' : '保存' }}
        </button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
/* ---------- 检索栏 ---------- */
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

/* 下拉选项由浏览器渲染，深色主题下必须显式指定底色 */
.select option {
  color: var(--text);
  background-color: #0b1830;
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

/* ---------- 表单（新增 / 编辑弹窗） ---------- */
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

.form__input:disabled {
  color: var(--muted);
  cursor: not-allowed;
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
  margin: -8px 0 0 88px;
  font-size: 12px;
  color: var(--muted);
}

/* 日期选择器在深色下需要指定配色，否则日历图标几乎看不见 */
.form__input[type='date'] {
  color-scheme: dark;
}

/* ---------- 导入弹窗 ---------- */
.dialog__tip {
  margin-bottom: 16px;
  font-size: 12.5px;
  line-height: 1.9;
  color: var(--muted);
}

.import {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}

.file {
  flex: 1;
  min-width: 200px;
  display: flex;
  align-items: center;
  padding: 0 14px;
  height: 36px;
  border-radius: 9px;
  font-size: 13px;
  color: var(--text-2);
  background: var(--input-bg);
  border: 1px dashed var(--line-strong);
  cursor: pointer;
  transition: border-color 0.25s, color 0.25s;
}

.file:hover {
  color: var(--cyan);
  border-color: var(--accent-hover);
}

.file input {
  display: none;
}

.file span {
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.result {
  margin-top: 18px;
  padding: 14px 16px;
  border-radius: 10px;
  background: var(--ghost-bg);
  border: 1px solid var(--line);
}

.result__summary {
  font-size: 13px;
  color: var(--text-2);
}

.result__ok {
  color: var(--green);
}

.result__bad {
  color: var(--red);
}

.result__errors {
  margin: 10px 0 0;
  padding-left: 18px;
  max-height: 160px;
  overflow-y: auto;
  font-size: 12.5px;
  line-height: 1.9;
  color: var(--danger-link);
}
</style>
