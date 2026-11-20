import { http } from './request'

/** 用户信息，与后端 UserVO 对应（飞手端只用到姓名和角色） */
export interface UserVO {
  id: number
  username: string
  name?: string
  /** 在线状态：idle 空闲 / busy 作业中 / grounded 已停飞 */
  state?: string
  roles?: string[]
  permissions?: string[]
}

/** 登录参数 */
export interface UserLoginParam {
  username: string
  password: string
}

/** 登录响应，与后端 UserLoginVO 对应 */
export interface UserLoginVO {
  token: string
  user: UserVO
}

/**
 * 登录。
 *
 * <p>注意这里**不是** `/api/user/login` 这种完整路径 ——
 * baseUrl 已经在 request.ts 里拼好了，这里只写业务路径，
 * 和后台管理端的写法保持一致，免得两边路径规则不一样。
 */
export function login(param: UserLoginParam) {
  return http.post<UserLoginVO>('/user/login', { ...param })
}

/**
 * 查询当前登录用户。
 *
 * <p>姓名这类信息不落地到本地存储，每次进「我的任务」页现取一次，
 * 好处是管理员改过资料后前端立刻能看到最新的。
 */
export function fetchCurrentUser() {
  return http.get<UserVO>('/user/me')
}

/** 退出登录。服务端只记一条审计日志，真正让本地失效的是清掉 token */
export function logout() {
  return http.post<void>('/user/logout')
}
