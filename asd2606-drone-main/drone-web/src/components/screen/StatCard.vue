<script setup lang="ts">
/**
 * 大屏上的一个数字卡：一个大数字 + 一行小字 + 可选的次级明细。
 *
 * <p>大屏上「数字」是主角，所以要克制：**一张卡只讲一件事**，
 * 单位/口径写在下面那行小字里（比如「在线率 96%」下面写「10 分钟内有数据」），
 * 不然看的人会去猜这个 96% 是怎么来的。
 */
defineProps<{
  label: string
  value: string | number
  /** 下面那行更小的字：口径、单位、对比值 */
  hint?: string
  /** 数字的颜色语义：normal 白 / warn 琥珀 / danger 红 / good 绿 */
  tone?: 'normal' | 'good' | 'warn' | 'danger'
  /** 点开这个数字要去哪个页面看明细（大屏上基本没人点，但留着不吃亏） */
  to?: string
}>()
</script>

<template>
  <component :is="to ? 'router-link' : 'div'" :to="to" class="statcard" :class="{ 'is-link': !!to }">
    <span class="statcard__label">{{ label }}</span>
    <span class="statcard__value" :class="`is-${tone || 'normal'}`">{{ value }}</span>
    <span v-if="hint" class="statcard__hint">{{ hint }}</span>
  </component>
</template>

<style scoped>
.statcard {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 16px 18px;
  border-radius: 12px;
  background: linear-gradient(160deg, rgba(38, 84, 140, 0.28), rgba(12, 26, 48, 0.5));
  border: 1px solid rgba(90, 150, 220, 0.22);
  text-decoration: none;
}

.statcard.is-link:hover {
  border-color: rgba(120, 190, 255, 0.45);
}

.statcard__label {
  font-size: 15px;
  letter-spacing: 1px;
  color: rgba(200, 224, 255, 0.68);
}

.statcard__value {
  font-size: 40px;
  font-weight: 700;
  line-height: 1.1;
  font-variant-numeric: tabular-nums;
  color: #eaf4ff;
}

.statcard__value.is-good {
  color: #6ee7b7;
}

.statcard__value.is-warn {
  color: #fcd34d;
}

.statcard__value.is-danger {
  color: #fca5a5;
}

.statcard__hint {
  font-size: 12.5px;
  color: rgba(180, 208, 240, 0.55);
}
</style>
