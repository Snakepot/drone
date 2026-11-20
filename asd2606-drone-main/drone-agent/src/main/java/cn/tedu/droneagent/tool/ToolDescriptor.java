package cn.tedu.droneagent.tool;

import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * 一个工具的**元数据**，来自配置文件 {@code agent.tool.catalog}。
 *
 * <h2>为什么单独抽出来</h2>
 * 工具类里只该有**行为**（{@link Tool#invoke}）；「叫什么、怎么描述、参数长什么样、能不能并行」
 * 这些都是**配置**：改措辞、调参数、禁并行都不该动代码。本类就是配置文件的一条记录。
 *
 * <h2>为什么参数 Schema 是**字符串**而不是结构化的 yml</h2>
 * 这是踩过的坑：Spring 把 yml 里嵌套的**数组**绑进 {@code Map<String, Object>} 时，
 * 会变成「以 0、1 为键的对象」—— {@code required: [place]} 到运行时成了 {@code {"0":"place"}}，
 * 模型直接回 {@code 400 Invalid schema}。写成 JSON 字符串最稳，还能在**启动期**校验它是合法 JSON 对象。
 *
 * <h2>它在链路里的位置，以及它不负责什么</h2>
 * <ul>
 *   <li><b>不执行</b>：它只是描述，真正干活的是同名的 {@link Tool}。</li>
 *   <li><b>不校验</b>：名字唯一、与行为对得上、Schema 合法，由 {@link ToolRegistry} 在启动时统一校验。</li>
 * </ul>
 *
 * @param name           工具名，与同名的 {@link Tool} 对齐
 * @param description    用途描述，发给模型、供它判断何时该用
 * @param parametersJson 入参的 JSON Schema，**JSON 字符串**（空则按 {@code {}} 处理）
 * @param parallelSafe   是否允许与本轮其他工具**并发**执行
 * @param readOnly       是否只读（`REQ-AGENT-0029`）：写操作要记台账、参与取消报告
 * @param timeoutMs      工具级超时（毫秒）；null 表示用全局 `agent.tool.invoke-timeout`
 */
public record ToolDescriptor(String name, String description, String parametersJson,
                             boolean parallelSafe, boolean readOnly, Long timeoutMs) {

    /** 配置绑定时明确用 6 参构造（本类还有 5 参兼容构造）。 */
    @ConstructorBinding
    public ToolDescriptor {
    }

    /** 兼容旧元数据：不单独设置工具级超时 */
    public ToolDescriptor(String name, String description, String parametersJson,
                          boolean parallelSafe, boolean readOnly) {
        this(name, description, parametersJson, parallelSafe, readOnly, null);
    }
}
