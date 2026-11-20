package cn.tedu.drone.common;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 声明访问该接口所需的权限。
 *
 * <h2>怎么用</h2>
 * <pre>
 * &#64;RequirePermission("staff:add")
 * &#64;PostMapping("/add")
 * public JsonResult&lt;Void&gt; add(...) { ... }
 * </pre>
 *
 * <p>权限编码来自 permission 表的 code 字段，格式为「模块:操作」。
 * 可以写在方法上，也可以写在 Controller 类上（类上表示该接口全部需要此权限）。
 * 写多个值表示「同时具备」这些权限才放行。
 *
 * <h2>谁来校验</h2>
 * {@link cn.tedu.drone.interceptor.AuthInterceptor} 在 Token 校验通过后，
 * 会读取目标方法上的本注解，与当前用户的权限集合比对，不满足返回 403。
 * <p>拦截器能读到注解，靠的是 {@code preHandle} 的 {@code handler} 参数可以转成
 * {@code HandlerMethod} —— 这也是本项目用拦截器而不是过滤器做鉴权的原因。
 */
@Target({ ElementType.METHOD, ElementType.TYPE })
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RequirePermission {

    /**
     * 需要的权限编码，例如 {@code "staff:add"}。
     * 多个值表示必须同时具备。
     */
    String[] value();
}
