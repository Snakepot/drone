package cn.tedu.drone.task.ws;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * 把 WebSocket 端点挂到 Spring 上。
 *
 * <p>三个要素：<b>路径</b>（{@code /ws/task}）、<b>处理器</b>（{@link TaskWsHandler}）、
 * <b>握手拦截器</b>（{@link TaskWsHandshakeInterceptor}，负责校验 token）。
 *
 * <h2>为什么握手要单独鉴权</h2>
 * WebSocket 的握手虽然是一次 HTTP 请求，但它不走 Spring MVC 的拦截器链，
 * 所以 {@code AuthInterceptor} 管不到它 —— 必须在这里挂一个自己的拦截器。
 * 配套地，{@code /ws/**} 要加进 MVC 白名单（见 {@code WebMvcConfig}），
 * 否则握手请求会先被 MVC 拦下。
 *
 * <h2>跨域设置</h2>
 * {@code setAllowedOriginPatterns("*")} 允许任意来源发起握手。
 * <b>这在生产环境是不该这么宽的</b>，这里放开的理由：
 * <ul>
 *   <li>浏览器调试时来源是 {@code http://localhost:5174}，真机小程序没有来源概念</li>
 *   <li>真正的安全边界是握手时校验的 JWT —— 没有有效 token，来源再合法也连不上</li>
 * </ul>
 * 上线前应改成白名单，只允许自己的域名。
 */
@Configuration
@EnableWebSocket
public class TaskWsConfig implements WebSocketConfigurer {

    /** 飞手端连接的通知地址 */
    public static final String WS_PATH = "/ws/task";

    private final TaskWsHandler taskWsHandler;
    private final TaskWsHandshakeInterceptor handshakeInterceptor;

    public TaskWsConfig(TaskWsHandler taskWsHandler, TaskWsHandshakeInterceptor handshakeInterceptor) {
        this.taskWsHandler = taskWsHandler;
        this.handshakeInterceptor = handshakeInterceptor;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(taskWsHandler, WS_PATH)
                .addInterceptors(handshakeInterceptor)
                .setAllowedOriginPatterns("*");
    }
}
