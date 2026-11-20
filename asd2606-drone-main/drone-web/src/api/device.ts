import { http, type ImportResult, type PageResult } from './request'

export interface DeviceVO {
  id: number
  code: string
  name: string
  model?: string
  type?: string
  purchaseDate?: string
  location?: string
  status?: string
  flightCount?: string
  flightHours?: string
  createTime?: string
}

/** 设备查询条件，与后端 DeviceQueryParam 对应 */
export interface DeviceQuery {
  keyword?: string
  type?: string
  status?: string
  page?: number
  pageSize?: number
}

/** 设备新增/编辑参数 */
export interface DeviceSaveParam {
  /** 不传为新增，传了为编辑 */
  id?: number
  /** 新增时必填；编辑时会被后端忽略 */
  code?: string
  name?: string
  model?: string
  type?: string
  purchaseDate?: string
  location?: string
  status?: string
  flightCount?: string
  flightHours?: string
}

/** 分页查询设备（支持关键字 / 类型 / 状态筛选） */
export function fetchDevicePage(params: DeviceQuery) {
  return http.get<PageResult<DeviceVO>>('/device/list', params)
}

/** 查询设备详情，供编辑弹窗回显 */
export function fetchDeviceDetail(id: number) {
  return http.get<DeviceVO>(`/device/${id}`)
}

/** 新增设备 */
export function addDevice(data: DeviceSaveParam) {
  return http.post<void>('/device/add', data)
}

/** 编辑设备 */
export function updateDevice(data: DeviceSaveParam) {
  return http.post<void>('/device/update', data)
}

/** 下载导入模版，返回文件流 */
export function downloadDeviceTemplate() {
  return http.download('/device/import/template')
}

/** 导入设备 */
export function importDevices(file: File) {
  const formData = new FormData()
  formData.append('file', file)
  return http.post<ImportResult>('/device/import', formData)
}

/** 按条件导出设备（条件与列表一致、忽略分页），返回文件流 */
export function exportDevices(params: DeviceQuery) {
  return http.download('/device/export', params)
}
