package cn.tedu.droneagent.artifact;

/**
 * 返回给前端的产物引用。真正的 HTML 不在响应体里，前端按 id 再取。
 *
 * @param id        产物 id（UUID；同时是文件名前缀）
 * @param fileName  下载时的文件名（取自 HTML 的 {@code <title>}，已清洗）
 * @param sizeBytes 文件字节数，前端用于展示
 */
public record ArtifactRef(String id, String fileName, long sizeBytes) {
}
