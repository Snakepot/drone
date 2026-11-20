<script setup lang="ts">
/**
 * 任务通知浮层。
 *
 * <p>挂在后台布局里，所有管理页面都能看到 —— 飞手开始执行或拒绝执行时，
 * 管理员不用盯着任务列表也能知道。
 *
 * <h2>两类通知的视觉分量刻意不同</h2>
 * <ul>
 *   <li><b>拒绝执行</b>：红色警告样式、<b>不自动消失</b>、带「去重新指派」按钮 ——
 *       这是唯一需要管理员动手的一类，混在别的提示里一闪而过等于没通知</li>
 *   <li><b>开始执行</b>：普通青色样式，6 秒后自己消失，只作告知</li>
 * </ul>
 */
import { useRouter } from 'vue-router'
import { useTaskSocket, type TaskNotification } from '@/composables/useTaskSocket'

const router = useRouter()
const { notifications, dismiss } = useTaskSocket()

function goToNotice(notice: TaskNotification) {
  // 跳哪由通知自己说了算：任务通知去 /task，告警通知去 /alert。
  // 原来写死 /task，加了告警之后就会「收到告警却跳到任务页」
  router.push(notice.path ?? '/task')
  // 跳过去之后把这些提示收掉，否则会一直压在页面上
  notifications.value.forEach((item) => dismiss(item.id))
}
</script>

<template>
  <div class="notice-stack">
    <div
      v-for="notice in notifications"
      :key="notice.id"
      class="notice"
      :class="{ 'notice--danger': notice.needsAction }"
    >
      <div class="notice__head">
        <span class="notice__icon">{{ notice.needsAction ? '!' : 'i' }}</span>
        <span class="notice__title">{{ notice.title }}</span>
        <span class="notice__close" @click="dismiss(notice.id)">×</span>
      </div>

      <div class="notice__body">
        <p v-for="(line, index) in notice.lines" :key="index" class="notice__line">
          {{ line }}
        </p>
      </div>

      <div v-if="notice.needsAction" class="notice__foot">
        <button class="notice__btn" type="button" @click="goToNotice(notice)">
          {{ notice.actionLabel ?? '去重新指派' }}
        </button>
      </div>
    </div>
  </div>
</template>

<style scoped>
/*
  固定在右上角，压在顶栏下面。用 pointer-events 让容器本身不挡鼠标 ——
  只有卡片自己接收点击，否则浮层会盖住下面的按钮。
*/
.notice-stack {
  position: fixed;
  top: 76px;
  right: 24px;
  z-index: 3000;
  display: flex;
  flex-direction: column;
  gap: 12px;
  width: 340px;
  pointer-events: none;
}

.notice {
  position: relative;
  pointer-events: auto;
  padding: 14px 16px;
  border-radius: 12px;
  background: var(--panel-2);
  border: 1px solid var(--line-strong);
  box-shadow: var(--card-shadow);
  backdrop-filter: blur(14px);
  animation: notice-in 0.24s ease;
}

/* 拒绝执行：红色描边 + 左侧一条实心警示条，和普通通知一眼能分开 */
.notice--danger {
  border-color: var(--tag-red-bd);
  box-shadow: 0 0 0 1px rgba(248, 113, 113, 0.18), 0 18px 40px rgba(0, 0, 0, 0.4);
}

.notice--danger::before {
  content: '';
  position: absolute;
  left: 0;
  top: 12px;
  bottom: 12px;
  width: 3px;
  border-radius: 0 3px 3px 0;
  background: linear-gradient(180deg, var(--red), #fb923c);
}

.notice__head {
  display: flex;
  align-items: center;
  gap: 8px;
}

.notice__icon {
  width: 20px;
  height: 20px;
  flex: none;
  display: grid;
  place-items: center;
  border-radius: 50%;
  font-size: 13px;
  font-weight: 700;
  color: var(--cyan);
  background: rgba(34, 211, 238, 0.14);
  border: 1px solid var(--tag-cyan-bd);
}

.notice--danger .notice__icon {
  color: var(--red);
  background: rgba(248, 113, 113, 0.16);
  border-color: var(--tag-red-bd);
}

.notice__title {
  flex: 1;
  font-size: 13.5px;
  font-weight: 600;
  color: var(--text);
}

.notice--danger .notice__title {
  color: var(--tag-red-fg);
}

.notice__close {
  flex: none;
  width: 20px;
  text-align: center;
  font-size: 16px;
  line-height: 1;
  color: var(--muted);
  cursor: pointer;
}

.notice__close:hover {
  color: var(--text);
}

.notice__body {
  margin-top: 8px;
}

.notice__line {
  font-size: 12.5px;
  line-height: 1.8;
  color: var(--text-2);
  word-break: break-all;
}

.notice--danger .notice__line:last-child {
  color: var(--tag-amber-fg);
}

.notice__foot {
  margin-top: 10px;
}

.notice__btn {
  width: 100%;
  height: 32px;
  border-radius: 8px;
  font-size: 12.5px;
  font-family: inherit;
  color: var(--btn-primary-fg);
  font-weight: 600;
  background: linear-gradient(100deg, var(--red), #fb923c);
  border: none;
  cursor: pointer;
}

.notice__btn:hover {
  filter: brightness(1.06);
}

@keyframes notice-in {
  from {
    opacity: 0;
    transform: translateX(16px);
  }
  to {
    opacity: 1;
    transform: none;
  }
}
</style>
