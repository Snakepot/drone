package cn.tedu.droneagent.tool;

/**
 * 一个可被模型调用的工具——**只提供行为**。
 *
 * <h2>元数据不在代码里</h2>
 * 工具的**名字之外**的东西（description、参数 Schema、能否并行）都搬到**配置文件**
 * （{@code agent.tool.catalog}），见 {@link ToolDescriptor}。
 * 一个工具若把描述/参数写死在类里，就等于在代码里埋了第二份「真相」，
 * 改个措辞都要改代码、重新编译 —— 所以这里**刻意不提供** {@code definition()}。
 *
 * <h2>为什么还要 {@link #name()}</h2>
 * 名字是「配置里的元数据」与「代码里的行为」**对齐的键**：
 * 两者按名字合并，对不上就让服务**启动失败**（见 {@link ToolRegistry}）。
 * 名字是**标识**，不是描述，放在代码里是合理的。
 *
 * <h2>它在链路里的位置，以及它不负责什么</h2>
 * <ul>
 *   <li><b>不决定要不要被调用</b>：那是模型根据配置里的描述自己判断的。</li>
 *   <li><b>不生成最终答复</b>：只回填「工具产出了什么」，对用户说的话仍由模型生成。</li>
 *   <li><b>不抛异常给编排层</b>：失败应包装成 {@link ToolResult#failed(String)}。</li>
 * </ul>
 */
public interface Tool {

    /** 工具名。这是「配置里的元数据」与「代码里的行为」对齐的键 */
    String name();

    /**
     * 执行工具。
     *
     * @param argumentsJson 模型给的参数，**原始 JSON 字符串**；由各工具自己解析
     * @return 回填给模型的结果；失败也要返回 {@link ToolResult}（{@code ok=false}），不要抛异常
     */
    ToolResult invoke(String argumentsJson);

    /**
     * 执行工具，并带上**调用方身份**。
     *
     * <p>需要「按人收窄数据」的工具（如检索内部资料，要知道这人能看哪些）覆写它；
     * 不需要身份的工具**什么都不用做**——默认实现直接转调
     * {@link #invoke(String)}，所以既有工具一行都不用改。
     *
     * <p>身份是**显式传进来**的，不是从 ThreadLocal 取的：工具跑在执行器线程上，那里取不到
     * （见 {@link ToolContext}）。
     *
     * @param context 调用方身份；{@link ToolContext#anonymous()} 表示没有身份，**应当什么都不给**
     */
    default ToolResult invoke(String argumentsJson, ToolContext context) {
        return invoke(argumentsJson);
    }
}
