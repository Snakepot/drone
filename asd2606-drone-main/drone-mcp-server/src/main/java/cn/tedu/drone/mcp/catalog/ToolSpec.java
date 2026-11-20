package cn.tedu.drone.mcp.catalog;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Set;

/**
 * 一个 MCP 工具的完整规格：既包含给模型看的描述，也包含转发时需要的「参数去哪」。
 *
 * <h2>为什么把参数分区存下来</h2>
 * {@code tools/call} 拿到的是一整包 JSON 参数，但转发给 {@code drone-server} 时，
 * 有些要拼进路径（`{id}`）、有些要放 query（`page`、`keyword`）、有些要进请求体（`taskId`）。
 * 这份分区在**启动时**由 OpenAPI 算好，调用时只做查表，不再猜。
 *
 * @param name         工具名（不含 `drone__` 前缀）
 * @param description  给模型看的描述（来自 OpenAPI summary）
 * @param httpMethod   GET / POST / PUT / DELETE / PATCH
 * @param pathTemplate 形如 `/task/{taskId}/zone-check`
 * @param inputSchema  合并后的入参 JSON Schema（已内联 `$ref`）
 * @param readOnly     是否只读（GET 视为只读），供 agent 侧推断危险工具
 * @param hasRequestBody 该操作是否需要发送 JSON 请求体（即使字段都可选，也要发 `{}`）
 * @param pathParams   path 参数名
 * @param queryParams  query 参数名
 * @param bodyParams   请求体字段名
 */
public record ToolSpec(
        String name,
        String description,
        String httpMethod,
        String pathTemplate,
        ObjectNode inputSchema,
        boolean readOnly,
        boolean hasRequestBody,
        List<String> pathParams,
        Set<String> queryParams,
        Set<String> bodyParams
) {
}
