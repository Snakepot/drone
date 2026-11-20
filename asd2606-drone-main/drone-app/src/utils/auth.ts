/**
 * 登录凭证的本地存取。
 *
 * <p>只存 token 一个东西，**不存用户信息** —— 和后台管理端
 * （drone-web/src/stores/auth.ts）是同一套思路：
 * token 是「通行证」，姓名、角色这些每次要用时调 `/user/me` 现取，
 * 这样资料被管理员改过之后，前端立刻能反映出来，不用等 token 过期。
 */
const TOKEN_KEY = 'token'

export function getToken(): string {
  return uni.getStorageSync(TOKEN_KEY) || ''
}

export function setToken(token: string) {
  uni.setStorageSync(TOKEN_KEY, token)
}

export function clearToken() {
  uni.removeStorageSync(TOKEN_KEY)
}

export function isLoggedIn(): boolean {
  return Boolean(getToken())
}
