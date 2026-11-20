package cn.tedu.droneagent.common;

/**
 * 取不到 RBAC 数据时抛出（drone-server 未启动、超时、返回非 200 等）。
 *
 * <p>刻意与「Token 不合法」区分开：前者是用户的问题（该去登录），
 * 后者是本服务与 drone-server 之间的问题（用户重新登录也没用）。
 * 拦截器据此返回不同的状态码。
 */
public class AuthoritiesUnavailableException extends RuntimeException {

    public AuthoritiesUnavailableException(String message) {
        super(message);
    }

    public AuthoritiesUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
