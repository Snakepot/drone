import { uploadFile } from './request'

/**
 * 附件（现场照片 / 飞行日志）。
 *
 * <h2>这个 VO 里<b>没有</b>文件路径，这是刻意的</h2>
 * 服务端只返回文件名、类型、大小这些展示用的信息，
 * 存储布局（文件放在哪、叫什么）是服务端的内部实现。
 * 要拿文件本体得走下载接口，那次调用要过权限校验 ——
 * 把路径吐给前端等于「拿到 URL 就能下」，绕过了权限。
 */
export interface TaskAttachmentVO {
  id: number
  /** 原始文件名，展示用 */
  fileName: string
  /** image 照片 / video 视频 / log 飞行日志 */
  fileType: string
  mimeType?: string
  /** 字节数 */
  fileSize?: number
  description?: string
  uploadByName?: string
  createTime?: string
}

/**
 * 上传一个附件到某条任务。
 *
 * <p>**分两步走**：先调这个接口拿到附件 id，再带着 id 去提交现场上报。
 * 为什么不合成一步：照片可能在飞手写说明之前就选好了，
 * 而上传失败时他应该能重试上传、不必把说明重写一遍。
 *
 * <p>允许的类型由服务端说了算（白名单），客户端这里不预先拦 ——
 * 拦了之后两边的白名单就成了两份，改一处漏一处。
 * 传错类型时后端会把「不支持的文件类型：.sh」这类原因带回来，直接弹给飞手看。
 *
 * @param taskId   归属任务（必须是派给当前登录飞手的）
 * @param filePath 本地临时文件路径，`uni.chooseImage` 的返回值
 */
export function uploadTaskAttachment(taskId: number, filePath: string) {
  return uploadFile<TaskAttachmentVO>({
    url: '/task/attachment/upload',
    filePath,
    name: 'file',
    formData: { taskId },
  })
}
