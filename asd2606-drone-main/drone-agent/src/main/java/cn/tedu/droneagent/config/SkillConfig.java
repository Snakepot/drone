package cn.tedu.droneagent.config;

import cn.tedu.droneagent.skill.ReadSkillResourceTool;
import cn.tedu.droneagent.skill.SkillCatalog;
import cn.tedu.droneagent.skill.SkillToolValidator;
import cn.tedu.droneagent.skill.UseSkillTool;
import cn.tedu.droneagent.tool.ToolRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 业务流程 Skill 的装配。
 *
 * <h2>为什么两个工具在关闭时也要注册</h2>
 * {@code ToolRegistry} 要求「行为 ↔ 元数据」严格 1:1。开关关闭时如果连 Bean 都不建，
 * 配置里的元数据就会变成「有元数据没行为」，服务起不来。所以工具始终存在，
 * 只是目录为空、调用时返回「没有可用的标准化流程」。
 */
@Slf4j
@Configuration
public class SkillConfig {

    /** 扫描路径：包含 SKILL.md 与 references/ 下的资料 */
    private static final String SKILL_PATTERN = "classpath*:skills/**/*";

    @Bean
    public SkillCatalog skillCatalog(SkillProperties properties) {
        if (!properties.enabled()) {
            log.info("【Skill】总开关关闭，流程目录为空；use_skill 会返回「暂无」");
            return SkillCatalog.empty();
        }
        return SkillCatalog.load(SKILL_PATTERN, properties.maxBodyChars());
    }

    @Bean
    public UseSkillTool useSkillTool(SkillCatalog catalog) {
        return new UseSkillTool(catalog);
    }

    @Bean
    public ReadSkillResourceTool readSkillResourceTool(SkillCatalog catalog, SkillProperties properties) {
        return new ReadSkillResourceTool(catalog, properties.maxResourceChars());
    }

    /**
     * 校验流程引用的工具。依赖 {@link ToolRegistry}，因此一定在注册表建好之后执行。
     */
    @Bean
    public SkillToolValidator skillToolValidator(SkillCatalog catalog, ToolRegistry registry) {
        return new SkillToolValidator(catalog, registry);
    }
}
