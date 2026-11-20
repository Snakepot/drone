package cn.tedu.droneagent.config;

import cn.tedu.droneagent.interceptor.AuthInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 注册鉴权拦截器，并声明不需要登录的路径。
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    /**
     * 无需登录即可访问的路径。
     *
     * <p>本服务目前没有登录接口（登录在 drone-server），所以白名单里只有错误页。
     * {@code /error} 必须放行：Controller 抛异常时 Spring Boot 会转发到这里，
     * 若被拦截器拦住，异常会被伪装成 401，排查时会被带偏。
     */
    private static final String[] WHITE_LIST = {
            "/error",
    };

    private final AuthInterceptor authInterceptor;

    public WebMvcConfig(AuthInterceptor authInterceptor) {
        this.authInterceptor = authInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns(WHITE_LIST);
    }
}
