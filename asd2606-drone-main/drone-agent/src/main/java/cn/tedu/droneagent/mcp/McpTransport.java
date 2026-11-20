package cn.tedu.droneagent.mcp;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * MCP 的**传输层**：只负责把一条 JSON-RPC 消息送出去、把响应拿回来。
 *
 * <h2>为什么要把「请求」和「通知」分成两个方法</h2>
 * MCP 基于 JSON-RPC 2.0，消息有两种：
 * <ul>
 *   <li><b>请求</b>（有 {@code id}）：服务器必须回一条同 id 的响应；</li>
 *   <li><b>通知</b>（没有 {@code id}）：服务器**不回 JSON-RPC 响应**。
 *       实测里 {@code notifications/initialized} 返回的是 <b>202 + 空 body</b>。
 *       如果拿请求的解析逻辑去处理通知，会在「空 body 不是合法 JSON」上炸掉。</li>
 * </ul>
 * 分开之后，通知路径永远不去解析响应体，这类错误从根上不会发生。
 *
 * <h2>它不负责什么</h2>
 * <ul>
 *   <li><b>不懂 MCP 语义</b>：不知道 initialize、tools/list 是什么，只传 JSON。</li>
 *   <li><b>不做重试</b>：失败就抛 {@link McpException}，是否重试由上层决定
 *       （本期工具调用不重试，见 REQ-AGENT-0007 的预算策略）。</li>
 * </ul>
 */
public interface McpTransport {

    /**
     * 发送一个**需要响应**的 JSON-RPC 请求。
     *
     * @param rpc 完整请求体（含 id / method / params）
     * @return 服务器返回的 JSON-RPC 响应（含 result 或 error）
     * @throws McpException 网络失败、超时、非 2xx、响应无法解析
     */
    JsonNode request(JsonNode rpc);

    /**
     * 发送请求，并附加**本次调用**的请求头（如用户的 `Authorization`）。
     *
     * <p>默认实现忽略这些头，直接走 {@link #request(JsonNode)}——这样既有的传输实现与
     * 测试桩不用改。需要「按调用方身份转发」的实现（HTTP）覆写它。
     *
     * <p><b>为什么必须是方法参数而不是字段</b>：一个传输实例被所有用户、所有线程共享，
     * 把它写成字段会让 A 的凭证串到 B 的请求上。
     *
     * @param rpc     完整请求体
     * @param headers 本次调用附加的头；为空表示不附加
     * @return 服务器返回的 JSON-RPC 响应
     */
    default JsonNode request(JsonNode rpc, java.util.Map<String, String> headers) {
        return request(rpc);
    }

    /**
     * 发送一个**通知**（没有 id，不期待 JSON-RPC 响应）。
     *
     * <p>202 / 204 都算成功；响应体即使有内容也不解析。
     *
     * @param rpc 完整通知体（method 必填，无 id）
     */
    void notify(JsonNode rpc);

    /**
     * 服务器在 {@code initialize} 响应头里下发的会话 id。
     *
     * <p>2025-03-26 版本用它维持会话；**高德不返回该头**，所以实现必须容忍 null：
     * 有就回带，没有就当无状态服务器。
     *
     * @return 会话 id；没有则为 null
     */
    String sessionId();
}
