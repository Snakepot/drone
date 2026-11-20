package cn.tedu.drone.common;

import lombok.extern.slf4j.Slf4j;

/**
 * 当前登录用户的「随身口袋」，底层是一个 ThreadLocal。
 *
 * <h2>为什么需要它</h2>
 * 拦截器校验完 Token、知道「你是谁」之后，Controller 和 Service 往往也想用这个身份
 * （比如「查出我自己的信息」）。如果靠方法参数一层层往下传，代码会很啰嗦。
 * 于是拦截器把用户信息往这个「口袋」里一放，业务代码在任何地方都能直接取出来。
 *
 * <h2>ThreadLocal 是什么</h2>
 * 可以理解成「每个线程各有一个储物柜」。Tomcat 为每个请求分配一个线程，
 * 所以 A 请求放进去的信息，B 请求读不到，天然隔离、互不干扰。
 *
 * <h2>怎么通过日志直观理解（配合本类的 info 日志看）</h2>
 * 本类的 set / get / clear 都会打印日志，并带上当前线程的 id 和名称。连续发几次请求就会发现：
 * <ul>
 *   <li>同一次请求的「拦截器 → Controller → Service → 请求结束」，
 *       打印出来的线程标识始终是同一个 —— 因为 Tomcat 把一个请求交给一个线程处理到底</li>
 *   <li>不同请求的线程标识通常不同（线程池里换了另一个线程来接）</li>
 *   <li>正因为「一个请求一个线程」，把数据放进 ThreadLocal 才不会串号</li>
 * </ul>
 *
 * <h2>为什么必须 clear（重点）</h2>
 * Tomcat 的线程是<b>复用</b>的：处理完一个请求不会销毁，而是回到线程池等着接下一个。
 * 如果请求结束时不清空口袋，A 用户的身份就会残留在这个线程上，
 * 被下一个恰好复用到该线程的请求（可能是另一个用户）读到 ——
 * 这是非常隐蔽的越权漏洞，而且很难复现。
 * <p>所以 {@link cn.tedu.drone.interceptor.AuthInterceptor} 会在请求结束时调用
 * {@link #clear()}，这件事不能省。
 *
 * @see cn.tedu.drone.interceptor.AuthInterceptor 写入与清理本上下文的地方
 */
@Slf4j
public final class UserContext {

    /** 每个线程各自的储物柜，存当前请求的登录用户 */
    private static final ThreadLocal<LoginUser> CURRENT = new ThreadLocal<>();

    /** 工具类不需要被实例化 */
    private UserContext() {
    }

    /**
     * 当前线程的标识，形如 {@code 线程id=31/http-nio-8080-exec-1}。
     *
     * <p>只用于打印教学日志：把同一次请求各环节的日志连起来看，
     * 线程标识一致就说明「从头到尾都是同一个线程在干活」。
     */
    public static String threadLabel() {
        Thread thread = Thread.currentThread();
        return "线程id=" + thread.getId() + "/" + thread.getName();
    }

    /**
     * 放入当前登录用户，由拦截器在 Token 校验通过后调用。
     */
    public static void set(LoginUser user) {
        CURRENT.set(user);
        log.info("  └─ ThreadLocal.set() [{}] 存入登录人：{}", threadLabel(), user.getUsername());
    }

    /**
     * 取出当前登录用户，业务代码随时可调。
     *
     * @return 当前登录用户；若该请求未经过拦截器（例如放行的白名单接口）则为 null
     */
    public static LoginUser get() {
        LoginUser user = CURRENT.get();
        log.info("  └─ ThreadLocal.get() [{}] 取出：{}", threadLabel(),
                user == null ? "null（当前线程没存过）" : user.getUsername());
        return user;
    }

    /**
     * 清空当前线程的用户信息，由拦截器在请求结束时调用，防止线程复用时数据串台。
     */
    public static void clear() {
        log.info("  └─ ThreadLocal.clear() [{}] 清空当前线程的口袋", threadLabel());
        CURRENT.remove();
    }
}
