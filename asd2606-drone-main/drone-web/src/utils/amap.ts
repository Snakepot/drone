/**
 * 高德地图 JS API 的加载器。
 *
 * <h2>为什么用「插 script 标签」而不是装 npm 包</h2>
 * 高德的 JS API 本来就是一个 CDN 上的脚本，官方那个 `@amap/amap-jsapi-loader`
 * 干的事也就是插标签 + 缓存 Promise。少一个依赖、少一份 lock 文件改动，
 * 而且这个文件里能看到完整的加载过程（出问题时好查）。
 *
 * <h2>Key 从哪来</h2>
 * 环境变量 `VITE_AMAP_KEY`（见 `.env.development` 的说明）。
 * **前端只能放「Web 端 Key」**，并且在高德控制台绑定域名 ——
 * 服务端用的那个 Key 绝不能出现在这里（见 `docs/地图接入方案.md` 2.4）。
 *
 * <h2>没配 Key 会怎样</h2>
 * 不抛异常、不白屏，而是让调用方拿到一个明确的失败，页面上显示
 * 「未配置高德 Key」的说明。**比一个空白的灰框强得多** ——
 * 后者会让人以为是自己网断了。
 */
const AMAP_KEY = import.meta.env.VITE_AMAP_KEY as string | undefined
const AMAP_SECURITY_CODE = import.meta.env.VITE_AMAP_SECURITY_CODE as string | undefined

/** 有没有配 Key —— 组件用它决定是画地图还是显示说明 */
export function hasAmapKey(): boolean {
  return Boolean(AMAP_KEY)
}

/** 加载中的 Promise（缓存住，多次调用只加载一次脚本） */
let loading: Promise<unknown> | null = null

/**
 * 加载高德 JS API，返回全局的 `AMap` 对象。
 *
 * <p>脚本加载失败时**要把缓存的 Promise 清掉**，否则一次网络抖动之后
 * 整个页面会话里都再也加载不成功了（缓存的已失败 Promise 会一直复用）。
 */
export function loadAmap(): Promise<unknown> {
  if (!AMAP_KEY) {
    return Promise.reject(new Error('未配置高德 Key'))
  }
  if (loading) {
    return loading
  }

  loading = new Promise((resolve, reject) => {
    // 安全密钥必须在脚本加载**之前**挂到 window 上，否则地图不出图、
    // 而且报的错很含糊（高德 2021 年之后的要求）
    if (AMAP_SECURITY_CODE) {
      ;(window as unknown as Record<string, unknown>)._AMapSecurityConfig = {
        securityJsCode: AMAP_SECURITY_CODE,
      }
    }

    const script = document.createElement('script')
    script.src = `https://webapi.amap.com/maps?v=2.0&key=${encodeURIComponent(AMAP_KEY)}`
    script.async = true
    script.onload = () => {
      const amap = (window as unknown as Record<string, unknown>).AMap
      if (amap) {
        resolve(amap)
      } else {
        loading = null
        reject(new Error('高德脚本已加载，但取不到 AMap 对象'))
      }
    }
    script.onerror = () => {
      loading = null
      reject(new Error('高德地图脚本加载失败，请检查网络与 Key 是否有效'))
    }
    document.head.appendChild(script)
  })
  return loading
}
