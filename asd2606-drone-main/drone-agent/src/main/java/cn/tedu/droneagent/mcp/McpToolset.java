package cn.tedu.droneagent.mcp;

import cn.tedu.droneagent.config.McpProperties;
import cn.tedu.droneagent.tool.Tool;
import cn.tedu.droneagent.tool.ToolDescriptor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * 启动期的 **MCP 工具发现与聚合**：连接配置里的每个服务器，把远程工具包装成
 * 本地 {@link Tool}，同时生成对应的 {@link ToolDescriptor}。
 *
 * <h2>为什么行为和元数据要在这里「成对」产出</h2>
 * {@code ToolRegistry} 的约定是「有一个行为就必须有一条元数据，反之亦然」，对不上就启动失败。
 * 远程工具的行为与元数据都来自同一次 {@code tools/list}，只要**成对添加**，
 * 这个 1:1 约束自然成立；把两者分开处理（例如元数据另写一份配置）才会出现半套。
 *
 * <h2>为什么单个服务器失败不影响其它</h2>
 * 地图服务不可用不应该让整个助手起不来。默认策略是**降级**：
 * 记一条 WARN，跳过这一家，其余服务器与本地工具照常工作。
 * 需要严格模式时把 {@code agent.mcp.fail-fast} 打开。
 *
 * <h2>日志怎么读</h2>
 * 每个服务器都有「开始发现 → 完成/失败」两条，带耗时；最后一条汇总。
 * 排查「为什么模型看不到某个工具」时，先看该服务器是否出现在开始日志里：
 * 没出现 = 配置被跳过（未启用/没配 key）；出现了但失败 = 网络或协议问题。
 */
@Slf4j
public class McpToolset {

    /** 暴露给模型的函数名限制（与 OpenAI 兼容接口一致） */
    private static final Pattern TOOL_NAME = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    private final List<Tool> tools;
    private final List<ToolDescriptor> descriptors;

    private McpToolset(List<Tool> tools, List<ToolDescriptor> descriptors) {
        this.tools = tools;
        this.descriptors = descriptors;
    }

    /** 远程工具包装后的行为列表，供 {@code ToolRegistry} 合并 */
    public List<Tool> tools() {
        return tools;
    }

    /** 与 {@link #tools()} 一一对应的元数据，供 {@code ToolRegistry} / {@code ToolGovernance} 使用 */
    public List<ToolDescriptor> descriptors() {
        return descriptors;
    }

    /**
     * 执行发现。**任何情况下都不应把「可降级的问题」抛出去**（除非 fail-fast）。
     */
    public static McpToolset discover(McpProperties properties, ObjectMapper objectMapper) {
        if (properties == null || !properties.enabled()) {
            log.info("【MCP】总开关关闭，跳过工具发现；本次只有本地工具可用");
            return empty();
        }
        List<McpProperties.Server> servers =
                properties.servers() == null ? List.of() : properties.servers();
        if (servers.isEmpty()) {
            log.info("【MCP】未配置任何服务器，跳过工具发现");
            return empty();
        }

        Duration budget = properties.startupBudget() == null ? Duration.ofSeconds(8) : properties.startupBudget();
        long deadline = System.nanoTime() + budget.toNanos();

        List<Tool> tools = new ArrayList<>();
        List<ToolDescriptor> descriptors = new ArrayList<>();
        Set<String> aliases = new HashSet<>();
        Set<String> exposedNames = new HashSet<>();

        log.info("【MCP】开始发现工具：共 {} 个服务器，总预算 {}ms", servers.size(), budget.toMillis());

        for (McpProperties.Server server : servers) {
            if (!server.usable()) {
                log.warn("【MCP】跳过服务器：alias={}，原因={}；"
                                + "后续请求不会调用它的任何工具",
                        server.alias(),
                        server.enabled() ? "未配置 url 或密钥（例如 AMAP_MCP_KEY 为空）" : "配置为 enabled=false");
                continue;
            }
            String alias = server.alias() == null ? "" : server.alias().trim();
            if (alias.isBlank()) {
                log.warn("【MCP】跳过服务器：endpoint={}，原因=缺少 alias（工具名前缀无法生成）",
                        McpSecrets.maskUrl(server.url()));
                continue;
            }
            if (!aliases.add(alias)) {
                // 别名重复是配置错误，与网络无关，直接让启动失败
                throw new IllegalStateException("agent.mcp.servers 里别名重复：「" + alias + "」");
            }
            if (System.nanoTime() > deadline) {
                log.warn("【MCP】启动预算已用尽，跳过剩余服务器：alias={}", alias);
                continue;
            }

            long start = System.nanoTime();
            log.info("【MCP】开始发现：alias={}，endpoint={}", alias, McpSecrets.maskUrl(server.url()));
            try {
                // 一个服务器一个 client：握手拿到的 session 必须由后续 tools/call 复用，
                // 否则需要会话的服务器会认为调用来自另一个「未登录」的客户端。
                // 发现带**有界重试**：容忍 agent 先起、MCP 服务晚几秒就绪的情况。
                Discovery discovery = discoverWithRetry(alias, server, properties, objectMapper, deadline);

                McpClient client = discovery.client();
                List<McpToolInfo> discovered = discovery.tools();
                int kept = 0;
                for (McpToolInfo info : discovered) {
                    String exposed = alias + "__" + info.name();
                    if (!TOOL_NAME.matcher(exposed).matches()) {
                        log.warn("【MCP】忽略工具：{}，原因=名字不符合 {}（远程原名 {}）",
                                exposed, TOOL_NAME.pattern(), info.name());
                        continue;
                    }
                    if (!exposedNames.add(exposed)) {
                        log.warn("【MCP】忽略工具：{}，原因=与已注册的工具重名", exposed);
                        continue;
                    }
                    String schema = validSchema(objectMapper, alias, info);
                    if (schema == null) {
                        continue;
                    }
                    tools.add(new McpToolAdapter(exposed, info.name(), alias, client,
                            server.forwardAuthorization(), properties.logRawPayload()));
                    descriptors.add(new ToolDescriptor(exposed, descriptionOf(info, alias), schema,
                            server.parallelSafe(), info.readOnlyHint()));
                    kept++;
                }
                long cost = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
                log.info("【MCP】发现完成：alias={}，远程 {} 个，按配置保留 {} 个，耗时 {}ms",
                        alias, discovered.size(), kept, cost);
            } catch (RuntimeException e) {
                if (properties.failFast()) {
                    throw new IllegalStateException("MCP 服务器「" + alias + "」发现失败（fail-fast 已开启）", e);
                }
                log.warn("【MCP】发现失败，本次跳过该服务器：alias={}，原因={}；"
                                + "其它服务器与本地工具不受影响，修复后重启即可恢复", alias, e.getMessage());
            }
        }

        log.info("【MCP】工具发现全部完成：注册 {} 个远程工具 {}", descriptors.size(),
                descriptors.stream().map(ToolDescriptor::name).toList());
        return sortedByName(tools, descriptors);
    }

    /**
     * 按工具名排序后返回（`REQ-AGENT-0022`）。
     *
     * <p>工具定义的顺序会进入请求前缀；前缀一变，模型的上下文缓存就整段失效。
     * 服务端 {@code tools/list} 的顺序、多个 MCP 服务器的返回先后都不该影响我们发出去的内容，
     * 所以这里统一按名字排序——同一套工具在任何一次启动里都得到同一个前缀。
     *
     * <p>行为与元数据必须**同步重排**：两者是一一对应的，只排一边会让适配器指错描述。
     */
    static McpToolset sortedByName(List<Tool> tools, List<ToolDescriptor> descriptors) {
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < descriptors.size(); i++) {
            order.add(i);
        }
        order.sort((left, right) ->
                descriptors.get(left).name().compareTo(descriptors.get(right).name()));

        List<Tool> sortedTools = new ArrayList<>(order.size());
        List<ToolDescriptor> sortedDescriptors = new ArrayList<>(order.size());
        for (int index : order) {
            sortedTools.add(tools.get(index));
            sortedDescriptors.add(descriptors.get(index));
        }
        return new McpToolset(List.copyOf(sortedTools), List.copyOf(sortedDescriptors));
    }

    /** 一个服务器的发现结果：client 必须复用，否则会话/连接信息会丢 */
    private record Discovery(McpClient client, List<McpToolInfo> tools) {
    }

    /**
     * 带重试的发现。
     *
     * <p>重试只针对「连不上/超时」这类瞬时问题；总时长仍受 {@code startup-budget} 约束，
     * 预算用尽就不再等。最后一次失败照常交给上层决定是降级还是 fail-fast。
     */
    private static Discovery discoverWithRetry(String alias, McpProperties.Server server,
                                               McpProperties properties, ObjectMapper objectMapper,
                                               long deadline) {
        int attempts = Math.max(1, properties.discoveryAttempts());
        Duration delay = properties.discoveryRetryDelay() == null
                ? Duration.ofSeconds(2) : properties.discoveryRetryDelay();
        RuntimeException last = null;

        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                McpClient client = clientOf(alias, server, properties.logRawPayload(), objectMapper);
                client.initialize();
                List<McpToolInfo> discovered = client.listTools().stream()
                        .filter(info -> keep(info.name(), server))
                        .toList();
                return new Discovery(client, discovered);
            } catch (RuntimeException e) {
                last = e;
                log.warn("【MCP】发现未成功（第 {}/{} 次）：alias={}，原因={}",
                        attempt, attempts, alias, e.getMessage());
                boolean canRetry = attempt < attempts && System.nanoTime() < deadline;
                if (!canRetry) {
                    break;
                }
                sleep(delay);
            }
        }
        throw last;
    }

    private static void sleep(Duration delay) {
        try {
            Thread.sleep(delay.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static McpClient clientOf(String alias, McpProperties.Server server,
                                      boolean logRawPayload, ObjectMapper objectMapper) {
        // 每个服务器一个独立的传输实例：会话 id、超时、请求头都不共享
        McpTransport transport = new StreamableHttpTransport(
                server.url(), server.connectTimeout(), server.requestTimeout(),
                server.headers(), objectMapper, logRawPayload);
        return new McpClient(alias, transport, objectMapper);
    }

    /** exclude 优先于 include；include 为空表示「除 exclude 外全部」 */
    private static boolean keep(String remoteName, McpProperties.Server server) {
        List<String> exclude = server.exclude() == null ? List.of() : server.exclude();
        if (exclude.contains(remoteName)) {
            return false;
        }
        List<String> include = server.include() == null ? List.of() : server.include();
        return include.isEmpty() || include.contains(remoteName);
    }

    /** Schema 必须是 JSON 对象；单个工具写坏了就跳过它，不拖累整批 */
    private static String validSchema(ObjectMapper objectMapper, String alias, McpToolInfo info) {
        try {
            JsonNode node = objectMapper.readTree(info.inputSchemaJson());
            if (node == null || !node.isObject()) {
                log.warn("【MCP】忽略工具：{}__{}，原因=inputSchema 不是 JSON 对象", alias, info.name());
                return null;
            }
            return info.inputSchemaJson();
        } catch (Exception e) {
            log.warn("【MCP】忽略工具：{}__{}，原因=inputSchema 无法解析：{}", alias, info.name(), e.getMessage());
            return null;
        }
    }

    private static String descriptionOf(McpToolInfo info, String alias) {
        String description = info.description() == null ? "" : info.description().trim();
        if (description.isEmpty()) {
            return "来自 MCP 服务器「" + alias + "」的远程工具";
        }
        return description;
    }

    private static McpToolset empty() {
        return new McpToolset(List.of(), List.of());
    }
}
