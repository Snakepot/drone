package cn.tedu.droneagent.common;

/**
 * 当前登录用户的 ThreadLocal 口袋。
 *
 * <p>与 drone-server 的同名类一致：拦截器在请求开始时放入、请求结束时清空。
 * <b>必须清空</b> —— Tomcat 线程是复用的，残留的身份会被下一个请求读到，造成越权。
 */
public final class UserContext {

    private static final ThreadLocal<LoginUser> CURRENT = new ThreadLocal<>();

    private UserContext() {
    }

    public static void set(LoginUser user) {
        CURRENT.set(user);
    }

    public static LoginUser get() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }
}
