package cn.tedu.drone.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class Knife4jConfig {

    @Bean
    public OpenAPI droneOpenAPI() {
        return new OpenAPI().info(new Info()
                .title("无人机管理平台 API")
                .description("drone-server 接口文档，访问 /doc.html 查看")
                .version("v0.0.1"));
    }

    @Bean
    public GroupedOpenApi userGroup() {
        return GroupedOpenApi.builder()
                .group("01-用户模块")
                .pathsToMatch("/user/**")
                .build();
    }

    @Bean
    public GroupedOpenApi deviceGroup() {
        return GroupedOpenApi.builder()
                .group("02-设备模块")
                .pathsToMatch("/device/**")
                .build();
    }
}
