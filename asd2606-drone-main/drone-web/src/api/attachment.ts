import { http } from './request'

/**
 * 附件（现场照片、飞行日志）。
 *
 * <p>这个 VO 里**没有**文件路径 —— 服务端只返回展示用的信息，
 * 存储布局是它的内部实现。要文件本体得走下载接口，那次调用要过权限校验
 * （有 `task:list` 能下任意任务的，否则必须是这条任务的飞手）。
 */
export interface TaskAttachmentVO {
  id: number
  fileName: string
  /** image 照片 / video 视频 / log 飞行日志 */
  fileType: string
  mimeType?: string
  fileSize?: number
  description?: string
  uploadByName?: string
  createTime?: string
}

/**
 * 下载一个附件。
 *
 * <p><b>为什么走接口拿 Blob、而不是给一个能直接点的 URL</b>：
 * 这个接口要过权限校验，而浏览器直接打开链接**带不上 Authorization 头**。
 * 所以老老实实用带 token 的请求拿回二进制，再在本地拼一个临时链接。
 *
 * <p>注意它返回的是 Blob，调用方要用 `saveBlob` 存盘 ——
 * 因为出错时后端返回的是 JSON（见 utils/download.ts 的说明）。
 */
export function downloadAttachment(attachmentId: number) {
  return http.download(`/task/attachment/${attachmentId}/download`)
}
