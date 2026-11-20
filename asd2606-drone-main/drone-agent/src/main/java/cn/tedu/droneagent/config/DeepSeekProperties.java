package cn.tedu.droneagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * DeepSeek 接入参数，对应 application.yml 里的 agent.deepseek.*
 *
 * <p>系统提示词**不在这里** —— 它已迁到 {@code src/main/resources/prompts/assistant/system.md}，
 * 由 {@code PromptCatalog} 加载（见 `REQ-AGENT-0008`）。
 *
 * @param baseUrl          连接地址
 * @param apiKey           API-KEY，从环境变量注入
 * @param model            调用的具体模型
 * @param reasoningEffort  推理强度
 * @param thinkingEnabled  是否开启深度思考
 */
@ConfigurationProperties(prefix = "agent.deepseek")
public record DeepSeekProperties(
        String baseUrl,
        String apiKey,
        String model,
        String reasoningEffort,
        boolean thinkingEnabled
) {

    public boolean configured() {
        return apiKey != null && !apiKey.isBlank();
    }
}
