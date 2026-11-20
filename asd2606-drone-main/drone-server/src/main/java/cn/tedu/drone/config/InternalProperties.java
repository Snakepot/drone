package cn.tedu.drone.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 服务间内部调用的配置，对应 application.yml 里的 {@code drone.internal} 节点。
 *
 * <p>内部接口（{@code /internal/**}）不面向最终用户，走的是服务间共享密钥，
 * 不使用用户 JWT —— 调用方 drone-agent 手里没有、也不该有用户密码。
 */
@Data
@Component
@ConfigurationProperties(prefix = "drone.internal")
public class InternalProperties {

    /**
     * 内部接口的共享密钥，调用方需放在请求头 {@code X-Internal-Token} 里。
     * 生产环境必须通过环境变量 {@code DRONE_INTERNAL_TOKEN} 注入。
     */
    private String token;
}
