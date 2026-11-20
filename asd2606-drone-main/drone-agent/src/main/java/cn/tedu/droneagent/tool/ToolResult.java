package cn.tedu.droneagent.tool;

/**
 * 一次工具调用的结果，即**要回填给模型的那段文本**。
 *
 * <h2>为什么 ok 与 content 要并存</h2>
 * 模型拿到的永远是 {@link #content()} 这一段文本 —— 成功时是搜索结果，
 * 失败时是一句「暂时查不到」。{@link #ok()} 只是给我们自己与日志看的：
 * 它不改变回填内容，但能让日志一眼看出这次工具到底成没成。
 *
 * <h2>它在链路里的位置，以及它不负责什么</h2>
 * <p><b>位置</b>：{@link Tool} 的返回值，随后由编排层拼进对话消息回填给模型。
 *
 * <ul>
 *   <li><b>不管怎么拼进消息</b>：把它塞成什么角色、什么结构，是编排层的事。</li>
 *   <li><b>不代表最终答复</b>：这只是「工具说了什么」，最终对用户说的话由模型生成。</li>
 * </ul>
 *
 * @param ok      工具是否产出了可用内容（成功 results 非空为 true；失败或无结果为 false）
 * @param content 回填给模型的文本，**不为 null**
 */
public record ToolResult(boolean ok, String content) {

    /** 工具产出了可用内容 */
    public static ToolResult ok(String content) {
        return new ToolResult(true, content);
    }

    /** 工具没产出可用内容（失败 / 无结果）；content 里写清原因，供模型如实转告 */
    public static ToolResult failed(String content) {
        return new ToolResult(false, content);
    }
}
