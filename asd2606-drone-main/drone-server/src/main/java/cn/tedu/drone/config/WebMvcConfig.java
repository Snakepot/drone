package cn.tedu.drone.config;

import cn.tedu.drone.interceptor.AuthInterceptor;
import cn.tedu.drone.internal.InternalAuthInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web 层配置：把登录校验拦截器注册到 Spring MVC，并声明哪些路径不需要登录。
 *
 * <p>拦截器类上加了 {@code @Component} 只是让它成为一个 Spring Bean，
 * 真正让它对请求生效，还得在这里注册、并指定拦截范围。
 *
 * <h2>白名单是什么</h2>
 * 拦截器默认会拦住所有请求，但有些请求本来就不该要求登录，比如：
 * <ul>
 *   <li>登录接口本身 —— 用户还没登录，怎么可能带着 Token 来登录？</li>
 *   <li>接口文档页面 —— 开发调试用，需要登录会很麻烦</li>
 * </ul>
 * 这些路径写在下面的 {@code WHITE_LIST} 里，会被排除在拦截之外。
 *
 * <h2>如果新增了「免登录」的接口</h2>
 * 记得把路径加进 {@code WHITE_LIST}，否则会一直返回 401。
 * 比如以后要接监控，健康检查接口就需要放进来。
 *
 * @see cn.tedu.drone.interceptor.AuthInterceptor 拦截器的具体校验逻辑
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    /**
     * 无需登录即可访问的路径（支持 Ant 风格通配符）
     */
    private static final String[] WHITE_LIST = {
            "/user/login",      // 登录接口：用户还没登录，必须放行
            /*
             * WebSocket 握手。这里必须放行，否则握手请求会先被本拦截器拦下返回 401。
             * 请注意：放行不等于不校验 —— 握手有它自己的一次 JWT 校验，
             * 见 TaskWsHandshakeInterceptor（WebSocket 不走 MVC 拦截器链）。
             */
            "/ws/**",
            "/doc.html",        // Knife4j 文档首页
            "/webjars/**",      // 文档页面依赖的静态资源
            "/v3/api-docs/**",  // OpenAPI 接口描述
            "/swagger-ui/**",   // Swagger UI 资源
            "/swagger-ui.html",
            "/favicon.ico",     // 浏览器标签页图标
            /*
             * 服务间内部接口（drone-agent 调）。
             * 这里放行只是「不要求用户 JWT」—— 调用方是服务，用户根本没登录。
             * 它并不开放：由下面的 InternalAuthInterceptor 用共享密钥把关。
             */
            "/internal/**",
    };

    private final AuthInterceptor authInterceptor;

    private final InternalAuthInterceptor internalAuthInterceptor;

    public WebMvcConfig(AuthInterceptor authInterceptor, InternalAuthInterceptor internalAuthInterceptor) {
        this.authInterceptor = authInterceptor;
        this.internalAuthInterceptor = internalAuthInterceptor;
    }

    /**
     * 注册拦截器。
     *
     * <p>用户 JWT 拦截器拦所有请求、排除白名单；内部密钥拦截器只拦 {@code /internal/**}。
     * 两个拦截器的职责分开，各自管一种凭据。
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns(WHITE_LIST);

        registry.addInterceptor(internalAuthInterceptor)
                .addPathPatterns("/internal/**");
    }
}
