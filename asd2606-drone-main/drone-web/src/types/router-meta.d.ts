import 'vue-router'

/**
 * 扩展 vue-router 的 RouteMeta 类型，让 route.meta.title / permission 有类型提示。
 *
 * 注意这里的 `import 'vue-router'` 不能省：
 * 有了它，本文件才被当作「模块」，下面的 declare module 才是对原模块的**增强**；
 * 否则 TS 会把它理解成**覆盖**整个 vue-router 模块的声明，
 * 导致 createRouter、RouterView 等导出全部消失（编译报 TS2305）。
 * 这也是它没有和 env.d.ts 放在一起的原因 —— 那个文件是全局声明文件，不能带 import。
 */
declare module 'vue-router' {
  interface RouteMeta {
    /** 免登录即可访问 */
    public?: boolean
    /** 面包屑标题 */
    title?: string
    /** 访问该页面所需的权限码，如 staff:list；不写表示登录即可访问 */
    permission?: string
  }
}
