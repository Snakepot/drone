import { http, type ImportResult, type PageResult } from './request'

// 分页结果与导入结果是各模块通用的，定义在 request 层，
// 这里再导出一份，方便页面只从自己的模块文件里取类型
export type { ImportResult, PageResult }

export interface UserLoginParam {
  username: string
  password: string
}

export interface UserVO {
  id: number
  username: string
  name: string
  email?: string
  phone?: string
  age?: number
  gender?: string
  status?: string
  state?: string
  /** 角色编码集合，如 ['admin'] */
  roles?: string[]
  /** 权限编码集合，如 ['staff:add', 'device:list'] */
  permissions?: string[]
  createTime?: string
}

export interface UserLoginVO {
  token: string
  user: UserVO
}

export function login(data: UserLoginParam) {
  return http.post<UserLoginVO>('/user/login', data)
}

/** 查询当前登录用户。数据以服务端为准，比登录时的快照更新 */
export function fetchCurrentUser() {
  return http.get<UserVO>('/user/me')
}

/** 退出登录。服务端只做审计记录，本地状态由调用方清理 */
export function logout() {
  return http.post<void>('/user/logout')
}

// ---------------------------------------------------------------------------
// 员工检索、导入导出
// ---------------------------------------------------------------------------

/** 员工查询条件，与后端 UserQueryParam 对应 */
export interface UserQuery {
  keyword?: string
  roleCode?: string
  status?: string
  page?: number
  pageSize?: number
}

/** 分页结果见 request.ts 的 PageResult */

/** 导入结果见 request.ts 的 ImportResult */

/** 角色选项，供筛选下拉使用 */
export interface RoleOption {
  id: number
  code: string
  name: string
}

/** 分页查询员工（支持关键字 / 角色 / 状态筛选） */
export function fetchUserPage(params: UserQuery) {
  return http.get<PageResult<UserVO>>('/user/list', params)
}

/** 角色选项 */
export function fetchRoleOptions() {
  return http.get<RoleOption[]>('/user/role-options')
}

/** 下载导入模版，返回文件流 */
export function downloadImportTemplate() {
  return http.download('/user/import/template')
}

/** 导入员工 */
export function importUsers(file: File) {
  const formData = new FormData()
  formData.append('file', file)
  return http.post<ImportResult>('/user/import', formData)
}

/** 按条件导出员工（条件与列表一致、忽略分页），返回文件流 */
export function exportUsers(params: UserQuery) {
  return http.download('/user/export', params)
}

// ---------------------------------------------------------------------------
// 员工新增、编辑、启停用
// ---------------------------------------------------------------------------

/** 员工新增/编辑参数 */
export interface UserSaveParam {
  /** 不传为新增，传了为编辑 */
  id?: number
  /** 新增时必填；编辑时会被后端忽略 */
  username?: string
  name?: string
  /** 新增时必填；编辑时留空表示不改密码 */
  password?: string
  phone?: string
  email?: string
  age?: number
  gender?: string
  /** 编辑时留空表示不调整角色 */
  roleCode?: string
}

/** 查询员工详情（含角色），供编辑弹窗回显 */
export function fetchUserDetail(id: number) {
  return http.get<UserVO>(`/user/${id}`)
}

/** 新增员工 */
export function addUser(data: UserSaveParam) {
  return http.post<void>('/user/add', data)
}

/** 编辑员工 */
export function updateUser(data: UserSaveParam) {
  return http.post<void>('/user/update', data)
}

/** 启用 / 禁用员工 */
export function toggleUser(id: number, status: string) {
  return http.post<void>('/user/toggle', { id, status })
}
