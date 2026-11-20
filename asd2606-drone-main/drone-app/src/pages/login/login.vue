<script setup lang="ts">
import { ref } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import { login } from '@/api/user'
import { isLoggedIn, setToken } from '@/utils/auth'

const username = ref('')
const password = ref('')
const loading = ref(false)

/**
 * 每次显示登录页时检查一下本地是否已有 token。
 *
 * <p>有这个判断，飞手就不需要每次打开小程序都重新输密码 ——
 * 上次登录的 token 还在（后端有效期 2 小时），直接进任务页。
 * token 真失效了也不怕：任务页第一次请求就会 401，
 * 由 request.ts 统一清掉 token 并跳回这里。
 */
onShow(() => {
  if (isLoggedIn()) {
    uni.reLaunch({ url: '/pages/task/my' })
  }
})

async function handleLogin() {
  if (!username.value.trim()) {
    uni.showToast({ title: '请输入登录账号', icon: 'none' })
    return
  }
  if (!password.value) {
    uni.showToast({ title: '请输入密码', icon: 'none' })
    return
  }

  loading.value = true
  try {
    // 账号密码错、账号被禁用这类失败，request.ts 里已经弹过提示了，
    // 这里只管收尾，不再弹第二次
    const result = await login({
      username: username.value.trim(),
      password: password.value,
    })
    setToken(result.token)
    uni.showToast({ title: '登录成功', icon: 'success' })
    // 用 reLaunch 而不是 navigateTo：登录页不该留在返回栈里，
    // 否则飞手在任务页点系统返回又能回到登录页
    uni.reLaunch({ url: '/pages/task/my' })
  } catch {
    // 保持停在登录页，让飞手改密码重试
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <view class="login">
    <!-- 品牌区 -->
    <view class="login__brand">
      <view class="login__mark">✦</view>
      <view class="login__title">飞手工作台</view>
      <view class="login__sub">DroneOps · Pilot</view>
    </view>

    <!-- 表单 -->
    <view class="pilot-card login__form">
      <view class="field">
        <text class="field__label">登录账号</text>
        <input
          v-model="username"
          class="field__input"
          type="text"
          placeholder="请输入手机号"
          placeholder-class="field__placeholder"
          :maxlength="20"
        />
      </view>

      <view class="field">
        <text class="field__label">密码</text>
        <input
          v-model="password"
          class="field__input"
          password
          placeholder="请输入密码"
          placeholder-class="field__placeholder"
          :maxlength="32"
          @confirm="handleLogin"
        />
      </view>

      <button
        class="pilot-btn pilot-btn--primary login__submit"
        :disabled="loading"
        @click="handleLogin"
      >
        {{ loading ? '登录中…' : '登 录' }}
      </button>

      <view class="login__hint">账号由管理员在后台创建，如有问题请联系管理员</view>
    </view>

    <view class="login__foot">无人机管理平台 · 飞手端</view>
  </view>
</template>

<style lang="scss" scoped>
.login {
  min-height: 100vh;
  padding: 0 48rpx;
  display: flex;
  flex-direction: column;
  justify-content: center;
}

/* ---------- 品牌 ---------- */
.login__brand {
  text-align: center;
  margin-bottom: 56rpx;
}

.login__mark {
  width: 112rpx;
  height: 112rpx;
  margin: 0 auto 24rpx;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 28rpx;
  font-size: 52rpx;
  color: $pilot-cyan;
  background: linear-gradient(140deg, rgba(34, 211, 238, 0.22), rgba(139, 92, 246, 0.22));
  border: 1rpx solid $pilot-line-strong;
}

.login__title {
  font-size: 42rpx;
  font-weight: 600;
  letter-spacing: 4rpx;
}

.login__sub {
  margin-top: 10rpx;
  font-size: 22rpx;
  letter-spacing: 4rpx;
  color: $pilot-muted;
  text-transform: uppercase;
}

/* ---------- 表单 ---------- */
.login__form {
  /* 卡片本身有 margin，这里去掉，由外层统一控制留白 */
  margin: 0;
  padding: 40rpx 32rpx 32rpx;
}

.field {
  margin-bottom: 32rpx;
}

.field__label {
  display: block;
  margin-bottom: 14rpx;
  font-size: 26rpx;
  color: $pilot-muted;
}

.field__input {
  height: 88rpx;
  padding: 0 24rpx;
  border-radius: 14rpx;
  font-size: 30rpx;
  color: $pilot-text;
  background: $pilot-input-bg;
  border: 1rpx solid $pilot-line-strong;
}

.field__placeholder {
  color: rgba(230, 241, 255, 0.28);
}

.login__submit {
  margin-top: 16rpx;
  width: 100%;
}

.login__hint {
  margin-top: 28rpx;
  font-size: 24rpx;
  line-height: 1.8;
  text-align: center;
  color: $pilot-muted;
}

.login__foot {
  margin-top: 64rpx;
  text-align: center;
  font-size: 22rpx;
  letter-spacing: 1rpx;
  color: $pilot-muted;
}
</style>
