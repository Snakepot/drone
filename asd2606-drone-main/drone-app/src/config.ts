/**
 * 接口地址。
 *
 * <h2>为什么两端不一样</h2>
 * 后端**没有配 CORS**，所以两个端的策略必须分开：
 *
 * | 端 | 地址 | 原因 |
 * |---|---|---|
 * | H5 | `/api` | 跑在浏览器里，直接请求后端会被 CORS 挡下。走 Vite devServer 的代理（同源），
 * |    |        | 和后台管理端 drone-web 是同一个套路 |
 * | 微信小程序 | `http://localhost:8080` | 小程序没有 CORS 概念，直连即可。
 * |            |                          | 需要在微信开发者工具里勾选「不校验合法域名」 |
 *
 * <h2>换环境改这里</h2>
 * 用真机调试时 `localhost` 指的是手机自己，要换成开发机的**局域网 IP**
 * （如 `http://192.168.1.10:8080`）。改这一行即可，别处没有硬编码地址。
 *
 * `// #ifdef` 是 uni-app 的条件编译：编译到某个平台时，其他分支的代码会被整段去掉，
 * 所以小程序产物里不会残留 H5 的地址，反之亦然。
 */
let baseUrl = ''

// #ifdef H5
baseUrl = '/api'
// #endif

// #ifdef MP-WEIXIN
baseUrl = 'http://localhost:8080'
// #endif

export const BASE_URL = baseUrl

/**
 * WebSocket 的基地址（通知推送用）。
 *
 * <p>与上面的 HTTP 地址是两个独立配置，因为两端规则不一样：
 *
 * | 端 | 地址 | 原因 |
 * |---|---|---|
 * | H5 | 从当前页面地址推导（`ws://当前host/api`） | 走 Vite devServer 代理（`vite.config.ts` 里给 `/api` 开了 `ws: true`），
 * |    |                                        | 这样和 REST 同一个来源，不用额外操心跨域与端口 |
 * | 小程序 | `ws://localhost:8080` | 小程序没有代理，直连后端。
 * |        |                        | 生产必须是 `wss://` 且域名要在公众平台配「socket 合法域名」 |
 *
 * <p>**真机调试时 `localhost` 指手机自己**，要改成开发机的局域网 IP，和 `BASE_URL` 一起改。
 */
let wsBaseUrl = ''

// #ifdef H5
// 直接从浏览器地址推导：换端口、换域名都不用改代码
wsBaseUrl = `${location.protocol === 'https:' ? 'wss' : 'ws'}://${location.host}/api`
// #endif

// #ifdef MP-WEIXIN
wsBaseUrl = 'ws://localhost:8080'
// #endif

export const WS_BASE_URL = wsBaseUrl

/** 任务通知的 WebSocket 路径，与后端 TaskWsConfig.WS_PATH 对应 */
export const WS_TASK_PATH = '/ws/task'

/** 请求超时时间（毫秒），与后台管理端保持一致 */
export const TIMEOUT = 10000
