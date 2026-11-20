package cn.tedu.droneagent.mcp;

/**
 * 一个**远程** MCP 工具的描述，是服务端 {@code tools/list} 返回的裁剪结果。
 *
 * <p>只保留接入本地工具框架必需的字段：名字、描述、入参 Schema、是否只读。
 * 其中 {@code inputSchemaJson} 是**序列化后的 JSON 字符串**——与
 * {@code agent.tool.catalog} 里 {@code parameters-json} 的形态一致，
 * 这样远程工具和本地工具最终能被同一个 {@code ToolRegistry} 校验。
 *
 * @param name          远程原名（如 {@code maps_weather}）
 * @param description   服务端给的描述；为空时由聚合层补一句兜底
 * @param inputSchemaJson 入参 JSON Schema（对象）；服务端没给时是 {@code {"type":"object"}}
 * @param readOnlyHint  服务端声明的「只读」提示；没有该注解时为 true
 *                      （MCP 约定：查询类工具应声明 readOnlyHint，缺省按只读处理风险更低）
 */
public record McpToolInfo(
        String name,
        String description,
        String inputSchemaJson,
        boolean readOnlyHint
) {
}
