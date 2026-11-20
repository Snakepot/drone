package cn.tedu.droneagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * HTML 产物存储配置，对应 yml 的 {@code agent.artifact.*}。
 *
 * @param root     落盘根目录；按 {@code {root}/{userId}/} 分目录，归属即权限边界
 * @param maxBytes 单个产物上限；超过视为「不是产物」，不抽取、原样返回回复
 * @param ttl      产物保留时长；启动与写入时顺手清理过期文件
 */
@ConfigurationProperties(prefix = "agent.artifact")
public record ArtifactProperties(
        String root,
        long maxBytes,
        Duration ttl
) {
}
