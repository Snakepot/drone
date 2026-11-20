package cn.tedu.drone.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * JWT 配置项，对应 application.yml 里的 {@code drone.jwt} 节点。
 *
 * <h2>为什么不把密钥直接写死在代码里</h2>
 * 密钥属于机密信息。写死在代码里就会被提交到 Git 仓库，等于把印章放在公共桌上 ——
 * 任何能看到代码的人都能签发合法 Token。
 * 抽成配置后，就可以在部署时通过环境变量 {@code JWT_SECRET} 注入真实密钥：
 * 开发环境用默认值图方便，生产环境用环境变量保证安全，两不相干。
 *
 * <pre>
 * drone:
 *   jwt:
 *     secret: ${JWT_SECRET:开发环境的默认值}
 *     expire-hours: 2
 *     issuer: drone-server
 * </pre>
 *
 * @see cn.tedu.drone.common.JwtUtil 使用这些配置签发与解析 Token
 */
@Data
@Component
@ConfigurationProperties(prefix = "drone.jwt")
public class JwtProperties {

    /** 签名密钥。HS256 算法要求至少 256 位（32 字节），配得太短会启动失败 */
    private String secret;

    /** Token 有效期（小时）。默认 2 小时，过期后前端需要重新登录 */
    private long expireHours = 2;

    /**
     * 大屏账号（拥有 {@code screen} 角色）的 Token 有效期（小时）。
     *
     * <h2>为什么大屏要单独一个长效期</h2>
     * 大屏挂在值班室/会议室，**没人会去重新登录** ——
     * 用交互式的 2 小时，第二天早上来看到的是一块停在登录页的屏。
     *
     * <p>默认 8760 小时 = 365 天。要收紧就调小（比如 720 小时 = 30 天，
     * 运维每月登录一次）；调小不需要改代码。
     *
     * <p>⚠️ 有效期**由服务端按角色决定，不接受前端传参** ——
     * 前端能要求的有效期，等于谁都能给自己签一年。
     */
    private long screenExpireHours = 8760;

    /** 签发者标识，会写进 Token 的 iss 字段，用于标明这个 Token 由谁签发 */
    private String issuer = "drone-server";
}
