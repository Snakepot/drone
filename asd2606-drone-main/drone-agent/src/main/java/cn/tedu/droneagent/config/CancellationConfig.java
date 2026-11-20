package cn.tedu.droneagent.config;

import cn.tedu.droneagent.cancel.CancellationRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 取消注册表装配（`REQ-AGENT-0029`）。 */
@Configuration
public class CancellationConfig {

    @Bean
    public CancellationRegistry cancellationRegistry() {
        return new CancellationRegistry();
    }
}
