package cn.tedu.droneagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * MCP（Model Context Protocol）接入配置，对应 yml 节点 {@code agent.mcp.*}。
 *
 * <h2>为什么是「服务器列表」而不是单个地址</h2>
 * 需求明确要求可扩展：以后接第二个、第三个 MCP Server 时**只加配置、不改代码**。
 * 所以这里从一开始就是 {@code List<Server>}，每个元素互不影响：
 * 一个连不上，其余照常发现工具（见 {@code McpToolset}）。
 *
 * <h2>为什么放在 {@code config} 包</h2>
 * {@code DroneAgentApplication} 上用的是
 * {@code @ConfigurationPropertiesScan("cn.tedu.droneagent.config")}——
 * 把配置类放进别的包，Spring **不会报错，只会静默不绑定**，这是很难查的一类问题。
 *
 * <h2>字段说明（初级工程师速查）</h2>
 * <ul>
 *   <li>{@code enabled}：整套 MCP 的总开关。关掉后本地工具照常工作，等同于没接过 MCP。</li>
 *   <li>{@code failFast}：发现失败时是「启动失败」还是「降级跳过」。默认 false。</li>
 *   <li>{@code startupBudget}：所有 MCP 服务器发现的总时间预算。超出即放弃剩余部分，
 *       避免一个黑洞地址把启动拖成几十秒。</li>
 *   <li>{@code servers}：服务器列表，见 {@link Server}。</li>
 * </ul>
 *
 * @param enabled       总开关
 * @param failFast      发现失败是否让服务启动失败；false 时只记 WARN 并跳过该服务器
 * @param startupBudget 启动期发现的总预算
 * @param discoveryAttempts 每个服务器的发现尝试次数（含首次）。用于容忍「agent 先起、MCP 服务晚几秒就绪」
 * @param discoveryRetryDelay 两次发现之间的间隔
 * @param logRawPayload 是否把 MCP 的**原始请求体与响应体**打进日志。
 *                      排障时非常有用，但报文里含业务数据（任务详情、位置等），**生产建议关闭**。
 *                      无论如何都不会打印 `Authorization` 请求头。
 * @param servers       MCP 服务器列表
 */
@ConfigurationProperties(prefix = "agent.mcp")
public record McpProperties(
        boolean enabled,
        boolean failFast,
        Duration startupBudget,
        int discoveryAttempts,
        Duration discoveryRetryDelay,
        boolean logRawPayload,
        List<Server> servers
) {

    /**
     * 一个 MCP 服务器。
     *
     * @param alias          别名，同时是工具名前缀（`别名__远程工具名`）。**必须唯一**，
     *                       重复会让启动失败——否则两个服务器的同名工具会互相覆盖
     * @param url            MCP 端点。密钥建议放在 query 里并用环境变量占位，例如
     *                       {@code https://mcp.amap.com/mcp?key=${AMAP_MCP_KEY:}}；
     *                       URL 为空或含空 key 时，该服务器会被跳过
     * @param enabled        该服务器单独的开关，便于临时下线一家而不动全局
     * @param connectTimeout 建连超时
     * @param requestTimeout 单次 RPC 超时。**必须小于** {@code agent.tool.invoke-timeout}，
     *                       否则编排层会先超时，日志里看到的是「工具超时」而不是真实原因
     * @param headers        额外请求头（例如某些服务要求 `Authorization`）。密钥不要写死在这里
     * @param include        工具白名单（远程原名）。为空表示「除 exclude 外全部」
     * @param exclude        工具黑名单（远程原名），优先级高于 include
     * @param parallelSafe   这批远程工具是否允许与本轮其他只读工具并发执行
     * @param forwardAuthorization 是否把调用方的 JWT 以 `Authorization: Bearer …` 转发给该服务器。
     *                       仅对**以用户身份操作业务**的本地 MCP 服务（如 `drone-mcp-server`）开启；
     *                       高德这类第三方服务保持 false，避免把用户凭据发给外部
     */
    public record Server(
            String alias,
            String url,
            boolean enabled,
            Duration connectTimeout,
            Duration requestTimeout,
            Map<String, String> headers,
            List<String> include,
            List<String> exclude,
            boolean parallelSafe,
            boolean forwardAuthorization
    ) {

        /**
         * 这个服务器是否值得去连。
         *
         * <p>两种情况直接跳过、且**不算错误**：
         * <ol>
         *   <li>配置里关掉了它；</li>
         *   <li>URL 为空，或 query 里的 {@code key=} 后面是空的——说明环境变量没配。</li>
         * </ol>
         * 第二种是刻意留的：本地开发不配密钥时，服务应当照常启动、只是没有地图工具，
         * 而不是启动失败。
         */
        public boolean usable() {
            if (!enabled || url == null || url.isBlank()) {
                return false;
            }
            String value = url.trim();
            int keyAt = value.indexOf("key=");
            if (keyAt >= 0) {
                int start = keyAt + "key=".length();
                int end = value.indexOf('&', start);
                String key = end < 0 ? value.substring(start) : value.substring(start, end);
                return !key.isBlank();
            }
            return true;
        }
    }
}
