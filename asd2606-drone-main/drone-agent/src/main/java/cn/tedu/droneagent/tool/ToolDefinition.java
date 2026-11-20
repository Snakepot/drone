package cn.tedu.droneagent.tool;

/**
 * 一个工具的**对外定义**：告诉模型「有这么个工具、它是干什么的、要什么参数」。
 *
 * <h2>它在链路里的位置，以及它不负责什么</h2>
 * <p><b>位置</b>：由 {@link cn.tedu.droneagent.service.ChatService} 拼进请求体的
 * {@code tools} 数组发给模型。模型据此决定要不要调用、以及带什么参数。
 *
 * <ul>
 *   <li><b>不真正执行</b>：定义只说「能做」，具体怎么做出对应的 {@link Tool} 实现负责。</li>
 *   <li><b>不管参数校验</b>：模型给的参数合不合法，由执行方在运行时判。</li>
 * </ul>
 *
 * @param name           工具名，模型回来后用它指代本工具
 * @param description    用途描述，模型靠这句话判断「什么时候该用」
 * @param parametersJson 入参的 JSON Schema，**字符串**；发送前会解析成对象嵌进请求体
 */
public record ToolDefinition(String name, String description, String parametersJson) {
}
