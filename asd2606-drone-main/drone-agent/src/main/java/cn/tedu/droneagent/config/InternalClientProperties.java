package cn.tedu.droneagent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 调用 drone-server 内部接口的配置，对应 yml 节点 {@code agent.internal}。
 */
@Data
@Component
@ConfigurationProperties(prefix = "agent.internal")
public class InternalClientProperties {

    /** drone-server 的地址，内部接口挂在它下面 */
    private String baseUrl = "http://localhost:8080";

    /** 共享密钥，必须与 drone-server 的 drone.internal.token 一致 */
    private String token;

    /** 单次调用的超时（毫秒）。卡住时不至于把本服务的线程一起拖死 */
    private int timeoutMs = 3000;

    /**
     * RBAC 数据的缓存秒数。
     *
     * <p>这是本服务引入的唯一一处「权限延迟」：drone-server 那边改权限是立即生效的，
     * 但本服务最多要等这么久才会重新拉一次。
     * 调小更实时、对 drone-server 的压力更大；调大则相反。
     * 设为 0 表示不缓存（每次都查）。
     */
    private long cacheSeconds = 60;
}
