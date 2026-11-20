import axios, { type AxiosRequestConfig, type AxiosResponse } from 'axios'
import { ref } from 'vue'

/**
 * 大屏的「登录已过期」标记。
 *
 * <p>普通页面 401 会跳登录页 —— 但**大屏不能跳**：它挂在值班室，
 * 跳走之后就再也没人把它切回来了，第二天早上看到的是一块停在登录页的屏。
 * 所以这里只置一个标记，由 {@code ScreenView} 就地显示大字提示
 * （并把已显示的数据留在底下，屏不至于全黑）。见 `docs/数字大屏方案.md` 4.5 ④。
 */
export const screenAuthExpired = ref(false)

/** 当前是不是在大屏路由上。拦截器在 router 之外，所以直接看地址栏 */
function isScreenRoute(): boolean {
  return window.location.pathname.startsWith('/screen')
}

/** 后端统一响应结构，字段名与 JsonResult 保持一致 */
export interface ApiResult<T = unknown> {
  state: number
  message: string
  data: T
}

/** 与后端 JsonResult.SUCCESS 保持一致 */
const SUCCESS_CODES = [0, 200]

const service = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL,
  timeout: 10000,
})

service.interceptors.request.use((config) => {
  const token = localStorage.getItem('token')
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

function isApiResult(value: unknown): value is ApiResult {
  return typeof value === 'object' && value !== null && 'state' in value && 'message' in value
}

service.interceptors.response.use(
  (response: AxiosResponse) => {
    const payload: unknown = response.data

    if (isApiResult(payload) && !SUCCESS_CODES.includes(payload.state)) {
      const message = payload.message || '请求失败'
      ElMessage.error(message)
      return Promise.reject(new Error(message))
    }

    return response
  },
  (error) => {
    const status = error.response?.status
    const message =
      error.response?.data?.message ||
      (status ? `请求失败（HTTP ${status}）` : '网络异常，请检查后端服务是否已启动')

    if (status === 401) {
      // Token 失效，清掉本地登录痕迹
      localStorage.removeItem('token')
      localStorage.removeItem('user')

      if (isScreenRoute()) {
        // 大屏：**不跳走**，就地提示（跳走了就再也没人把它切回来）
        screenAuthExpired.value = true
        return Promise.reject(error)
      }

      if (window.location.pathname !== '/login') {
        window.location.href = '/login'
        return Promise.reject(error)
      }
    }

    ElMessage.error(message)
    return Promise.reject(error)
  },
)

async function request<T>(config: AxiosRequestConfig): Promise<T> {
  const response = await service.request<ApiResult<T>>(config)
  const payload: unknown = response.data

  // 非统一结构（文件流等）直接返回原始响应体
  return (isApiResult(payload) ? payload.data : payload) as T
}

export const http = {
  get: <T>(url: string, params?: object) => request<T>({ url, method: 'get', params }),
  // 允许覆盖单次请求的配置（例如给慢接口单独放宽超时）；url / method / data 仍以本方法为准
  post: <T>(url: string, data?: object, config?: AxiosRequestConfig) =>
    request<T>({ ...config, url, method: 'post', data }),
  put: <T>(url: string, data?: object) => request<T>({ url, method: 'put', data }),
  delete: <T>(url: string, params?: object) => request<T>({ url, method: 'delete', params }),

  /**
   * 下载文件，返回二进制内容。
   *
   * <p>不能复用上面几个方法 —— 它们会把响应体当 JSON 解包，
   * 而文件流是二进制，硬解会直接报错。这里单独走 responseType: 'blob'。
   */
  download: async (url: string, params?: object): Promise<Blob> => {
    const response = await service.get<Blob>(url, { params, responseType: 'blob' })
    return response.data
  },
}

export default service

/** 分页结果，各模块的列表接口通用 */
export interface PageResult<T> {
  list: T[]
  total: number
  page: number
  pageSize: number
}

/** Excel 导入结果，员工与设备模块通用 */
export interface ImportResult {
  total: number
  success: number
  failed: number
  errors: string[]
}
