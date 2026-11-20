<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { fetchArtifact, type AgentArtifact } from '@/api/agent'
import { saveBlob } from '@/utils/download'

/**
 * 对话里的 HTML 产物卡片。
 *
 * <h3>为什么用 iframe 而不是 v-html</h3>
 * 模型输出属于不可信内容；报告还自带 `<style>`，注入主文档会污染全局主题。
 * `sandbox=""` 会禁用脚本、表单、顶层跳转与同源，报告只能在里面「看」。
 *
 * <h3>为什么先取 Blob 再给 iframe</h3>
 * 鉴权走 `Authorization` 头，`<iframe src>` 不会自动带；所以用 axios 取回 Blob，
 * 再 `createObjectURL` 给预览与下载共用。卸载时记得 revoke。
 */
const props = defineProps<{ artifact: AgentArtifact }>()

const objectUrl = ref('')
const blob = ref<Blob | null>(null)
const loading = ref(true)
const failed = ref(false)
const previewOpen = ref(false)

function formatSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`
}

onMounted(async () => {
  try {
    const content = await fetchArtifact(props.artifact.id)
    blob.value = content
    objectUrl.value = URL.createObjectURL(content)
  } catch {
    // 拦截器已弹过提示；这里只把卡片降级成「可下载」，不打断整段对话
    failed.value = true
  } finally {
    loading.value = false
  }
})

onBeforeUnmount(() => {
  if (objectUrl.value) {
    URL.revokeObjectURL(objectUrl.value)
  }
})

async function download() {
  if (!blob.value) {
    ElMessage.error('报告还没加载好，请稍后再试')
    return
  }
  await saveBlob(blob.value, props.artifact.fileName)
}
</script>

<template>
  <div class="artifact">
    <div class="artifact__head">
      <div class="artifact__meta">
        <span class="artifact__name" :title="artifact.fileName">{{ artifact.fileName }}</span>
        <span class="artifact__size">{{ formatSize(artifact.sizeBytes) }}</span>
      </div>
      <div class="artifact__actions">
        <button
          class="artifact__btn"
          type="button"
          :disabled="loading || failed"
          @click="previewOpen = true"
        >
          放大
        </button>
        <button class="artifact__btn artifact__btn--primary" type="button" :disabled="loading" @click="download">
          下载
        </button>
      </div>
    </div>

    <div class="artifact__frame">
      <div v-if="loading" class="artifact__state">正在加载报告…</div>
      <div v-else-if="failed" class="artifact__state">预览加载失败，可以尝试下载</div>
      <iframe
        v-else
        :src="objectUrl"
        sandbox=""
        referrerpolicy="no-referrer"
        loading="lazy"
        title="报告预览"
      ></iframe>
    </div>

    <el-dialog v-model="previewOpen" :title="artifact.fileName" width="92%" append-to-body>
      <iframe
        v-if="objectUrl"
        :src="objectUrl"
        sandbox=""
        referrerpolicy="no-referrer"
        class="artifact__dialog-frame"
        title="报告预览（放大）"
      ></iframe>
    </el-dialog>
  </div>
</template>

<style scoped>
.artifact {
  margin-top: 10px;
  border: 1px solid var(--line-strong);
  border-radius: 12px;
  overflow: hidden;
  background: var(--panel);
}

.artifact__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 9px 12px;
  border-bottom: 1px solid var(--line);
}

.artifact__meta {
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.artifact__name {
  font-size: 12.5px;
  color: var(--text);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.artifact__size {
  font-size: 11px;
  color: var(--text-2);
}

.artifact__actions {
  display: flex;
  gap: 8px;
  flex-shrink: 0;
}

.artifact__btn {
  border: 1px solid var(--line-strong);
  background: transparent;
  color: var(--text-2);
  font-size: 12px;
  padding: 4px 10px;
  border-radius: 8px;
  cursor: pointer;
  transition: 0.16s;
}

.artifact__btn:hover:not(:disabled) {
  color: var(--text);
  border-color: var(--cyan);
}

.artifact__btn--primary {
  background: rgba(34, 211, 238, 0.14);
  border-color: rgba(34, 211, 238, 0.4);
  color: var(--cyan);
}

.artifact__btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.artifact__frame {
  height: 340px;
  background: #fff;
}

.artifact__frame iframe {
  width: 100%;
  height: 100%;
  border: 0;
  display: block;
  background: #fff;
}

.artifact__state {
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 12.5px;
  color: var(--text-2);
  background: var(--ghost-bg);
}

.artifact__dialog-frame {
  width: 100%;
  height: 78vh;
  border: 0;
  background: #fff;
}
</style>
