package cn.tedu.droneagent.config;

import cn.tedu.droneagent.rate.RateLimiter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 限流装配（`REQ-AGENT-0030`）。 */
@Configuration
public class RateLimitConfig {

    @Bean
    public RateLimiter rateLimiter(RateLimitProperties properties) {
        return new RateLimiter(properties);
    }
}
