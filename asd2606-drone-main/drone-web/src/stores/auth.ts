import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import {
  fetchCurrentUser as fetchCurrentUserApi,
  login as loginApi,
  logout as logoutApi,
  type UserLoginParam,
  type UserVO,
} from '@/api/user'

/** localStorage 的键名，request.ts 收到 401 时清理的也是它 */
const TOKEN_KEY = 'token'

/**
 * 登录状态。
 *
 * <p>localStorage 里<b>只存 token</b> —— 它是「通行证」。
 * 用户信息（姓名、角色等）不落地，而是登录后、以及每次刷新页面时调 /user/me 现取，
 * 这样资料改过之后前端立刻能反映出来，不用等 Token 过期。
 */
export const useAuthStore = defineStore('auth', () => {
  const token = ref(localStorage.getItem(TOKEN_KEY) ?? '')
  const user = ref<UserVO | null>(null)

  /** 本地是否已登录。只代表「有 token」，Token 是否真的有效由服务端说了算 */
  const isLoggedIn = computed(() => Boolean(token.value))

  /** 展示用名字：优先真实姓名，没有就退回用户名 */
  const displayName = computed(() => user.value?.name || user.value?.username || '')

  /**
   * 是否拥有指定权限。
   *
   * <p>权限来自服务端（登录响应或 /user/me），前端只做「显不显示」的判断。
   * 真正的安全边界在服务端 —— 就算有人改了前端代码让按钮显示出来，
   * 调接口时依然会被拦截器按同一套权限码拦下（403）。
   */
  function hasPermission(code: string) {
    return user.value?.permissions?.includes(code) ?? false
  }

  /** 是否拥有指定角色，如 'admin' */
  function hasRole(code: string) {
    return user.value?.roles?.includes(code) ?? false
  }

  /**
   * 首页路径：按权限挑第一个能访问的页面。
   *
   * <p>不能固定跳 /staff —— 菜单已经按权限隐藏了「员工管理」，
   * 没有 staff:list 的用户一登录却撞上员工页，体验是断的。
   */
  const homePath = computed(() => {
    if (hasPermission('staff:list')) return '/staff'
    if (hasPermission('device:list')) return '/device'
    if (hasPermission('task:list')) return '/task'
    // 告警中心放最后：只有告警权限的账号（很少见）也得有个能落地的地方，
    // 否则登录后直接撞上「暂无可用功能」，而实际上他是有页面的
    if (hasPermission('alert:list')) return '/alert'
    return '/no-access'
  })

  /**
   * 登录：只保存 token。
   * 用户信息不在这里保存，交给 {@link fetchCurrentUser} 去取，保证与数据库一致。
   */
  async function login(param: UserLoginParam) {
    const result = await loginApi(param)
    token.value = result.token
    localStorage.setItem(TOKEN_KEY, result.token)
  }

  /**
   * 拉取当前登录用户信息。
   *
   * <p>若 token 已失效，接口会返回 401，由 axios 响应拦截器统一处理（清 token 并回登录页），
   * 所以调用方不用自己判断登录状态。
   */
  async function fetchCurrentUser() {
    // 已经有用户信息就不重复请求：路由守卫和 App.vue 都会调它，
    // 加这层判断可以避免刷新页面时发两次 /user/me
    if (user.value) return user.value

    user.value = await fetchCurrentUserApi()
    return user.value
  }

  /**
   * 退出登录。
   *
   * <p>先通知服务端（它会记一条审计日志），但<b>不管成功与否都要把本地状态清干净</b> ——
   * 比如 Token 已经过期时调用会返回 401，那种情况更应该清掉。
   * 所以这里用 try/finally 保证清理动作一定执行。
   */
  async function logout() {
    try {
      await logoutApi()
    } catch {
      // 服务端调用失败不影响本地登出，忽略即可
    } finally {
      token.value = ''
      user.value = null
      localStorage.removeItem(TOKEN_KEY)
    }
  }

  return { token, user, isLoggedIn, displayName, hasRole, hasPermission, homePath, login, fetchCurrentUser, logout }
})
