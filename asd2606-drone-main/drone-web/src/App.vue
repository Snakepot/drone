<script setup lang="ts">
import { watch } from 'vue'
import { RouterView } from 'vue-router'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()

// token 一旦就绪（刚登录完成，或刷新页面时从 localStorage 恢复），就去拉当前登录人的信息。
// 登录页与业务页的框架差异由路由处理（业务页套 AdminLayout），这里只负责语言包与全局初始化。
watch(
  () => auth.token,
  (token) => {
    if (token) {
      auth.fetchCurrentUser().catch(() => {
        // 失败（例如 token 失效返回 401）已由 axios 响应拦截器统一处理，这里只避免未捕获异常
      })
    }
  },
  { immediate: true },
)
</script>

<template>
  <el-config-provider :locale="zhCn">
    <RouterView />
  </el-config-provider>
</template>
