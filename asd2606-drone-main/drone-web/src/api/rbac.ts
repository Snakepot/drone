import { http } from './request'
import type { UserVO } from './user'

export interface RoleVO {
  id: number
  code: string
  name: string
  description?: string
  /** 1 启用，0 禁用 */
  status: number
  createTime?: string
  updateTime?: string
}

export interface PermissionVO {
  id: number
  code: string
  name: string
  type: string
  parentId: number
  sort: number
}

export interface RoleSaveParam {
  /** 不传为新增，传了为更新 */
  id?: number
  /** 仅新增时生效，更新时不改编码 */
  code?: string
  name: string
  description?: string
  status?: number
}

export function fetchRoles() {
  return http.get<RoleVO[]>('/system/role/list')
}

export function saveRole(data: RoleSaveParam) {
  return http.post<void>('/system/role/save', data)
}

/** 查询角色已勾选的权限 ID */
export function fetchRolePermissionIds(roleId: number) {
  return http.get<number[]>(`/system/role/${roleId}/permission-ids`)
}

/** 覆盖式保存角色的权限 */
export function grantRolePermissions(roleId: number, permissionIds: number[]) {
  return http.post<void>('/system/role/grant', { roleId, permissionIds })
}

export function fetchPermissions() {
  return http.get<PermissionVO[]>('/system/permission/list')
}

/** 员工列表（授权用），与员工管理页的 /user/list 是两个入口，权限码也不同 */
export function fetchGrantUsers() {
  return http.get<UserVO[]>('/system/user/list')
}

/** 查询员工已勾选的角色 ID */
export function fetchUserRoleIds(userId: number) {
  return http.get<number[]>(`/system/user/${userId}/role-ids`)
}

/** 覆盖式保存员工的角色 */
export function grantUserRoles(userId: number, roleIds: number[]) {
  return http.post<void>('/system/user/grant', { userId, roleIds })
}
