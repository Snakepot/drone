<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import {
  addUser,
  downloadImportTemplate,
  exportUsers,
  fetchRoleOptions,
  fetchUserDetail,
  fetchUserPage,
  importUsers,
  toggleUser,
  updateUser,
  type ImportResult,
  type RoleOption,
  type UserQuery,
  type UserSaveParam,
  type UserVO,
} from '@/api/user'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()

const loading = ref(false)
const list = ref<UserVO[]>([])
const total = ref(0)
const roleOptions = ref<RoleOption[]>([])

const query = reactive<UserQuery>({
  keyword: '',
  roleCode: '',
  status: '',
  page: 1,
  pageSize: 10,
})

const totalPages = computed(() =>
  Math.max(1, Math.ceil(total.value / (query.pageSize ?? 10))),
)

/** 当前页码，兜底为 1 —— 免得模板里到处写 ?? 1 */
const currentPage = computed(() => query.page ?? 1)

/** 账号状态：库里 "0" 视为禁用，其余为启用 */
function statusText(status?: string) {
  return status === '0' ? '禁用' : '启用'
}

function statusTone(status?: string) {
  return status === '0' ? 'red' : 'green'
}

const stateMap: Record<string, { label: string; tone: string }> = {
  idle: { label: '空闲', tone: 'grey' },
  busy: { label: '作业中', tone: 'amber' },
  grounded: { label: '已停飞', tone: 'red' },
}

function stateOf(state?: string) {
  return stateMap[state ?? ''] ?? { label: state ?? '—', tone: 'grey' }
}

async function loadData() {
  loading.value = true
  try {
    const result = await fetchUserPage(query)
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
  // 角色下拉与列表可以并行加载，不必等一个再等另一个
  await Promise.all([
    loadData(),
    fetchRoleOptions().then((roles) => {
      roleOptions.value = roles
    }),
  ])
})

function handleSearch() {
  query.page = 1
  loadData()
}

function handleReset() {
  query.keyword = ''
  query.roleCode = ''
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
    const condition: UserQuery = {
      keyword: query.keyword,
      roleCode: query.roleCode,
      status: query.status,
    }
    const blob = await exportUsers(condition)
    if (await saveBlob(blob, '员工列表.xlsx')) {
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
  const blob = await downloadImportTemplate()
  await saveBlob(blob, '员工导入模版.xlsx')
}

async function submitImport() {
  if (!importFile.value) {
    ElMessage.warning('请先选择要导入的文件')
    return
  }

  importing.value = true
  try {
    const result = await importUsers(importFile.value)
    importResult.value = result
    // 导入后刷新列表，让用户立刻看到结果
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

/**
 * 把 Blob 存成本地文件。
 *
 * <p>这里有个容易踩的坑：后端出错时返回的是 JSON 而不是文件，
 * 但 responseType 是 blob，axios 不会报错，前端会「成功」下载到一个
 * 内容是错误信息的 xlsx。所以要检查类型，把错误读出来提示用户。
 *
 * @return 是否真的触发了下载
 */
async function saveBlob(blob: Blob, filename: string): Promise<boolean> {
  if (blob.type.includes('application/json')) {
    const text = await blob.text()
    try {
      const payload = JSON.parse(text)
      ElMessage.error(payload.message || '下载失败')
    } catch {
      ElMessage.error('下载失败')
    }
    return false
  }

  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = filename
  link.click()
  URL.revokeObjectURL(url)
  return true
}

// ---------------------------------------------------------------------------
// 新增 / 编辑
// ---------------------------------------------------------------------------
const formVisible = ref(false)
const formSaving = ref(false)
const isEdit = computed(() => Boolean(form.id))

const form = reactive<UserSaveParam>({
  id: undefined,
  username: '',
  name: '',
  password: '',
  phone: '',
  email: '',
  age: undefined,
  gender: 'male',
  roleCode: '',
})

function resetForm() {
  form.id = undefined
  form.username = ''
  form.name = ''
  form.password = ''
  form.phone = ''
  form.email = ''
  form.age = undefined
  form.gender = 'male'
  form.roleCode = ''
}

function openAddDialog() {
  resetForm()
  formVisible.value = true
}

async function openEditDialog(user: UserVO) {
  resetForm()
  // 列表接口刻意不带角色（避免 N+1），打开弹窗时再按需查这一个用户的详情
  const detail = await fetchUserDetail(user.id)
  form.id = detail.id
  form.username = detail.username
  form.name = detail.name
  form.phone = detail.phone
  form.email = detail.email
  form.age = detail.age
  form.gender = detail.gender ?? 'male'
  form.roleCode = detail.roles?.[0] ?? ''
  formVisible.value = true
}

async function submitForm() {
  if (!form.name?.trim()) {
    ElMessage.warning('请填写姓名')
    return
  }
  if (!isEdit.value && !form.username?.trim()) {
    ElMessage.warning('请填写登录账号')
    return
  }
  if (!isEdit.value && !form.password) {
    ElMessage.warning('请填写初始密码')
    return
  }
  if (!form.roleCode) {
    ElMessage.warning('请选择角色')
    return
  }

  formSaving.value = true
  try {
    if (isEdit.value) {
      await updateUser(form)
    } else {
      await addUser(form)
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

// ---------------------------------------------------------------------------
// 启用 / 禁用
// ---------------------------------------------------------------------------
async function handleToggle(user: UserVO) {
  const disabling = user.status !== '0'
  const action = disabling ? '禁用' : '启用'

  try {
    await ElMessageBox.confirm(
      disabling
        ? `禁用后「${user.name}」将无法登录，确认禁用吗？`
        : `确认启用「${user.name}」的账号吗？`,
      `${action}账号`,
      { confirmButtonText: `确认${action}`, cancelButtonText: '取消', type: 'warning' },
    )
  } catch {
    // 用户点了取消
    return
  }

  try {
    await toggleUser(user.id, disabling ? '0' : '1')
    ElMessage.success(`已${action}`)
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
        <input
          v-model="query.keyword"
          type="text"
          placeholder="搜索姓名 / 账号 / 手机号"
          @keyup.enter="handleSearch"
        />
      </label>

      <select v-model="query.roleCode" class="select">
        <option value="">全部角色</option>
        <option v-for="role in roleOptions" :key="role.id" :value="role.code">{{ role.name }}</option>
      </select>

      <select v-model="query.status" class="select">
        <option value="">全部状态</option>
        <option value="1">启用</option>
        <option value="0">禁用</option>
      </select>

      <button class="btn btn--primary" type="button" @click="handleSearch">查询</button>
      <button class="btn btn--ghost" type="button" @click="handleReset">重置</button>
    </div>

    <!-- ============ 列表 ============ -->
    <div class="card">
      <div class="card__head">
        <div class="card__title">
          员工列表
          <small>共 {{ total }} 条记录，导出会带上当前的筛选条件</small>
        </div>
        <div class="card__tools">
          <button
            v-if="auth.hasPermission('staff:import')"
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
            v-if="auth.hasPermission('staff:export')"
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
            v-if="auth.hasPermission('staff:add')"
            class="btn btn--primary"
            type="button"
            @click="openAddDialog"
          >
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round">
              <path d="M12 5v14M5 12h14" />
            </svg>
            新增员工
          </button>
        </div>
      </div>

      <div class="table-wrap">
        <table>
          <thead>
            <tr>
              <th>姓名</th>
              <th>登录账号</th>
              <th>手机号</th>
              <th>邮箱</th>
              <th>在线状态</th>
              <th>账号状态</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="user in list" :key="user.id">
              <td class="strong">{{ user.name }}</td>
              <td class="code">{{ user.username }}</td>
              <td>{{ user.phone || '—' }}</td>
              <td>{{ user.email || '—' }}</td>
              <td>
                <span class="tag" :class="`tag--${stateOf(user.state).tone}`">
                  {{ stateOf(user.state).label }}
                </span>
              </td>
              <td>
                <span class="tag" :class="`tag--${statusTone(user.status)}`">
                  {{ statusText(user.status) }}
                </span>
              </td>
              <td>
                <div class="ops">
                  <span
                    v-if="auth.hasPermission('staff:edit')"
                    class="op"
                    @click="openEditDialog(user)"
                  >
                    编辑
                  </span>
                  <span
                    v-if="auth.hasPermission('staff:toggle')"
                    class="op"
                    :class="{ 'op--danger': user.status !== '0' }"
                    @click="handleToggle(user)"
                  >
                    {{ user.status === '0' ? '启用' : '禁用' }}
                  </span>
                </div>
              </td>
            </tr>
            <tr v-if="!loading && list.length === 0">
              <td colspan="7" class="empty">没有符合条件的员工</td>
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
    <el-dialog v-model="importVisible" title="导入员工" width="520px" align-center>
      <p class="dialog__tip">
        先下载模版并按表头填写（角色编码填 pilot、maintainer 这类英文编码）。<br />
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
    <el-dialog v-model="formVisible" :title="isEdit ? '编辑员工' : '新增员工'" width="520px" align-center>
      <div class="form">
        <label class="form__row">
          <span class="form__label">登录账号</span>
          <input
            v-model="form.username"
            class="form__input"
            type="text"
            :disabled="isEdit"
            placeholder="手机号，用于登录"
          />
        </label>
        <p v-if="isEdit" class="form__hint">账号是登录凭证，创建后不可修改</p>

        <label class="form__row">
          <span class="form__label">姓名</span>
          <input v-model="form.name" class="form__input" type="text" placeholder="真实姓名" />
        </label>

        <label class="form__row">
          <span class="form__label">密码</span>
          <input
            v-model="form.password"
            class="form__input"
            type="password"
            :placeholder="isEdit ? '留空表示不修改' : '初始密码'"
          />
        </label>

        <div class="form__row">
          <span class="form__label">角色</span>
          <select v-model="form.roleCode" class="form__input form__select">
            <option value="">请选择角色</option>
            <option v-for="role in roleOptions" :key="role.id" :value="role.code">{{ role.name }}</option>
          </select>
        </div>

        <label class="form__row">
          <span class="form__label">手机号</span>
          <input v-model="form.phone" class="form__input" type="text" placeholder="选填" />
        </label>

        <label class="form__row">
          <span class="form__label">邮箱</span>
          <input v-model="form.email" class="form__input" type="text" placeholder="选填" />
        </label>

        <label class="form__row">
          <span class="form__label">年龄</span>
          <input v-model.number="form.age" class="form__input" type="number" placeholder="选填" />
        </label>

        <div class="form__row">
          <span class="form__label">性别</span>
          <select v-model="form.gender" class="form__input form__select">
            <option value="male">男</option>
            <option value="female">女</option>
          </select>
        </div>
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
  /* 自绘下拉箭头，避免各浏览器默认样式不一致 */
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

/* 下拉选项由浏览器渲染，深色主题下需要显式指定底色，否则是白底黑字 */
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

/* 下拉箭头自绘，否则各浏览器默认样式差异很大 */
.form__select {
  appearance: none;
  cursor: pointer;
  background-image: linear-gradient(45deg, transparent 50%, var(--muted) 50%),
    linear-gradient(135deg, var(--muted) 50%, transparent 50%);
  background-position: calc(100% - 16px) 15px, calc(100% - 11px) 15px;
  background-size: 5px 5px, 5px 5px;
  background-repeat: no-repeat;
}

/* 下拉选项由浏览器渲染，深色主题下必须显式指定底色，否则是白底黑字 */
.form__select option {
  color: var(--text);
  background-color: #0b1830;
}

.form__hint {
  margin: -8px 0 0 88px;
  font-size: 12px;
  color: var(--muted);
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
