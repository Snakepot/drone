<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import {
  fetchGrantUsers,
  fetchPermissions,
  fetchRolePermissionIds,
  fetchRoles,
  fetchUserRoleIds,
  grantRolePermissions,
  grantUserRoles,
  saveRole,
  type PermissionVO,
  type RoleSaveParam,
  type RoleVO,
} from '@/api/rbac'
import type { UserVO } from '@/api/user'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()

type TabKey = 'role' | 'permission' | 'user'

const tabs: { key: TabKey; label: string }[] = [
  { key: 'role', label: '角色管理' },
  { key: 'permission', label: '权限管理' },
  { key: 'user', label: '员工授权' },
]

const active = ref<TabKey>('role')
const loading = ref(false)

const roles = ref<RoleVO[]>([])
const permissions = ref<PermissionVO[]>([])
const users = ref<UserVO[]>([])

onMounted(async () => {
  loading.value = true
  try {
    // 三个 Tab 的数据量都很小，一次取完，切 Tab 时不用再等
    const [roleList, permissionList, userList] = await Promise.all([
      fetchRoles(),
      fetchPermissions(),
      fetchGrantUsers(),
    ])
    roles.value = roleList
    permissions.value = permissionList
    users.value = userList
  } finally {
    loading.value = false
  }
})

/** 权限点按编码前缀分组，界面上一块块展示，便于勾选 */
const moduleLabels: Record<string, string> = {
  staff: '员工模块',
  device: '设备模块',
  system: '系统设置',
}

const permissionGroups = computed(() => {
  const groups = new Map<string, PermissionVO[]>()
  for (const item of permissions.value) {
    const module = item.code.split(':')[0] ?? 'other'
    if (!groups.has(module)) groups.set(module, [])
    groups.get(module)!.push(item)
  }
  return [...groups.entries()].map(([module, items]) => ({
    module,
    label: moduleLabels[module] ?? module,
    items,
  }))
})

// ---------- 角色新增 / 编辑 ----------
const roleDialogVisible = ref(false)
const roleSaving = ref(false)
const roleForm = ref<RoleSaveParam>({ name: '', code: '', description: '', status: 1 })

function openRoleDialog(role?: RoleVO) {
  roleForm.value = role
    ? { id: role.id, name: role.name, description: role.description, status: role.status }
    : { name: '', code: '', description: '', status: 1 }
  roleDialogVisible.value = true
}

async function submitRole() {
  if (!roleForm.value.name.trim()) {
    ElMessage.warning('请填写角色名称')
    return
  }
  if (!roleForm.value.id && !roleForm.value.code?.trim()) {
    ElMessage.warning('请填写角色编码')
    return
  }

  roleSaving.value = true
  try {
    await saveRole(roleForm.value)
    ElMessage.success('保存成功')
    roleDialogVisible.value = false
    roles.value = await fetchRoles()
  } finally {
    roleSaving.value = false
  }
}

// ---------- 给角色配权限 ----------
const grantDialogVisible = ref(false)
const granting = ref(false)
const grantRoleTarget = ref<RoleVO | null>(null)
const checkedPermissionIds = ref<number[]>([])

async function openGrantDialog(role: RoleVO) {
  grantRoleTarget.value = role
  checkedPermissionIds.value = await fetchRolePermissionIds(role.id)
  grantDialogVisible.value = true
}

async function submitGrant() {
  if (!grantRoleTarget.value) return

  granting.value = true
  try {
    await grantRolePermissions(grantRoleTarget.value.id, checkedPermissionIds.value)
    ElMessage.success('授权成功')
    grantDialogVisible.value = false
  } finally {
    granting.value = false
  }
}

// ---------- 给员工配角色 ----------
const userDialogVisible = ref(false)
const userGranting = ref(false)
const grantUserTarget = ref<UserVO | null>(null)
const checkedRoleIds = ref<number[]>([])

async function openUserDialog(user: UserVO) {
  grantUserTarget.value = user
  checkedRoleIds.value = await fetchUserRoleIds(user.id)
  userDialogVisible.value = true
}

async function submitUserGrant() {
  if (!grantUserTarget.value) return

  userGranting.value = true
  try {
    await grantUserRoles(grantUserTarget.value.id, checkedRoleIds.value)
    ElMessage.success('授权成功')
    userDialogVisible.value = false
  } finally {
    userGranting.value = false
  }
}
</script>

<template>
  <div>
    <!-- ============ Tab 切换 ============ -->
    <div class="tabs">
      <button
        v-for="tab in tabs"
        :key="tab.key"
        class="tabs__item"
        :class="{ 'is-active': active === tab.key }"
        type="button"
        @click="active = tab.key"
      >
        {{ tab.label }}
      </button>
    </div>

    <!-- ============ 角色管理 ============ -->
    <div v-show="active === 'role'" class="card">
      <div class="card__head">
        <div class="card__title">
          角色列表
          <small>角色决定「能做什么」；权限不直接给用户，而是通过角色间接赋予</small>
        </div>
        <div class="card__tools">
          <button
            v-if="auth.hasPermission('system:role:save')"
            class="btn btn--primary"
            type="button"
            @click="openRoleDialog()"
          >
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round">
              <path d="M12 5v14M5 12h14" />
            </svg>
            新增角色
          </button>
        </div>
      </div>

      <div class="table-wrap">
        <table>
          <thead>
            <tr>
              <th>角色编码</th>
              <th>角色名称</th>
              <th>说明</th>
              <th>状态</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="role in roles" :key="role.id">
              <td class="code">{{ role.code }}</td>
              <td class="strong">{{ role.name }}</td>
              <td>{{ role.description || '—' }}</td>
              <td>
                <span class="tag" :class="role.status === 1 ? 'tag--green' : 'tag--red'">
                  {{ role.status === 1 ? '启用' : '禁用' }}
                </span>
              </td>
              <td>
                <div class="ops">
                  <span
                    v-if="auth.hasPermission('system:role:save')"
                    class="op"
                    @click="openRoleDialog(role)"
                  >
                    编辑
                  </span>
                  <!-- 管理员角色不允许调整权限，服务端也会再挡一次 -->
                  <span
                    v-if="auth.hasPermission('system:role:grant') && role.code !== 'admin'"
                    class="op"
                    @click="openGrantDialog(role)"
                  >
                    配置权限
                  </span>
                  <span v-else-if="role.code === 'admin'" class="op is-muted">权限固定</span>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <!-- ============ 权限管理 ============ -->
    <div v-show="active === 'permission'" class="card">
      <div class="card__head">
        <div class="card__title">
          权限点清单
          <small>权限点与接口一一对应，由代码定义；这里只读展示，分配请到「角色管理 → 配置权限」</small>
        </div>
      </div>

      <div class="table-wrap">
        <table>
          <thead>
            <tr>
              <th>权限编码</th>
              <th>权限名称</th>
              <th>所属模块</th>
              <th>类型</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="permission in permissions" :key="permission.id">
              <td class="code">{{ permission.code }}</td>
              <td class="strong">{{ permission.name }}</td>
              <td>
                <span class="tag tag--cyan">{{ moduleLabels[permission.code.split(':')[0] ?? ''] ?? '其他' }}</span>
              </td>
              <td>{{ permission.type }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <!-- ============ 员工授权 ============ -->
    <div v-show="active === 'user'" class="card">
      <div class="card__head">
        <div class="card__title">
          员工授权
          <small>给员工分配角色；授权采用覆盖式保存，提交后立即生效（对方无需重新登录）</small>
        </div>
      </div>

      <div class="table-wrap">
        <table>
          <thead>
            <tr>
              <th>姓名</th>
              <th>登录账号</th>
              <th>手机号</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="user in users" :key="user.id">
              <td class="strong">{{ user.name }}</td>
              <td class="code">{{ user.username }}</td>
              <td>{{ user.phone || '—' }}</td>
              <td>
                <div class="ops">
                  <template v-if="user.id === auth.user?.id">
                    <span class="op is-muted">当前登录账号</span>
                  </template>
                  <template v-else-if="auth.hasPermission('system:user:grant')">
                    <span class="op" @click="openUserDialog(user)">分配角色</span>
                  </template>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <!-- ============ 弹层：角色编辑 ============ -->
    <el-dialog
      v-model="roleDialogVisible"
      :title="roleForm.id ? '编辑角色' : '新增角色'"
      width="460px"
      align-center
    >
      <div class="form">
        <label class="form__row">
          <span class="form__label">角色编码</span>
          <input
            v-model="roleForm.code"
            class="form__input"
            type="text"
            :disabled="!!roleForm.id"
            placeholder="如 pilot，代码里会引用它"
          />
        </label>
        <p v-if="roleForm.id" class="form__hint">编码会被代码引用，创建后不可修改</p>

        <label class="form__row">
          <span class="form__label">角色名称</span>
          <input v-model="roleForm.name" class="form__input" type="text" placeholder="如 飞手" />
        </label>

        <label class="form__row">
          <span class="form__label">说明</span>
          <input v-model="roleForm.description" class="form__input" type="text" placeholder="选填" />
        </label>

        <label class="form__row">
          <span class="form__label">状态</span>
          <span class="form__switch">
            <button
              class="switch"
              :class="{ 'is-on': roleForm.status === 1 }"
              type="button"
              @click="roleForm.status = roleForm.status === 1 ? 0 : 1"
            >
              <i></i>
            </button>
            <span class="form__switchText">{{ roleForm.status === 1 ? '启用' : '禁用' }}</span>
          </span>
        </label>
      </div>

      <template #footer>
        <button class="btn btn--ghost" type="button" @click="roleDialogVisible = false">取消</button>
        <button class="btn btn--primary" type="button" :disabled="roleSaving" @click="submitRole">
          {{ roleSaving ? '保存中' : '保存' }}
        </button>
      </template>
    </el-dialog>

    <!-- ============ 弹层：角色配置权限 ============ -->
    <el-dialog
      v-model="grantDialogVisible"
      :title="`配置权限 · ${grantRoleTarget?.name ?? ''}`"
      width="520px"
      align-center
    >
      <p class="dialog__tip">
        勾选后点击保存，采用覆盖式提交；角色下的员工下次请求就会拿到新权限，无需重新登录。
      </p>

      <div class="perm">
        <div v-for="group in permissionGroups" :key="group.module" class="perm__group">
          <div class="perm__title">{{ group.label }}</div>
          <el-checkbox-group v-model="checkedPermissionIds" class="perm__items">
            <el-checkbox v-for="item in group.items" :key="item.id" :value="item.id">
              <span class="perm__name">{{ item.name }}</span>
              <span class="perm__code">{{ item.code }}</span>
            </el-checkbox>
          </el-checkbox-group>
        </div>
      </div>

      <template #footer>
        <button class="btn btn--ghost" type="button" @click="grantDialogVisible = false">取消</button>
        <button class="btn btn--primary" type="button" :disabled="granting" @click="submitGrant">
          {{ granting ? '保存中' : '保存' }}
        </button>
      </template>
    </el-dialog>

    <!-- ============ 弹层：员工分配角色 ============ -->
    <el-dialog
      v-model="userDialogVisible"
      :title="`分配角色 · ${grantUserTarget?.name ?? ''}`"
      width="460px"
      align-center
    >
      <p class="dialog__tip">一个员工可以同时拥有多个角色，权限取并集。</p>

      <el-checkbox-group v-model="checkedRoleIds" class="perm__items">
        <el-checkbox v-for="role in roles" :key="role.id" :value="role.id">
          <span class="perm__name">{{ role.name }}</span>
          <span class="perm__code">{{ role.code }}</span>
        </el-checkbox>
      </el-checkbox-group>

      <template #footer>
        <button class="btn btn--ghost" type="button" @click="userDialogVisible = false">取消</button>
        <button class="btn btn--primary" type="button" :disabled="userGranting" @click="submitUserGrant">
          {{ userGranting ? '保存中' : '保存' }}
        </button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
/* ---------- Tab ---------- */
.tabs {
  display: flex;
  gap: 8px;
  margin-bottom: 16px;
}

.tabs__item {
  padding: 9px 18px;
  border-radius: 10px;
  font-family: inherit;
  font-size: 13.5px;
  color: var(--text-2);
  background: var(--ghost-bg);
  border: 1px solid var(--line);
  cursor: pointer;
  transition: all 0.22s;
}

.tabs__item:hover {
  color: var(--cyan);
  border-color: var(--accent-hover);
}

.tabs__item.is-active {
  color: #03121f;
  font-weight: 600;
  background: linear-gradient(120deg, var(--cyan), var(--blue) 70%);
  border-color: transparent;
  box-shadow: 0 6px 18px rgba(34, 211, 238, 0.28);
}

.op.is-muted {
  color: var(--muted);
  cursor: default;
}

/* ---------- 弹层表单 ---------- */
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

.form__hint {
  margin: -8px 0 0 88px;
  font-size: 12px;
  color: var(--muted);
}

.form__switch {
  display: flex;
  align-items: center;
  gap: 9px;
}

.form__switchText {
  font-size: 13px;
  color: var(--text-2);
}

/* 自绘开关，避免引入更多组件样式 */
.switch {
  position: relative;
  width: 40px;
  height: 22px;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.12);
  border: 1px solid var(--line-strong);
  cursor: pointer;
  transition: background 0.22s, border-color 0.22s;
}

.switch i {
  position: absolute;
  top: 2px;
  left: 2px;
  width: 16px;
  height: 16px;
  border-radius: 50%;
  background: var(--muted);
  transition: transform 0.22s, background 0.22s;
}

.switch.is-on {
  background: rgba(34, 211, 238, 0.25);
  border-color: var(--accent-hover);
}

.switch.is-on i {
  transform: translateX(18px);
  background: var(--cyan);
  box-shadow: 0 0 8px var(--cyan);
}

/* ---------- 授权弹层 ---------- */
.dialog__tip {
  margin-bottom: 16px;
  font-size: 12.5px;
  line-height: 1.8;
  color: var(--muted);
}

.perm {
  max-height: 380px;
  overflow-y: auto;
}

.perm__group + .perm__group {
  margin-top: 16px;
}

.perm__title {
  margin-bottom: 8px;
  font-size: 12px;
  letter-spacing: 1px;
  color: var(--muted);
}

.perm__items {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.perm__name {
  margin-right: 8px;
}

.perm__code {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 12px;
  color: var(--muted);
}
</style>
