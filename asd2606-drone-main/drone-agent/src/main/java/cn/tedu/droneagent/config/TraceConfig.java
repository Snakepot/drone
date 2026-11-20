package cn.tedu.droneagent.config;

import cn.tedu.droneagent.trace.TraceRecorder;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

/**
 * 链路追踪的装配。
 *
 * <p>与记忆/产物同思路：目录来自配置、运行时按需创建；{@code data/} 已被 .gitignore 覆盖。
 */
@Configuration
public class TraceConfig {

    @Bean
    public TraceRecorder traceRecorder(TraceProperties properties, ObjectMapper objectMapper) {
        if (!properties.enabled()) {
            return TraceRecorder.noop();
        }
        String root = properties.root() == null || properties.root().isBlank()
                ? "./data/traces" : properties.root();
        return new TraceRecorder(Path.of(root), properties.ttl(), objectMapper, true);
    }
}
