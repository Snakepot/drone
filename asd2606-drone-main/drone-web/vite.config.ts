/// <reference types="vitest/config" />
import { fileURLToPath, URL } from 'node:url'

import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'
import vueDevTools from 'vite-plugin-vue-devtools'
import AutoImport from 'unplugin-auto-import/vite'
import Components from 'unplugin-vue-components/vite'
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers'

// https://vite.dev/config/
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')

  return {
    plugins: [
      vue(),
      vueDevTools(),
      AutoImport({
        imports: ['vue', 'vue-router', 'pinia'],
        resolvers: [ElementPlusResolver()],
        dts: 'src/types/auto-imports.d.ts',
      }),
      Components({
        resolvers: [ElementPlusResolver()],
        dts: 'src/types/components.d.ts',
      }),
    ],
    resolve: {
      alias: {
        '@': fileURLToPath(new URL('./src', import.meta.url)),
      },
    },
    server: {
      port: 5173,
      proxy: {
        /*
         * 智能助手走 drone-agent（独立服务，默认 8081），
         * 与其它接口不在同一个后端，所以单开一条。
         * 必须放在 '/api' 之前 —— vite 按声明顺序匹配，先命中的生效；
         * 放到后面的话请求会被 '/api' 那条吃掉，转发到 drone-server 上去，报 404。
         */
        '/api/agent': {
          target: env.VITE_AGENT_PROXY_TARGET || 'http://localhost:8081',
          changeOrigin: true,
          rewrite: (path) => path.replace(/^\/api\/agent/, ''),
        },
        // ws: true 是给任务通知那条 WebSocket 用的（/api/ws/task）——
        // 不开这个开关，浏览器的 WebSocket 请求不会被转发，管理员就收不到实时通知
        '/api': {
          target: env.VITE_PROXY_TARGET || 'http://localhost:8080',
          changeOrigin: true,
          ws: true,
          rewrite: (path) => path.replace(/^\/api/, ''),
        },
      },
    },
    test: {
      /*
       * 单元测试只跑 src 下的 *.spec.ts。
       * 环境用 node：目前只测 api 层这类不碰 DOM 的纯逻辑，不需要 jsdom/happy-dom。
       * 将来要测组件，得再引入 happy-dom（或 jsdom）与 @vue/test-utils ——
       * 那属于新增依赖，按 frontend-standards 第 1 节要先评审。
       */
      environment: 'node',
      include: ['src/**/*.spec.ts'],
      server: {
        deps: {
          /*
           * element-plus 必须交给 vite 处理，不能外部化给 node 直接加载。
           * 原因：自动导入的解析器会顺带给组件注入样式副作用导入，
           * 那条链路最终 `import 'element-plus/theme-chalk/base.css'` ——
           * node 的 ESM 加载器不认 .css，会直接报 Unknown file extension ".css"。
           * 交给 vite 之后，测试环境会把 CSS stub 掉（不加载、也不报错）。
           */
          inline: ['element-plus'],
        },
      },
    },
  }
})
