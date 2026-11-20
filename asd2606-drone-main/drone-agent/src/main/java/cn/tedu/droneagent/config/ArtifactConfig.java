package cn.tedu.droneagent.config;

import cn.tedu.droneagent.artifact.ArtifactExtractor;
import cn.tedu.droneagent.artifact.ArtifactService;
import cn.tedu.droneagent.artifact.ArtifactStore;
import cn.tedu.droneagent.artifact.DefaultArtifactService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

/**
 * HTML 产物的装配。
 *
 * <p>与记忆/知识库同思路：目录来自配置、运行时按需创建；该目录在 {@code data/} 下，
 * 已被 .gitignore 覆盖。
 */
@Configuration
public class ArtifactConfig {

    @Bean
    public ArtifactExtractor artifactExtractor(ArtifactProperties properties) {
        return new ArtifactExtractor(properties.maxBytes());
    }

    @Bean
    public ArtifactStore artifactStore(ArtifactProperties properties, ObjectMapper objectMapper) {
        return new ArtifactStore(Path.of(properties.root()), properties.ttl(), objectMapper);
    }

    @Bean
    public ArtifactService artifactService(ArtifactExtractor extractor, ArtifactStore store) {
        return new DefaultArtifactService(extractor, store);
    }
}
