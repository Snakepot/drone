<script setup lang="ts">
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
</script>

<template>
  <div class="card">
    <div class="card__head">
      <div class="card__title">
        暂无可用功能
        <small>账号权限待分配</small>
      </div>
    </div>

    <div class="empty">
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round">
        <rect x="4.5" y="10.5" width="15" height="9.5" rx="2.6" />
        <path d="M8 10.5V7.8a4 4 0 0 1 8 0v2.7" />
      </svg>
      <p>
        当前账号（<span class="name">{{ auth.displayName }}</span>）还没有被分配任何功能权限，
        请联系管理员开通后再试。
      </p>
    </div>
  </div>
</template>

<style scoped>
.empty {
  display: grid;
  place-items: center;
  gap: 14px;
  padding: 70px 24px;
  text-align: center;
}

.empty svg {
  width: 42px;
  height: 42px;
  color: rgba(120, 190, 255, 0.35);
}

.empty p {
  max-width: 420px;
  font-size: 13.5px;
  line-height: 1.9;
  color: var(--muted);
}

.name {
  color: var(--text);
  font-weight: 500;
}
</style>
