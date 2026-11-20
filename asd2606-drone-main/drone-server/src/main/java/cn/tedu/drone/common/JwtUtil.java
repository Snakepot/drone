package cn.tedu.drone.common;

import cn.tedu.drone.config.JwtProperties;
import cn.tedu.drone.user.pojo.UserPO;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * JWT 工具类，只负责两件事：<b>签发 Token</b> 和 <b>解析 Token</b>。
 *
 * <h2>一、Token 长什么样</h2>
 * 一段 Token 由三部分组成，中间用英文句点 {@code .} 连接：
 * <pre>
 *   header . payload . signature
 *   （头部）  （载荷）    （签名）
 * </pre>
 * <ul>
 *   <li><b>header</b>：声明用了哪种签名算法，本项目是 HS256</li>
 *   <li><b>payload</b>：真正装数据的地方，比如「用户 ID 是 1、角色是 admin、2 小时后过期」</li>
 *   <li><b>signature</b>：用密钥算出来的防伪标记，改了前面任何一个字符它都对不上</li>
 * </ul>
 *
 * <h2>二、初学者最容易误解的两点</h2>
 * <ol>
 *   <li><b>payload 不是加密的</b>。它只是 Base64 编码，把 Token 复制到解码网站上就能看到内容。
 *       所以绝对不能往里放密码、手机号这类敏感信息。</li>
 *   <li><b>安全性全靠 signature</b>，而它由下面的 {@code secretKey} 算出来。
 *       密钥一旦泄露，别人就能伪造出任意合法 Token。所以密钥只能待在服务端，
 *       生产环境要通过环境变量注入，绝不能提交到代码仓库。</li>
 * </ol>
 *
 * <h2>三、这个类被谁使用</h2>
 * <ul>
 *   <li>登录成功后，{@link cn.tedu.drone.user.service.impl.UserServiceImpl} 调用
 *       {@link #generateToken} 签发 Token</li>
 *   <li>每次请求进来时，{@link cn.tedu.drone.interceptor.AuthInterceptor} 调用
 *       {@link #parseToken} 校验 Token</li>
 * </ul>
 *
 * @see cn.tedu.drone.config.JwtProperties 密钥与有效期的配置来源
 */
@Slf4j
@Component
public class JwtUtil {

    /** JWT 配置（密钥、有效期、签发者） */
    private final JwtProperties properties;

    /**
     * 真正用来签名和验签的密钥。
     * <p>在构造方法里由配置的字符串转换而来，整个应用只创建一次。
     * HS256 要求密钥至少 256 位（32 字节），配得太短会在启动时直接报错。
     */
    private final SecretKey secretKey;

    public JwtUtil(JwtProperties properties) {
        this.properties = properties;
        this.secretKey = Keys.hmacShaKeyFor(properties.getSecret().getBytes(StandardCharsets.UTF_8));

        log.info("JWT 初始化完成，签名算法=HS256，Token 有效期={}小时，签发者={}",
                properties.getExpireHours(), properties.getIssuer());
    }

    /**
     * 为登录成功的用户签发一个 Token。
     *
     * <p>Token 里只放两样东西：
     * <ul>
     *   <li>{@code sub}：用户 ID，JWT 标准字段，后续靠它识别「你是谁」</li>
     *   <li>{@code username}：用户名，方便排查问题时直观看出来</li>
     *   <li>{@code iss} / {@code iat} / {@code exp}：签发者、签发时间、过期时间</li>
     * </ul>
     *
     * <p><b>角色和权限刻意不放进 Token</b>，而是校验时查库（见 docs/RBAC权限设计.md）。
     * 这样管理员改了某人的角色，下次请求就生效，不用等他重新登录；
     * 代价是每个受保护请求多一次关联查询 —— 就是「无状态」与「实时性」的取舍。
     *
     * @param user 登录成功的用户，必须带有 id 与 username
     * @return Token 字符串，可直接返回给前端保存
     */
    public String generateToken(UserPO user) {
        return generateToken(user, properties.getExpireHours());
    }

    /**
     * 按指定的小时数签发 Token。
     *
     * <p>存在的唯一理由是**大屏**：它有 {@code screen} 只读角色，
     * 挂在值班室没人会去重新登录，所以给它一个长效期
     * （见 {@link cn.tedu.drone.config.JwtProperties#getScreenExpireHours()}）。
     *
     * <p>⚠️ 「用哪个时长」这个判断**在调用方（登录流程）里做**，
     * 不在这里 —— 这里只负责「按给的时长签」。
     */
    public String generateToken(UserPO user, long expireHours) {
        Date issuedAt = new Date();
        // 过期时间 = 签发时间 + 有效小时数（3600_000 毫秒 = 1 小时）
        Date expiration = new Date(issuedAt.getTime() + expireHours * 3600_000L);

        String token = Jwts.builder()
                .subject(String.valueOf(user.getId()))   // sub：用户 ID
                .claim("username", user.getUsername())   // 自定义字段：用户名
                .issuer(properties.getIssuer())          // iss：签发者
                .issuedAt(issuedAt)                      // iat：签发时间
                .expiration(expiration)                  // exp：过期时间
                .signWith(secretKey, Jwts.SIG.HS256)     // 显式指定 HS256 签名
                .compact();

        // 注意：日志只记录用户身份和有效期，绝不打印 Token 原文，更不打印密码
        log.debug("Token 签发完成，userId={}，签发时间={}，过期时间={}",
                user.getId(), issuedAt, expiration);

        return token;
    }

    /**
     * 校验 Token 的签名与有效期，并取出里面的数据。
     *
     * <p>校验过程（全程不需要查数据库）：
     * <ol>
     *   <li>把 Token 拆成 header、payload、signature 三段</li>
     *   <li>用服务端自己手里的 {@code secretKey} 重新算一遍签名</li>
     *   <li>和 Token 里带的签名比对，一致说明既没被篡改、也不是伪造的</li>
     *   <li>再看 {@code exp} 判断有没有过期</li>
     * </ol>
     *
     * <p>任何一步不通过都会抛异常：
     * <ul>
     *   <li>{@link ExpiredJwtException}：Token 已过期，需要重新登录</li>
     *   <li>其他 {@link JwtException}：签名对不上（被篡改或伪造）或格式不正确</li>
     * </ul>
     *
     * @param token 前端放在请求头里的 Token（已去掉 "Bearer " 前缀）
     * @return 校验通过的结果，可用 {@code getPayload()} 取出里面的数据
     * @throws JwtException Token 不合法或已过期
     */
    public Jws<Claims> parseToken(String token) {
        try {
            Jws<Claims> jws = Jwts.parser()
                    .verifyWith(secretKey)
                    .build()
                    .parseSignedClaims(token);

            log.debug("Token 校验通过，userId={}", jws.getPayload().getSubject());
            return jws;
        } catch (ExpiredJwtException e) {
            // 过期是最常见的失败原因，单独记录并带上过期时间，方便定位
            log.warn("Token 校验失败：已过期，过期时间={}", e.getClaims().getExpiration());
            throw e;
        } catch (JwtException e) {
            // 签名不匹配意味着 Token 被篡改或伪造，属于安全事件
            log.warn("Token 校验失败：签名不合法或格式错误，原因={}", e.getMessage());
            throw e;
        }
    }
}
