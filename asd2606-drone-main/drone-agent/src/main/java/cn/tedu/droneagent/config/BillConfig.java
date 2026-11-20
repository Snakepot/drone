package cn.tedu.droneagent.config;

import cn.tedu.droneagent.bill.BillStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 账单存储的装配（`REQ-AGENT-0025`）。
 *
 * <p>单独一个配置类，而不是塞进 `MemoryConfig`：账单与记忆是两条独立的数据流，
 * 关掉账单不应该影响记忆，反之亦然。
 */
@Configuration
public class BillConfig {

    @Bean
    public BillStore billStore(BillProperties properties, ObjectMapper objectMapper) {
        return new BillStore(properties, objectMapper);
    }
}
