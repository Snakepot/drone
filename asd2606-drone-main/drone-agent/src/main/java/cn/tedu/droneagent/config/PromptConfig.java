package cn.tedu.droneagent.config;

import cn.tedu.droneagent.prompt.PromptCatalog;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 提示词的装配。
 *
 * <h2>为什么单独一个配置类</h2>
 * 与 {@link MemoryConfig} / {@link ToolConfig} 同理：加载依赖「资源位置」这一个显式参数，
 * 摆在这里一眼看清；测试也能用同样方式手工造一个 {@link PromptCatalog}。
 *
 * <h2>校验发生在**启动期**</h2>
 * {@link PromptCatalog#load} 会读一遍目录并逐条校验（非空 / 名字与路径一致 / 变量与占位符相符…）；
 * **任何一条不合格，服务直接起不来**，不留到运行时。
 */
@Configuration
public class PromptConfig {

    /** 提示词位置：{@code src/main/resources/prompts/<业务>/<名字>.md} */
    private static final String PROMPTS_LOCATION = "classpath*:prompts/**/*.md";

    @Bean
    public PromptCatalog promptCatalog() {
        return PromptCatalog.load(PROMPTS_LOCATION);
    }
}
