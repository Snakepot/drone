<script setup lang="ts">
/**
 * 告警滚动流：最近发生的几条，新的从上面进来。
 *
 * <p>大屏上这一块的规矩是**只显示、不处理** —— 没有按钮、不能点开。
 * 处理告警是告警中心的事（那里有处置记录、有权限校验），
 * 而大屏前面通常没有人。
 *
 * <p>空列表要显示「暂无告警」而不是留白：留白会让人以为「是不是没数据/挂了」。
 */
import type { RecentAlert } from '@/api/dashboard'

defineProps<{
  items: RecentAlert[]
  /** 最多显示几条（超出滚动出去） */
  limit?: number
}>()

/** 级别 → 标签颜色。三档，与告警中心的配色一致 */
function severityTone(severity: string) {
  switch (severity) {
    case 'critical':
      return 'danger'
    case 'warning':
      return 'warn'
    default:
      return 'info'
  }
}

function severityText(severity: string) {
  switch (severity) {
    case 'critical':
      return '严重'
    case 'warning':
      return '警告'
    case 'info':
      return '提示'
    default:
      return severity
  }
}
</script>

<template>
  <div class="feed">
    <div class="feed__title">最近告警</div>
    <div v-if="items.length === 0" class="feed__empty">暂无告警</div>
    <ul v-else class="feed__list">
      <li
        v-for="(item, index) in items.slice(0, limit || 8)"
        :key="index"
        class="feed__item"
        :class="{ 'is-fresh': index === 0 }"
      >
        <span class="feed__tag" :class="`is-${severityTone(item.severity)}`">
          {{ severityText(item.severity) }}
        </span>
        <span class="feed__label">{{ item.label }}</span>
        <span class="feed__msg">{{ item.message }}</span>
        <span class="feed__time">{{ (item.observedAt || '').slice(11, 16) }}</span>
      </li>
    </ul>
  </div>
</template>

<style scoped>
.feed {
  display: flex;
  flex-direction: column;
  height: 100%;
  padding: 14px 16px;
  border-radius: 12px;
  background: linear-gradient(160deg, rgba(38, 84, 140, 0.28), rgba(12, 26, 48, 0.5));
  border: 1px solid rgba(90, 150, 220, 0.22);
}

.feed__title {
  margin-bottom: 10px;
  font-size: 15px;
  letter-spacing: 1px;
  color: rgba(200, 224, 255, 0.68);
}

.feed__empty {
  padding: 30px 0;
  text-align: center;
  font-size: 15px;
  color: rgba(180, 208, 240, 0.45);
}

.feed__list {
  flex: 1;
  margin: 0;
  padding: 0;
  list-style: none;
  overflow: hidden;
}

.feed__item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 9px 0;
  font-size: 14px;
  border-bottom: 1px solid rgba(90, 150, 220, 0.12);
}

/* 最新那一条稍微亮一点：人看滚动流时先看这里 */
.feed__item.is-fresh .feed__msg {
  color: #eaf4ff;
}

.feed__tag {
  flex: none;
  padding: 1px 7px;
  border-radius: 4px;
  font-size: 12px;
  color: #03121f;
  background: #7dd3fc;
}

.feed__tag.is-danger {
  background: #fca5a5;
}

.feed__tag.is-warn {
  background: #fcd34d;
}

.feed__label {
  flex: none;
  color: rgba(200, 224, 255, 0.85);
}

.feed__msg {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: rgba(180, 208, 240, 0.7);
}

.feed__time {
  flex: none;
  font-variant-numeric: tabular-nums;
  color: rgba(180, 208, 240, 0.55);
}
</style>
