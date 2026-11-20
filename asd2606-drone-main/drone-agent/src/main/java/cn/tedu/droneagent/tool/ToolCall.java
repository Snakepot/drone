package cn.tedu.droneagent.tool;

/**
 * 模型**要求调用某个工具**的一次请求。
 *
 * <h2>它在链路里的位置，以及它不负责什么</h2>
 * <p><b>位置</b>：模型响应里 {@code message.tool_calls} 的一项，由
 * {@link cn.tedu.droneagent.service.ChatService} 解析出来交给编排层；
 * 编排层据此执行工具，并把 {@link #id()} 原样带回（回填时用它对上号）。
 *
 * <ul>
 *   <li><b>不解析参数</b>：{@link #argumentsJson()} 是模型给的**原始 JSON 字符串**，
 *       意思怎么取由执行方决定。</li>
 *   <li><b>不执行</b>：它只是「模型想调什么」，真正执行是 {@link Tool} 的事。</li>
 * </ul>
 *
 * @param id            本次调用的唯一标识；回填工具结果时必须原样带回去
 * @param name          工具名，对应 {@link ToolDefinition#name()}
 * @param argumentsJson 模型给的参数，**JSON 字符串**（可能不完整或有毛病，需容错）
 */
public record ToolCall(String id, String name, String argumentsJson) {
}
