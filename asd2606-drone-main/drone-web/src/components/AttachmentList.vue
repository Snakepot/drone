<script setup lang="ts">
/**
 * 附件列表：图片直接显示（点开大图），非图片给下载按钮。
 *
 * <h2>为什么要抽成组件</h2>
 * 告警详情和任务报告都要显示飞手拍的照片，而这里面的坑不少
 * （图片要带 token 取 Blob、object URL 用完必须 revoke、后端出错时返回的是 JSON
 * 而不是图片）。抄两份的话，迟早有一处漏掉 revoke —— 那是内存只涨不落的泄漏，
 * 而且不报错、不报警，很难发现。
 *
 * <h2>一个必须绕的坑</h2>
 * 下载接口要过权限，而浏览器自己发的图片请求**带不上 Authorization 头**，
 * 所以 `<img src>` 直接写接口地址只会 401。做法是带 token 取回 Blob、
 * 再用 {@code URL.createObjectURL} 造一个本地地址给 `<img>` 用。
 */
import { onBeforeUnmount, ref, watch } from 'vue'
import { downloadAttachment, type TaskAttachmentVO } from '@/api/attachment'
import { saveBlob } from '@/utils/download'

const props = defineProps<{
  attachments?: TaskAttachmentVO[]
}>()

/** 附件 id → 本地预览地址（只有图片才有） */
const previewUrls = ref<Record<number, string>>({})

watch(() => props.attachments, loadPreviews, { immediate: true, deep: true })
// 组件销毁时必须释放，否则 Blob 会被钉在内存里直到页面关闭
onBeforeUnmount(releasePreviews)

/** 把图片附件取回来，做成可以直接显示的本地地址（非图片不动） */
async function loadPreviews() {
  releasePreviews()
  const images = (props.attachments || []).filter((file) => file.fileType === 'image')
  for (const file of images) {
    try {
      const blob = await downloadAttachment(file.id)
      // 后端出错时返回的是 JSON 而不是图片 —— 照 utils/download.ts 的同一条判断，
      // 不然会把一段错误信息当图片渲染，页面上出现一个破图
      if (blob.type.includes('application/json')) continue
      previewUrls.value[file.id] = URL.createObjectURL(blob)
    } catch {
      // 取不到就不预览。文件名按钮还在，点它仍能下载
    }
  }
}

/** 释放 object URL，否则内存只涨不落 */
function releasePreviews() {
  Object.values(previewUrls.value).forEach((url) => URL.revokeObjectURL(url))
  previewUrls.value = {}
}

/**
 * 取某张附件的预览地址；还没取到就返回空串。
 *
 * <p>为什么不直接在模板里写 {@code previewUrls[file.id]}：那样类型是
 * `string | undefined`，模板里判过了也没法让类型收窄（模板不是 TS 的控制流范围）。
 * 包一层函数把「没有就是空串」说清楚，模板里就只剩一个判断。
 */
function previewUrlOf(file: TaskAttachmentVO): string {
  return previewUrls.value[file.id] ?? ''
}

async function handleDownload(file: TaskAttachmentVO) {
  try {
    const blob = await downloadAttachment(file.id)
    await saveBlob(blob, file.fileName)
  } catch {
    // 请求层已经弹过提示（403 / 网络异常等），这里只负责收尾
  }
}
</script>

<template>
  <div v-if="(attachments || []).length" class="files">
    <div v-for="file in attachments" :key="file.id" class="file">
      <!-- 图片：等大图预览 -->
      <el-image
        v-if="previewUrlOf(file)"
        class="file__thumb"
        :src="previewUrlOf(file)"
        :preview-src-list="[previewUrlOf(file)]"
        :preview-teleported="true"
        fit="cover"
      />
      <span v-else-if="file.fileType === 'image'" class="file__thumb file__thumb--loading">
        加载中…
      </span>

      <button class="file__name" type="button" @click="handleDownload(file)">
        {{ file.fileName }}
        <em v-if="file.fileSize">{{ Math.ceil(file.fileSize / 1024) }} KB</em>
      </button>
    </div>
  </div>
  <span v-else class="files__none">无</span>
</template>

<style scoped>
.files {
  display: flex;
  flex-direction: column;
  gap: 10px;
  min-width: 0;
}

.file {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

/* 缩略图：固定小尺寸，点一下会开大图（el-image 自带的预览） */
.file__thumb {
  width: 120px;
  height: 120px;
  border-radius: 6px;
  border: 1px solid var(--line, rgba(255, 255, 255, 0.12));
  cursor: zoom-in;
}

/* 图片还没取回来时的占位，避免布局突然跳动 */
.file__thumb--loading {
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  color: var(--muted);
  cursor: default;
}

.file__name {
  padding: 0;
  border: none;
  background: none;
  font: inherit;
  font-size: 13px;
  color: var(--accent, #38bdf8);
  text-align: left;
  cursor: pointer;
  word-break: break-all;
}

.file__name:hover {
  text-decoration: underline;
}

.file__name em {
  margin-left: 6px;
  font-style: normal;
  font-size: 12px;
  color: var(--muted);
}

/* 没有附件时的占位 —— 让「这条没有」与「没这功能」分得开 */
.files__none {
  color: var(--muted);
}
</style>
