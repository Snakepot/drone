package cn.tedu.drone.mcp.invoke;

/**
 * 一次工具调用的结果：给模型看的文本 + 是否失败。
 *
 * <p>对齐 MCP 的 `tools/call` 返回结构：`{content:[{type:"text",text}], isError}`。
 * 业务失败、无权限、登录失效都走 `isError=true`，让模型据此向用户解释；
 * 只有协议层错误（JSON 不合法、未知方法）才用 JSON-RPC 的 `error`。
 */
public record ToolCallResult(String text, boolean isError) {

    public static ToolCallResult ok(String text) {
        return new ToolCallResult(text, false);
    }

    public static ToolCallResult error(String text) {
        return new ToolCallResult(text, true);
    }
}
