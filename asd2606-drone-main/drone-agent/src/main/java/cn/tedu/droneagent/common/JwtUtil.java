package cn.tedu.droneagent.common;

import cn.tedu.droneagent.config.JwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;

/**
 * JWT 解析工具。本服务只做一件事：<b>校验并取出载荷</b>，不签发 Token ——
 * Token 统一由 drone-server 在登录时签发，避免两处都发、口径跑偏。
 *
 * <p>因此这里的密钥与 drone-server 是同一把，见 {@link JwtProperties}。
 */
@Component
public class JwtUtil {

    private final JwtProperties properties;
    private final SecretKey secretKey;

    public JwtUtil(JwtProperties properties) {
        this.properties = properties;
        if (properties.getSecret() == null || properties.getSecret().isBlank()) {
            // 快速失败：密钥没配，验签必然全挂，与其每个请求报 401 不如启动就报出来
            throw new IllegalStateException(
                    "未配置 drone.jwt.secret（应与 drone-server 一致），请通过 JWT_SECRET 环境变量注入");
        }
        this.secretKey = Keys.hmacShaKeyFor(properties.getSecret().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 校验签名、有效期与签发者，返回载荷。
     *
     * @param token 已去掉 "Bearer " 前缀的 Token
     * @throws io.jsonwebtoken.JwtException 签名不符、已过期或签发者不对
     */
    public Claims parseToken(String token) {
        return Jwts.parser()
                .verifyWith(secretKey)
                // 顺带校验签发者：只有 drone-server 签的 Token 才认
                .requireIssuer(properties.getIssuer())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
