package cn.tedu.droneagent.common;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 声明访问该接口所需的权限码，与 drone-server 的注解同名同语义。
 *
 * <p>权限码来自 drone-server 的 permission 表，格式为「模块:操作」。
 * 可写在方法或类上，多个值表示「同时具备」。
 */
@Target({ ElementType.METHOD, ElementType.TYPE })
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RequirePermission {

    /** 需要的权限编码，例如 {@code "agent:chat"}。多个值表示必须同时具备 */
    String[] value();
}
