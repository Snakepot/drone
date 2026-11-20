package cn.tedu.droneagent.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具注册处：把「配置文件里的**元数据**」与「代码里的**行为**」按**工具名**合并、校验，
 * 并对上层提供统一入口。
 *
 * <h2>为什么要有它</h2>
 * 以前元数据在代码里，两者天然一致；现在元数据在配置里，就**可能对不上**——
 * 改配置忘了改代码、或反一反。把它收口在这里，**在启动时**就把问题暴露出来（fail fast），
 * 而不是等到某个用户点了、某些工具名找不到才报错。
 *
 * <h2>校验哪四件事</h2>
 * <ol>
 *   <li>行为侧名字非空、不重复；</li>
 *   <li>配置侧名字非空、不重复；</li>
 *   <li>**两边一一对应**：有行为没元数据、或有元数据没行为，都算错；</li>
 *   <li>参数 Schema 能序列化成 JSON。</li>
 * </ol>
 *
 * <h2>它在链路里的位置，以及它不负责什么</h2>
 * <ul>
 *   <li><b>不执行工具</b>：{@link #handler} 只负责「按名字找行为」，执行是 {@link Tool} 的事。</li>
 *   <li><b>不联网、不管模型</b>：它是纯静态的注册与查询。</li>
 * </ul>
 */
public class ToolRegistry {

    /** 配置侧：名字 → 元数据。保持配置里的顺序，发给模型的工具列表顺序才稳定 */
    private final Map<String, ToolDescriptor> descriptors;

    /** 行为侧：名字 → 行为 */
    private final Map<String, Tool> handlers;

    /** 发给模型用的定义（由元数据生成），构造时算好、之后只读 */
    private final List<ToolDefinition> definitions;

    public ToolRegistry(List<Tool> tools, List<ToolDescriptor> catalog, ObjectMapper objectMapper) {
        this.handlers = indexHandlers(tools);
        this.descriptors = indexDescriptors(catalog);
        assertMatched();
        this.definitions = buildDefinitions(objectMapper);
    }

    /** 发给模型的工具定义列表（顺序 = 配置里的顺序） */
    public List<ToolDefinition> definitions() {
        return definitions;
    }

    /** 按名字取行为；没有就返回 null（模型可能报一个我们不认识的工具名） */
    public Tool handler(String name) {
        return handlers.get(name);
    }

    /** 该工具是否只读；未知工具按「写」处理，宁可多保护一层 */
    public boolean isReadOnly(String name) {
        ToolDescriptor descriptor = descriptors.get(name);
        return descriptor != null && descriptor.readOnly();
    }

    /** 该工具是否允许与本轮其他工具并发执行 */
    public boolean isParallelSafe(String name) {
        ToolDescriptor descriptor = descriptors.get(name);
        return descriptor != null && descriptor.parallelSafe();
    }

    /** 工具级超时（毫秒）；未配置返回 -1，由调用方用全局超时 */
    public long timeoutMillis(String name) {
        ToolDescriptor descriptor = descriptors.get(name);
        return descriptor == null || descriptor.timeoutMs() == null
                ? -1 : descriptor.timeoutMs();
    }

    private static Map<String, Tool> indexHandlers(List<Tool> tools) {
        Map<String, Tool> map = new LinkedHashMap<>();
        for (Tool tool : tools) {
            String name = tool.name();
            if (name == null || name.isBlank()) {
                throw new IllegalStateException("存在没有名字的工具行为：" + tool.getClass().getName());
            }
            if (map.putIfAbsent(name, tool) != null) {
                throw new IllegalStateException("工具名重复：有两个行为都叫「" + name + "」");
            }
        }
        return map;
    }

    private static Map<String, ToolDescriptor> indexDescriptors(List<ToolDescriptor> catalog) {
        Map<String, ToolDescriptor> map = new LinkedHashMap<>();
        if (catalog == null) {
            return map;
        }
        for (ToolDescriptor descriptor : catalog) {
            String name = descriptor.name();
            if (name == null || name.isBlank()) {
                throw new IllegalStateException("agent.tool.catalog 里有工具没有写 name");
            }
            if (map.putIfAbsent(name, descriptor) != null) {
                throw new IllegalStateException("agent.tool.catalog 里工具名重复：「" + name + "」");
            }
        }
        return map;
    }

    /** 行为与元数据必须一一对应，缺一边都算配置错误 */
    private void assertMatched() {
        for (String name : handlers.keySet()) {
            if (!descriptors.containsKey(name)) {
                throw new IllegalStateException(
                        "工具「" + name + "」有行为实现，但 agent.tool.catalog 里没有它的元数据");
            }
        }
        for (String name : descriptors.keySet()) {
            if (!handlers.containsKey(name)) {
                throw new IllegalStateException(
                        "agent.tool.catalog 里的工具「" + name + "」没有对应的行为实现");
            }
        }
    }

    private List<ToolDefinition> buildDefinitions(ObjectMapper objectMapper) {
        List<ToolDefinition> list = new ArrayList<>(descriptors.size());
        for (ToolDescriptor descriptor : descriptors.values()) {
            list.add(new ToolDefinition(descriptor.name(), descriptor.description(),
                    schemaOf(objectMapper, descriptor)));
        }
        return List.copyOf(list);
    }

    /**
     * 取工具的入参 Schema，并**在启动期**校验它是合法 JSON 且为**对象**。
     *
     * <p>为什么要校验：Schema 写坏了（少个引号、写成数组）在这里就拦下，而不是等用户提问、
     * 模型回一个 {@code 400 Invalid schema} 才发现。空值按 {@code {}} 处理。
     */
    private static String schemaOf(ObjectMapper objectMapper, ToolDescriptor descriptor) {
        String json = descriptor.parametersJson();
        if (json == null || json.isBlank()) {
            return "{}";
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            if (!node.isObject()) {
                throw new IllegalStateException(
                        "工具「" + descriptor.name() + "」的 parameters-json 必须是一个 JSON 对象");
            }
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "工具「" + descriptor.name() + "」的 parameters-json 不是合法 JSON", e);
        }
        return json;
    }
}
