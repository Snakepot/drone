package cn.tedu.droneagent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * JWT 配置，对应的 yml 节点与 drone-server 完全相同（{@code drone.jwt}）。
 *
 * <h2>为什么节点名要一模一样</h2>
 * 本服务不签发 Token，只验签。要让 drone-server 签发的 Token 在这里通过校验，
 * 必须满足两个条件：<b>密钥相同</b>、<b>签发者相同</b>。
 * 沿用同一个配置节点，是为了让「部署时两个服务必须填同一份值」这件事一眼可见 ——
 * 换成自己的节点名反而容易漏配，表现为「明明登录了却一直 401」。
 *
 * <p>密钥通过环境变量 {@code JWT_SECRET} 注入，两个服务填同一个值。
 */
@Data
@Component
@ConfigurationProperties(prefix = "drone.jwt")
public class JwtProperties {

    /** 签名密钥。必须与 drone-server 的 drone.jwt.secret 完全一致 */
    private String secret;

    /** 签发者，会校验 Token 的 iss 字段。必须与 drone-server 的 issuer 一致 */
    private String issuer = "drone-server";
}
