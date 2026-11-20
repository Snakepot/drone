package cn.tedu.droneagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 业务流程 Skill 的配置，对应 yml 的 {@code agent.skill.*}。
 *
 * @param enabled          总开关；关掉后两个工具仍存在（注册表要求行为与元数据 1:1），
 *                         但目录为空，调用会返回「没有可用的标准化流程」
 * @param maxBodyChars     单个 SKILL.md 正文上限；超出即启动失败（细节应放 references/）
 * @param maxResourceChars 单次读取参考资料的上限；超出截断并标注
 */
@ConfigurationProperties(prefix = "agent.skill")
public record SkillProperties(
        boolean enabled,
        int maxBodyChars,
        int maxResourceChars
) {
}
