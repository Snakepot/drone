/**
 * 网络请求封装。
 *
 * <h2>为什么不用 axios</h2>
 * 小程序运行环境里没有 XMLHttpRequest，axios 跑不起来，必须用 uni-app 的
 * `uni.request`。所以这个文件是「照后台管理端的 request.ts 再实现一遍」——
 * 拦截器的语义（统一带 token、统一解包、统一弹错、401 回登录页）保持一致，
 * 但实现手段换成了小程序能用的。
 *
 * <h2>后端响应长什么样</h2>
 * 统一是 `{ state, message, data }` 三个字段（后端 JsonResult），
 * 所以业务成功与否要看 `state`，**不能只看 HTTP 状态码**：
 * 业务失败（如「用户名或密码错误」）返回的是 HTTP 200 + state 500。
 */
import { BASE_URL, TIMEOUT } from '@/config'
import { clearToken, getToken } from '@/utils/auth'

/** 与后端 JsonResult 字段一一对应 */
export interface ApiResult<T = unknown> {
  state: number
  message: string
  data: T
}

/** 分页结果，与后端 PageResult 对应 */
export interface PageResult<T> {
  list: T[]
  total: number
  page: number
  pageSize: number
}

/** 与后端 JsonResult.SUCCESS 保持一致；0 是历史兼容值 */
const SUCCESS_CODES = [0, 200]

/** 登录失效的状态码，HTTP 401 与响应体里的 401 都要认 */
const UNAUTHORIZED = 401

interface RequestOptions {
  url: string
  method?: 'GET' | 'POST'
  /** POST 的请求体 */
  data?: Record<string, unknown>
  /** GET 的查询参数；值为空字符串/undefined/null 的键会被丢掉 */
  params?: Record<string, unknown>
}

/** 上传的超时（毫秒）。比普通请求宽松得多，理由见 {@link uploadFile} */
const UPLOAD_TIMEOUT = 60000

interface UploadOptions {
  url: string
  /** 本地临时文件路径（`uni.chooseImage` 返回的那种） */
  filePath: string
  /** 表单字段名，默认 `file`，与后端 `@RequestParam("file")` 对应 */
  name?: string
  /** 随文件一起提交的表单字段（比如 taskId） */
  formData?: Record<string, unknown>
}

function buildQuery(params?: Record<string, unknown>): string {
  if (!params) return ''

  const pairs = Object.entries(params)
    // 空值不往 URL 上拼：后台端传的都是 `status=` 这种空串表示「不筛」，
    // 拼上去后端虽然也能处理，但 URL 会变脏，日志里不好看
    .filter(([, value]) => value !== undefined && value !== null && value !== '')
    .map(([key, value]) => `${encodeURIComponent(key)}=${encodeURIComponent(String(value))}`)

  return pairs.length ? `?${pairs.join('&')}` : ''
}

function toast(message: string) {
  uni.showToast({ title: message, icon: 'none', duration: 2000 })
}

/**
 * 跳回登录页。
 *
 * <p>两个防重复措施，因为一屏上可能同时有好几个请求一起 401：
 * 一是已经在登录页就不再跳，二是用一个标志位挡住并发跳转。
 */
let redirecting = false

function backToLogin() {
  clearToken()

  const pages = getCurrentPages()
  const current = pages.length ? pages[pages.length - 1].route : ''
  if (current === 'pages/login/login' || redirecting) return

  redirecting = true
  uni.reLaunch({
    url: '/pages/login/login',
    complete: () => {
      redirecting = false
    },
  })
}

/**
 * 发一个请求并解包。
 *
 * @returns 成功时 resolve 出 `data` 部分；失败时 reject，**错误提示已经弹过了**，
 *          调用方只需要负责收尾（比如关掉 loading），不用再弹一次
 */
export function request<T>(options: RequestOptions): Promise<T> {
  return new Promise<T>((resolve, reject) => {
    const token = getToken()

    uni.request({
      url: BASE_URL + options.url + buildQuery(options.params),
      method: options.method || 'GET',
      data: options.data,
      timeout: TIMEOUT,
      header: {
        'Content-Type': 'application/json',
        // 有 token 就带上，格式与后端拦截器约定的 `Bearer xxx` 一致
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
      },
      success: (res) => {
        if (res.statusCode === UNAUTHORIZED) {
          toast('登录状态已失效，请重新登录')
          backToLogin()
          reject(new Error('unauthorized'))
          return
        }

        if (res.statusCode !== 200) {
          toast(`请求失败（HTTP ${res.statusCode}）`)
          reject(new Error(`HTTP ${res.statusCode}`))
          return
        }

        const payload = res.data as ApiResult<T> | undefined
        if (!payload || typeof payload.state !== 'number') {
          toast('响应格式不正确')
          reject(new Error('bad response'))
          return
        }

        // 业务层的「未登录」（HTTP 200 + state 401）也按登录失效处理
        if (payload.state === UNAUTHORIZED) {
          toast(payload.message || '登录状态已失效，请重新登录')
          backToLogin()
          reject(new Error('unauthorized'))
          return
        }

        if (!SUCCESS_CODES.includes(payload.state)) {
          toast(payload.message || '请求失败')
          reject(new Error(payload.message))
          return
        }

        resolve(payload.data)
      },
      fail: () => {
        // 连不上后端最常见的原因就是服务没起来，提示里直接写出来，省得排查半天
        toast('网络异常，请检查后端服务是否已启动')
        reject(new Error('network error'))
      },
    })
  })
}

export const http = {
  get: <T>(url: string, params?: Record<string, unknown>) => request<T>({ url, params }),
  post: <T>(url: string, data?: Record<string, unknown>) => request<T>({ url, method: 'POST', data }),
}

/**
 * 上传文件（multipart）。
 *
 * <h2>为什么要单独一个函数</h2>
 * `uni.request` 发不了文件，multipart 要走 `uni.uploadFile`。
 * 但两者**错误处理的语义必须一致**（带 token、401 回登录页、业务错误统一弹提示），
 * 所以这段逻辑留在这个文件里，和 {@link request} 挨着 ——
 * 抄到别处去就会变成第二套实现，将来改一处漏一处。
 *
 * <h2>两个和小请求不一样的地方</h2>
 * <ol>
 *   <li><b>响应体是字符串</b>，要自己 `JSON.parse`，不能像 `uni.request` 那样
 *       直接拿到对象（平台差异）</li>
 *   <li><b>超时放宽到 60 秒</b>：图片是几 MB 的原图，用请求那 10 秒的超时
 *       会在网络稍差时必失败。小程序的 `wx.uploadFile` 不支持自定义超时，
 *       那个参数只在 App 与 H5 生效 —— 传了也无害</li>
 * </ol>
 *
 * @returns 成功时 resolve 出 `data` 部分；失败时 reject，**提示已经弹过了**
 */
export function uploadFile<T>(options: UploadOptions): Promise<T> {
  return new Promise<T>((resolve, reject) => {
    const token = getToken()

    uni.uploadFile({
      url: BASE_URL + options.url,
      filePath: options.filePath,
      name: options.name || 'file',
      formData: options.formData,
      timeout: UPLOAD_TIMEOUT,
      header: {
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
      },
      success: (res) => {
        if (res.statusCode === UNAUTHORIZED) {
          toast('登录状态已失效，请重新登录')
          backToLogin()
          reject(new Error('unauthorized'))
          return
        }

        if (res.statusCode !== 200) {
          toast(`上传失败（HTTP ${res.statusCode}）`)
          reject(new Error(`HTTP ${res.statusCode}`))
          return
        }

        // 平台差异：这里的 data 是字符串，要自己解析
        let payload: ApiResult<T> | undefined
        try {
          payload = JSON.parse(res.data) as ApiResult<T>
        } catch {
          toast('响应格式不正确')
          reject(new Error('bad response'))
          return
        }

        if (!payload || typeof payload.state !== 'number') {
          toast('响应格式不正确')
          reject(new Error('bad response'))
          return
        }

        if (payload.state === UNAUTHORIZED) {
          toast(payload.message || '登录状态已失效，请重新登录')
          backToLogin()
          reject(new Error('unauthorized'))
          return
        }

        if (!SUCCESS_CODES.includes(payload.state)) {
          // 后端把「扩展名不在白名单」「超过大小上限」这类原因写在 message 里，
          // 直接显示给飞手 —— 他能据此决定换张图还是压缩一下
          toast(payload.message || '上传失败')
          reject(new Error(payload.message))
          return
        }

        resolve(payload.data)
      },
      fail: () => {
        toast('上传失败，请检查网络后重试')
        reject(new Error('network error'))
      },
    })
  })
}
