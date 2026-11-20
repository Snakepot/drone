package cn.tedu.droneagent.mcp;

/**
 * MCP 调用失败的统一异常。
 *
 * <h2>为什么单独一个异常类型</h2>
 * 传输失败、协议错误、工具返回 {@code isError=true} 是三类不同原因，
 * 但它们在编排层的处理方式相同：**变成一条失败的工具结果回填给模型**，而不是让请求 500。
 * 用一个类型收口，适配器里只需 {@code catch (RuntimeException)} 一次。
 *
 * <h2>消息里不能带密钥</h2>
 * 构造函数只接收**已经脱敏**的消息。调用方拼消息时若要用到 URL，
 * 必须先过 {@link McpSecrets#maskUrl(String)}——否则异常被日志打印时密钥就跟着出去了。
 */
public class McpException extends RuntimeException {

    public McpException(String message) {
        super(message);
    }

    public McpException(String message, Throwable cause) {
        super(message, cause);
    }
}
