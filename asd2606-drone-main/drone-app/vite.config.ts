import { defineConfig } from "vite";
import uni from "@dcloudio/vite-plugin-uni";

// https://vitejs.dev/config/
export default defineConfig({
  plugins: [uni()],
  server: {
    port: 5174,
    proxy: {
      // 只有 H5 端会用到这个代理：后端没配 CORS，浏览器直连会被挡下，
      // 所以 H5 走同源的 /api，由 devServer 转发到后端并剥掉前缀 ——
      // 与后台管理端 drone-web 的 vite.config.ts 是同一套做法。
      // 微信小程序不走代理，它在 src/config.ts 里直连后端。
      //
      // ws: true 是给 WebSocket 用的：任务通知那条连接也是 /api/ws/task，
      // 不开这个开关的话，浏览器的 WebSocket 请求不会被转发。
      "/api": {
        target: "http://localhost:8080",
        changeOrigin: true,
        ws: true,
        rewrite: (path) => path.replace(/^\/api/, ""),
      },
    },
  },
});
