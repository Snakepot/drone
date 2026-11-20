import { ElMessage } from 'element-plus'

/**
 * 把 Blob 存成本地文件。
 *
 * <h2>为什么不能拿到 blob 就直接下载</h2>
 * 后端出错时返回的是 <b>JSON 而不是文件</b>（比如 403「这条附件不属于你的任务」）。
 * 但请求的 `responseType` 是 blob，axios 不会把它当失败 ——
 * 前端会「成功」下载到一个内容是错误信息的文件，用户打开才知道上当了。
 * 所以这里要先看类型：是 JSON 就解析出 message 弹出来，并且**不**触发下载。
 *
 * <h2>为什么单独抽出来</h2>
 * 设备导出、告警附件下载都要用。抄一份到每个页面的话，
 * 「JSON 错误处理」这种容易漏的分支就会漏在某一处 ——
 * 而它恰恰是用户最容易踩到的那个问题（权限不够时点了下载）。
 *
 * @returns true 表示真的下载了；false 表示其实是错误、已弹过提示
 */
export async function saveBlob(blob: Blob, filename: string): Promise<boolean> {
  if (blob.type.includes('application/json')) {
    const text = await blob.text()
    try {
      const payload = JSON.parse(text)
      ElMessage.error(payload.message || '下载失败')
    } catch {
      ElMessage.error('下载失败')
    }
    return false
  }

  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = filename
  link.click()
  URL.revokeObjectURL(url)
  return true
}
