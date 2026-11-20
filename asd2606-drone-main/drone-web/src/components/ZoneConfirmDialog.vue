<script setup lang="ts">
/**
 * 禁飞区命中确认弹窗 —— 建任务 / 派发任务命中时弹出来。
 *
 * <h2>它存在的意义是「让人看见风险并签名」</h2>
 * 系统拦不住已经起飞的飞机，它能做的是在事前把风险摆到人面前。
 * 所以这个弹窗必须做三件事，少一件这套「软拦」就白做了：
 * <ol>
 *   <li><b>把命中的区域逐条列出来</b> —— 只说「命中禁飞区」等于没说，
 *       人得知道撞的是哪一块、是哪个点撞的</li>
 *   <li><b>必须让人写一段原因</b> —— 那段话会进流转流水，
 *       是事后唯一能把「人知情」和「人不知情」分开的东西</li>
 *   <li><b>把「会留痕」这件事说明白</b> —— 不写清的话，用户会以为点一下确认就过去了</li>
 * </ol>
 *
 * <p>不做成两个弹窗（先 confirm 再 prompt）：那样第二句提示会脱离上下文，
 * 用户只看到「请输入原因」，不知道自己在给什么签名。
 */
import { computed, ref, watch } from 'vue'
import type { ZoneHitVO } from '@/api/zone'

const props = defineProps<{
  modelValue: boolean
  /** 命中的区域（空数组时这个弹窗不该被打开） */
  hits: ZoneHitVO[]
  /** 弹窗标题：建任务和派发两处措辞不同 */
  title?: string
  /** 确认按钮的字 */
  confirmText?: string
}>()

const emit = defineEmits<{
  'update:modelValue': [boolean]
  /** 用户确认已知晓：把原因交出去，由调用方带着它重新提交 */
  confirm: [string]
}>()

/** 原因。下限与后端一致（4 个字）—— 两边不一致会让人填了一半被后端打回来 */
const MIN_LENGTH = 4
const reason = ref('')

const visible = computed({
  get: () => props.modelValue,
  set: (value: boolean) => emit('update:modelValue', value),
})

// 每次打开都清空：上一次的原因留着，很容易被人顺手点了确认
watch(
  () => props.modelValue,
  (open) => {
    if (open) {
      reason.value = ''
    }
  },
)

const tooShort = computed(() => reason.value.trim().length < MIN_LENGTH)
const overLimit = computed(() => reason.value.trim().length > 150)

function confirm() {
  if (tooShort.value) {
    ElMessage.warning(`请把原因写清楚（至少 ${MIN_LENGTH} 个字）`)
    return
  }
  if (overLimit.value) {
    ElMessage.warning('原因请控制在 150 个字以内')
    return
  }
  emit('confirm', reason.value.trim())
  visible.value = false
}
</script>

<template>
  <el-dialog v-model="visible" :title="title || '命中禁飞区'" width="560px" align-center>
    <p class="tip">
      这些位置撞上了<b>生效中</b>的禁飞区。系统不会替你决定能不能飞 ——
      确认已知晓风险、并写清原因后可以继续，<b>这段记录会进任务的流转流水</b>。
    </p>

    <ul class="hits">
      <li v-for="hit in hits" :key="hit.zoneId + '-' + hit.pointLabel">
        <span class="hits__tag" :class="hit.forbidden ? 'is-forbidden' : 'is-limited'">
          {{ hit.forbidden ? '禁飞' : '限高' }}
        </span>
        <span class="hits__text">{{ hit.message }}</span>
      </li>
    </ul>

    <label class="field">
      <span class="field__label">已知晓风险的原因</span>
      <textarea
        v-model="reason"
        class="field__area"
        rows="3"
        placeholder="如：客户场地，已确认当天无飞行活动 / 有临时批文，已报备空管"
      ></textarea>
    </label>
    <p class="field__hint">
      至少 {{ MIN_LENGTH }} 个字（「知道了」这种等于没写）。这句话会随任务一起留档。
      当前 {{ reason.trim().length }} 字。
    </p>

    <template #footer>
      <button class="btn btn--ghost" type="button" @click="visible = false">取消</button>
      <button class="btn btn--primary" type="button" :disabled="tooShort || overLimit" @click="confirm">
        {{ confirmText || '已知晓，继续' }}
      </button>
    </template>
  </el-dialog>
</template>

<style scoped>
.tip {
  margin-bottom: 14px;
  font-size: 13px;
  line-height: 1.9;
  color: var(--muted);
}

.hits {
  margin: 0 0 16px;
  padding: 10px 12px;
  list-style: none;
  border-radius: 10px;
  background: rgba(248, 113, 113, 0.08);
  border: 1px solid rgba(248, 113, 113, 0.28);
}

.hits li {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 4px 0;
  font-size: 13px;
  line-height: 1.7;
  color: var(--text);
}

.hits__tag {
  flex: none;
  margin-top: 2px;
  padding: 1px 6px;
  border-radius: 4px;
  font-size: 11px;
  color: #03121f;
  background: #fca5a5;
}

.hits__tag.is-limited {
  background: #fcd34d;
}

.field {
  display: block;
}

.field__label {
  display: block;
  margin-bottom: 6px;
  font-size: 13px;
  color: var(--muted);
}

.field__area {
  width: 100%;
  padding: 9px 12px;
  border-radius: 9px;
  font-size: 13.5px;
  font-family: inherit;
  line-height: 1.7;
  color: var(--text);
  background: var(--input-bg);
  border: 1px solid var(--line-strong);
  outline: none;
  resize: vertical;
}

.field__area:focus {
  border-color: var(--accent-hover);
  box-shadow: 0 0 0 3px rgba(34, 211, 238, 0.12);
}

.field__hint {
  margin-top: 6px;
  font-size: 12px;
  color: var(--muted);
}
</style>
