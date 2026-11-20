package cn.tedu.drone.mcp.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 工具目录：从 OpenAPI 文档生成「每个业务接口一个工具」的规格表。
 *
 * <h2>为什么从 OpenAPI 生成，而不是手写</h2>
 * 手写 70 份 Schema 一定会和真实接口漂移；而这里的数据源就是 `drone-server` 自己生成的
 * OpenAPI，**有接口才有工具**，天然满足「不发明工具」。
 *
 * <h2>三条必须处理的 OpenAPI 坑</h2>
 * <ol>
 *   <li><b>`$ref` 不内联，模型看不懂</b>：`{"$ref":"#/components/schemas/TaskVO"}` 对模型是黑盒，
 *       必须递归替换成真实结构（带深度上限与环检测，嵌套对象如航点坐标会互相引用）。</li>
 *   <li><b>查询 Bean 的 `required=true` 是假象</b>：springdoc 把「一个 `param` 对象」标成必填，
 *       但 Spring 实际按可选绑定；摊平后字段一律**非必填**，否则模型会被逼着编参数。</li>
 *   <li><b>计算属性泄漏</b>：分页参数 Bean 上的 `getOffset()/getLimit()` 会作为 query 字段出现，
 *       但它们由 `page/pageSize` 推导，客户端传了也无效——必须剔除。</li>
 * </ol>
 *
 * <h2>它不负责什么</h2>
 * 不执行、不鉴权、不写库；只做「文档 → 工具描述」的纯转换，因此可以完全用 fixture 单测。
 */
@Slf4j
public final class ToolCatalog {

    /** 完全排除的路径：不是业务能力，或无法用 JSON 参数表达 */
    private static final Set<String> EXCLUDED_EXACT = Set.of(
            "/user/login", "/user/logout",
            "/user/health", "/device/health", "/route/health", "/point/health", "/zone/health",
            "/user/import", "/user/import/template", "/user/export",
            "/device/import", "/device/import/template", "/device/export",
            "/task/attachment/upload", "/analytics/coverage/export"
    );

    /** 服务间内部接口：不给模型看 */
    private static final String EXCLUDED_PREFIX = "/internal";

    /** 附件相关的下载走二进制流，MCP 工具只能返回文本 */
    private static final String ATTACHMENT_PREFIX = "/task/attachment/";

    /** 由 page/pageSize 推导的计算属性，传了也无效 */
    private static final Set<String> COMPUTED_QUERY = Set.of("offset", "limit");

    /** `$ref` 递归内联的最大深度；超过就退化成「任意对象」 */
    private static final int MAX_REF_DEPTH = 5;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final List<ToolSpec> tools;
    private final Map<String, ToolSpec> byName;
    private final String fingerprint;

    private ToolCatalog(List<ToolSpec> tools) {
        this.tools = List.copyOf(tools);
        Map<String, ToolSpec> map = new LinkedHashMap<>();
        for (ToolSpec tool : tools) {
            map.put(tool.name(), tool);
        }
        this.byName = Map.copyOf(map);
        this.fingerprint = fingerprintOf(tools);
    }

    /** 从一份 OpenAPI 文档构建目录；入参为 null 时返回空目录 */
    public static ToolCatalog fromOpenApi(JsonNode document) {
        if (document == null || !document.path("paths").isObject()) {
            log.warn("【MCP-Server】OpenAPI 文档里没有 paths，工具目录为空");
            return new ToolCatalog(List.of());
        }

        List<ToolSpec> drafts = new ArrayList<>();
        Iterator<Map.Entry<String, JsonNode>> paths = document.path("paths").fields();
        while (paths.hasNext()) {
            Map.Entry<String, JsonNode> entry = paths.next();
            String path = entry.getKey();
            if (isExcluded(path)) {
                continue;
            }
            Iterator<Map.Entry<String, JsonNode>> operations = entry.getValue().fields();
            while (operations.hasNext()) {
                Map.Entry<String, JsonNode> operation = operations.next();
                String method = operation.getKey().toUpperCase();
                if (!isHttpMethod(method)) {
                    continue;
                }
                ToolSpec spec = buildSpec(document, path, method, operation.getValue());
                if (spec != null) {
                    drafts.add(spec);
                }
            }
        }

        List<ToolSpec> resolved = resolveNameCollisions(drafts);
        log.info("【MCP-Server】工具目录已生成：{} 个（OpenAPI 共 {} 个操作）",
                resolved.size(), document.path("paths").size());
        return new ToolCatalog(resolved);
    }

    public List<ToolSpec> tools() {
        return tools;
    }

    /** 按工具名查找；不存在返回 null（模型可能报一个不存在的名字） */
    public ToolSpec find(String name) {
        return byName.get(name);
    }

    public int size() {
        return tools.size();
    }

    /** 工具名指纹：用于日志里判断「两边看到的是不是同一套工具」 */
    public String fingerprint() {
        return fingerprint;
    }

    private static boolean isHttpMethod(String method) {
        return switch (method) {
            case "GET", "POST", "PUT", "DELETE", "PATCH" -> true;
            default -> false;
        };
    }

    private static boolean isExcluded(String path) {
        return path.startsWith(EXCLUDED_PREFIX)
                || path.startsWith(ATTACHMENT_PREFIX)
                || EXCLUDED_EXACT.contains(path);
    }

    /** 把一个 operation 变成 ToolSpec；结构不合格时返回 null 并记 WARN */
    private static ToolSpec buildSpec(JsonNode document, String path, String method, JsonNode operation) {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        ArrayNode required = schema.putArray("required");

        List<String> pathParams = new ArrayList<>();
        Set<String> queryParams = new LinkedHashSet<>();
        Set<String> bodyParams = new LinkedHashSet<>();

        // 1) 参数：path 必填；query 分「内联参数」与「查询 Bean」两种
        for (JsonNode parameter : operation.path("parameters")) {
            String in = parameter.path("in").asText();
            String name = parameter.path("name").asText();
            JsonNode parameterSchema = inline(document, parameter.path("schema"), 0, new HashSet<>());

            if ("path".equals(in)) {
                if (properties.has(name)) {
                    log.warn("【MCP-Server】跳过 {} {}：路径参数 {} 与已有字段重名", method, path, name);
                    return null;
                }
                properties.set(name, parameterSchema);
                required.add(name);
                pathParams.add(name);
            } else if ("query".equals(in)) {
                mergeQuery(document, method, path, parameter, parameterSchema,
                        properties, required, queryParams);
            }
        }

        // 2) 请求体
        boolean hasRequestBody = operation.path("requestBody").isObject();
        if (hasRequestBody) {
            JsonNode bodySchema = requestBodySchema(document, operation.path("requestBody"));
            if (bodySchema == null || !bodySchema.isObject()) {
                log.warn("【MCP-Server】跳过 {} {}：请求体不是 JSON 对象", method, path);
                return null;
            }
            JsonNode bodyProperties = bodySchema.path("properties");
            if (bodyProperties.isObject()) {
                Iterator<Map.Entry<String, JsonNode>> fields = bodyProperties.fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> field = fields.next();
                    if (properties.has(field.getKey())) {
                        log.warn("【MCP-Server】跳过 {} {}：字段 {} 同时出现在路径与请求体",
                                method, path, field.getKey());
                        return null;
                    }
                    properties.set(field.getKey(), field.getValue());
                    bodyParams.add(field.getKey());
                }
            }
            for (JsonNode name : bodySchema.path("required")) {
                String text = name.asText();
                if (!required.toString().contains("\"" + text + "\"")) {
                    required.add(text);
                }
            }
        }

        // 3) 名称
        String name = baseName(path);

        String summary = operation.path("summary").asText("");
        String description = summary.isBlank() ? method + " " + path : summary;

        return new ToolSpec(name, description, method, path, schema, "GET".equals(method),
                hasRequestBody, List.copyOf(pathParams), Set.copyOf(queryParams), Set.copyOf(bodyParams));
    }

    /**
     * 合并 query 参数。
     *
     * <p>查询 Bean（springdoc 会生成一个名为 {@code param} 的 `$ref`）需要**摊平**：
     * 直接把它的 `properties` 提到顶层；字段一律视为可选（见类注释第 2 条坑）。
     */
    private static void mergeQuery(JsonNode document, String method, String path, JsonNode parameter,
                                   JsonNode parameterSchema, ObjectNode properties, ArrayNode required,
                                   Set<String> queryParams) {
        JsonNode beanProperties = parameterSchema.path("properties");
        if (beanProperties.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = beanProperties.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                String fieldName = field.getKey();
                if (COMPUTED_QUERY.contains(fieldName)) {
                    continue;
                }
                if (properties.has(fieldName)) {
                    log.warn("【MCP-Server】{} {}：查询字段 {} 重名，后者忽略", method, path, fieldName);
                    continue;
                }
                properties.set(fieldName, field.getValue());
                queryParams.add(fieldName);
            }
            return;
        }

        String name = parameter.path("name").asText();
        if (name.isBlank() || COMPUTED_QUERY.contains(name)) {
            return;
        }
        if (properties.has(name)) {
            log.warn("【MCP-Server】{} {}：查询参数 {} 重名，后者忽略", method, path, name);
            return;
        }
        properties.set(name, parameterSchema);
        queryParams.add(name);
        if (parameter.path("required").asBoolean(false)) {
            required.add(name);
        }
    }

    /** 取 requestBody 的 JSON Schema（优先 application/json，否则取第一个媒体类型） */
    private static JsonNode requestBodySchema(JsonNode document, JsonNode requestBody) {
        JsonNode content = requestBody.path("content");
        JsonNode media = content.path("application/json");
        if (!media.isObject()) {
            Iterator<JsonNode> it = content.elements();
            media = it.hasNext() ? it.next() : null;
        }
        if (media == null) {
            return null;
        }
        return inline(document, media.path("schema"), 0, new HashSet<>());
    }

    /**
     * 递归内联 `$ref`。
     *
     * <p>{@code seen} 防止自引用/互相引用把递归打爆；出栈时移除，允许同一结构被多处复用。
     * 超过深度上限时退化为 {@code {"type":"object"}}——模型仍能传对象，只是失去字段约束，
     * 比整个工具不可用要好。
     */
    private static JsonNode inline(JsonNode document, JsonNode node, int depth, Set<String> seen) {
        if (node == null || node.isMissingNode()) {
            return MAPPER.createObjectNode();
        }
        if (node.isObject() && node.has("$ref")) {
            String ref = node.get("$ref").asText();
            if (depth >= MAX_REF_DEPTH || !seen.add(ref)) {
                ObjectNode fallback = MAPPER.createObjectNode();
                fallback.put("type", "object");
                return fallback;
            }
            JsonNode target = document.at(ref.startsWith("#") ? ref.substring(1) : ref);
            JsonNode resolved = inline(document, target, depth + 1, seen);
            seen.remove(ref);
            return resolved;
        }
        if (node.isObject()) {
            ObjectNode copy = MAPPER.createObjectNode();
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                // $schema 对模型无意义，去掉能省 token
                if ("$schema".equals(field.getKey())) {
                    continue;
                }
                copy.set(field.getKey(), inline(document, field.getValue(), depth, seen));
            }
            return copy;
        }
        if (node.isArray()) {
            ArrayNode copy = MAPPER.createArrayNode();
            for (JsonNode item : node) {
                copy.add(inline(document, item, depth, seen));
            }
            return copy;
        }
        return node.deepCopy();
    }

    /**
     * 基础名：路径里的 `{...}` 段去掉；末段是参数时补 `_detail`。
     * 例：`/task/dispatch` → `task_dispatch`；`/alert/{id}` → `alert_detail`。
     */
    private static String baseName(String path) {
        String[] segments = path.split("/");
        List<String> parts = new ArrayList<>();
        for (String segment : segments) {
            if (segment.isBlank() || segment.startsWith("{")) {
                continue;
            }
            parts.add(segment.replace('-', '_'));
        }
        String name = String.join("_", parts);
        if (segments.length > 0 && segments[segments.length - 1].startsWith("{")) {
            name = name + "_detail";
        }
        return name;
    }

    /**
     * 同名冲突消歧：路径段少的保留原名，其余加 `_by_<中间参数>`；
     * 没有中间参数时加方法名。仍有冲突就跳过（并记 WARN），绝不生成两个同名工具。
     */
    private static List<ToolSpec> resolveNameCollisions(List<ToolSpec> drafts) {
        Map<String, List<ToolSpec>> groups = new LinkedHashMap<>();
        for (ToolSpec spec : drafts) {
            groups.computeIfAbsent(spec.name(), key -> new ArrayList<>()).add(spec);
        }

        List<ToolSpec> resolved = new ArrayList<>();
        Set<String> used = new LinkedHashSet<>();
        for (Map.Entry<String, List<ToolSpec>> group : groups.entrySet()) {
            List<ToolSpec> specs = group.getValue();
            if (specs.size() == 1) {
                resolved.add(specs.get(0));
                used.add(specs.get(0).name());
                continue;
            }
            specs.sort(Comparator.comparingInt((ToolSpec s) -> s.pathTemplate().split("/").length)
                    .thenComparing(ToolSpec::pathTemplate));
            for (int i = 0; i < specs.size(); i++) {
                ToolSpec spec = specs.get(i);
                String name = i == 0 ? spec.name() : disambiguate(spec);
                if (!used.add(name)) {
                    log.warn("【MCP-Server】无法消歧，跳过工具：{} {}（候选名 {}）",
                            spec.httpMethod(), spec.pathTemplate(), name);
                    continue;
                }
                resolved.add(new ToolSpec(name, spec.description(), spec.httpMethod(), spec.pathTemplate(),
                        spec.inputSchema(), spec.readOnly(), spec.hasRequestBody(),
                        spec.pathParams(), spec.queryParams(), spec.bodyParams()));
            }
        }
        return resolved;
    }

    /** 用路径中间的参数名消歧，如 `/task/{taskId}/zone-check` → `_by_task` */
    private static String disambiguate(ToolSpec spec) {
        String[] segments = spec.pathTemplate().split("/");
        for (int i = 0; i < segments.length - 1; i++) {
            if (segments[i].startsWith("{") && segments[i].endsWith("}")) {
                String param = segments[i].substring(1, segments[i].length() - 1);
                if (param.endsWith("Id")) {
                    param = param.substring(0, param.length() - 2);
                }
                return spec.name() + "_by_" + param.toLowerCase();
            }
        }
        return spec.name() + "_" + spec.httpMethod().toLowerCase();
    }

    private static String fingerprintOf(List<ToolSpec> tools) {
        try {
            String joined = tools.stream().map(ToolSpec::name).sorted().reduce("", (a, b) -> a + b + "\n");
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(joined.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 6; i++) {
                hex.append(String.format("%02x", digest[i]));
            }
            return hex.toString();
        } catch (Exception e) {
            return "unknown";
        }
    }
}
