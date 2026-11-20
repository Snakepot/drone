package cn.tedu.droneagent.vector;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * {@link VectorStore} 的 **Milvus 实现**：走 Milvus 的 **REST API**（`/v2/vectordb/**`）。
 *
 * <h2>怎么连</h2>
 * <b>实测（Milvus v2.5.8）</b>：REST 接口与 gRPC **共用 19530**；9091 只是 management/metrics 端口。
 * 因此 {@code base-url} 形如 {@code http://host:19530}，各接口路径以 {@code /v2/vectordb} 开头。
 *
 * <h2>为什么用 REST，不引官方 SDK</h2>
 * 与本项目既有取向一致（{@code ChatService} 直连 HTTP 不引 SDK）：报文就这几条，
 * 少一个重型依赖、也少一份要跟着升级的版本。（见 `tasks` 的 T-4b 记录。）
 *
 * <h2>集合由它按需创建</h2>
 * {@link #ensureCollection()} 是**幂等**的：不存在就按调用方给的 {@link FieldDef} 建（含 COSINE 索引）并 load；
 * 已存在就**校验维度与必需字段**——对不上**直接报错**，而不是等写入时才莫名其妙地失败。
 *
 * <h2>过滤条件怎么翻译</h2>
 * {@link VectorFilter} 是结构化描述，这里翻译成 Milvus 表达式：
 * {@code 权限码} → {@code ARRAY_CONTAINS(perm_codes, "code")}，多值用 {@code or} 连接，条件之间用 {@code and}。
 * **权限过滤因此是下推到库里的**（AC-3 的前置过滤）。
 *
 * <h2>它不负责什么</h2>
 * <ul>
 *   <li><b>不认识业务字段</b>：字段名来自 {@link FieldDef}，由调用方给。</li>
 *   <li><b>不算向量、不切块</b>：那是 {@code EmbeddingModel} 与 {@code kb} 的事。</li>
 * </ul>
 */
@Slf4j
public class MilvusVectorStore implements VectorStore {

    /** 向量字段名（集合 schema 由本类创建，名字由我们定） */
    private static final String VECTOR_FIELD = "embedding";

    /** 向量索引名 */
    private static final String INDEX_NAME = "embedding";

    private static final String API_PREFIX = "/v2/vectordb";

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    private final String baseUrl;

    private final String collection;

    private final int dimension;

    private final List<FieldDef> fields;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final HttpClient httpClient;

    /** 集合是否已就绪；只做一次，之后不再重复探测 */
    private volatile boolean ready;

    /**
     * @param baseUrl    形如 {@code http://localhost:19530}（见类注释：REST 与 gRPC 共用 19530）
     * @param collection 集合名
     * @param dimension  向量维度，必须与嵌入模型一致
     * @param fields     标量字段定义（不含向量字段），由调用方给
     */
    public MilvusVectorStore(String baseUrl, String collection, int dimension, List<FieldDef> fields) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.collection = collection;
        this.dimension = dimension;
        this.fields = List.copyOf(fields);
        this.httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
    }

    /** 幂等地把集合准备好：不存在就建 + 建索引 + load；存在就校验维度与字段 */
    public synchronized void ensureCollection() {
        if (ready) {
            return;
        }
        JsonNode data = post("/collections/has", Map.of("collectionName", collection));
        if (!data.path("has").asBoolean(false)) {
            createCollection();
            log.info("【向量库】已创建集合并建立索引：{}（维度 {}，字段 {}）",
                    collection, dimension, fieldNames());
        } else {
            assertCompatible();
        }
        load();
        ready = true;
    }

    @Override
    public void upsert(List<VectorRecord> records) {
        if (records.isEmpty()) {
            return;
        }
        ensureCollection();
        List<Map<String, Object>> data = new ArrayList<>(records.size());
        for (VectorRecord record : records) {
            if (record.vector().length != dimension) {
                throw new IllegalArgumentException("向量维度不匹配：集合是 " + dimension
                        + "，实际是 " + record.vector().length + "（id=" + record.id() + "）");
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", record.id());
            row.putAll(record.metadata());
            row.put(VECTOR_FIELD, record.vector());
            data.add(row);
        }
        JsonNode result = post("/entities/upsert", Map.of("collectionName", collection, "data", data));
        log.info("【向量库】写入 {} 条到 {}（服务端确认 {} 条）",
                data.size(), collection, result.path("upsertCount").asInt());
    }

    @Override
    public List<ScoredRecord> search(float[] queryVector, VectorFilter filter, int topK) {
        if (topK <= 0) {
            return List.of();
        }
        if (queryVector.length != dimension) {
            throw new IllegalArgumentException("查询向量维度不匹配：集合是 " + dimension
                    + "，实际是 " + queryVector.length);
        }
        if (filter instanceof VectorFilter.None) {
            return List.of();   // fail-closed：直接返回空，连向量库都不访问
        }
        ensureCollection();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("collectionName", collection);
        body.put("data", List.of(queryVector));
        body.put("limit", topK);
        body.put("outputFields", fieldNames());
        String expression = toExpression(filter);
        if (!expression.isBlank()) {
            body.put("filter", expression);   // 权限过滤下推到库：不符合条件的记录不参与检索
        }

        JsonNode hits = post("/entities/search", body);
        List<ScoredRecord> records = new ArrayList<>();
        for (JsonNode hit : hits) {
            records.add(new ScoredRecord(
                    hit.path("id").asText(),
                    hit.path("distance").asDouble(),   // COSINE：越大越像
                    metadataOf(hit)));
        }
        log.info("【向量库】检索 {}：命中 {} 条（topK={}，过滤={}）",
                collection, records.size(), topK, expression.isBlank() ? "无" : expression);
        return records;
    }

    @Override
    public void deleteByFilter(VectorFilter filter) {
        if (filter instanceof VectorFilter.None) {
            return;   // 匹配零条 → 什么都不删
        }
        ensureCollection();
        String expression = toExpression(filter);
        if (expression.isBlank()) {
            throw new IllegalArgumentException("删除条件为空：拒绝「一个参数写错就清空集合」");
        }
        JsonNode result = post("/entities/delete", Map.of("collectionName", collection, "filter", expression));
        log.info("【向量库】按条件删除 {}：{} 条（过滤={}）",
                collection, result.path("deleteCount").asInt(), expression);
    }

    @Override
    public int size() {
        ensureCollection();
        JsonNode data = post("/entities/query",
                Map.of("collectionName", collection, "filter", "", "outputFields", List.of("count(*)")));
        return data.path(0).path("count(*)").asInt();
    }

    // ---------------- 内部 ----------------

    private void createCollection() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("autoID", false);                 // 主键由我们给（重灌即覆盖，幂等重建的基础）
        schema.put("enableDynamicField", false);

        ArrayNode fieldNodes = schema.putArray("fields");
        fieldNodes.add(fieldNode("id", "VarChar", Map.of("max_length", "128"), true));
        for (FieldDef field : fields) {
            fieldNodes.add(fieldNodeOf(field));
        }
        fieldNodes.add(fieldNode(VECTOR_FIELD, "FloatVector", Map.of("dim", String.valueOf(dimension)), false));

        ObjectNode index = objectMapper.createObjectNode();
        index.put("fieldName", VECTOR_FIELD);
        index.put("indexName", INDEX_NAME);
        index.put("metricType", "COSINE");
        index.putObject("params").put("index_type", "AUTOINDEX");

        ObjectNode body = objectMapper.createObjectNode();
        body.put("collectionName", collection);
        body.set("schema", schema);
        body.putArray("indexParams").add(index);

        post("/collections/create", body);
    }

    /** 已存在的集合：校验维度与必需的标量字段都在。对不上直接报错，别等写入时才炸 */
    private void assertCompatible() {
        JsonNode data = post("/collections/describe", Map.of("collectionName", collection));
        Map<String, JsonNode> existing = new LinkedHashMap<>();
        for (JsonNode field : data.path("fields")) {
            existing.put(field.path("name").asText(), field);
        }

        JsonNode vectorField = existing.get(VECTOR_FIELD);
        if (vectorField == null) {
            throw incompatible("缺少向量字段 " + VECTOR_FIELD);
        }
        int actualDim = dimOf(vectorField);
        if (actualDim != dimension) {
            throw incompatible("维度不符：集合是 " + actualDim + "，配置是 " + dimension);
        }
        List<String> missing = fieldNames().stream().filter(name -> !existing.containsKey(name)).toList();
        if (!missing.isEmpty()) {
            throw incompatible("缺少字段 " + missing);
        }
    }

    private static int dimOf(JsonNode vectorField) {
        for (JsonNode param : vectorField.path("params")) {
            if ("dim".equals(param.path("key").asText())) {
                return param.path("value").asInt(-1);
            }
        }
        return -1;
    }

    private IllegalStateException incompatible(String why) {
        return new IllegalStateException("集合「" + collection + "」已存在但与配置不符（" + why
                + "）。请先删除该集合后重启，或改用另一个集合名。");
    }

    private void load() {
        post("/collections/load", Map.of("collectionName", collection));
    }

    private ObjectNode fieldNode(String name, String dataType, Map<String, String> params, boolean primary) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("fieldName", name);
        node.put("dataType", dataType);
        if (primary) {
            node.put("isPrimary", true);
        }
        if (!params.isEmpty()) {
            ObjectNode elementParams = node.putObject("elementTypeParams");
            params.forEach(elementParams::put);
        }
        return node;
    }

    private ObjectNode fieldNodeOf(FieldDef field) {
        return switch (field.kind()) {
            case VARCHAR -> fieldNode(field.name(), "VarChar",
                    Map.of("max_length", String.valueOf(field.maxLength())), false);
            case INT32 -> fieldNode(field.name(), "Int32", Map.of(), false);
            case STRING_ARRAY -> {
                ObjectNode node = objectMapper.createObjectNode();
                node.put("fieldName", field.name());
                node.put("dataType", "Array");
                node.put("elementDataType", "VarChar");
                ObjectNode elementParams = node.putObject("elementTypeParams");
                elementParams.put("max_length", String.valueOf(field.maxLength()));
                elementParams.put("max_capacity", String.valueOf(field.maxCapacity()));
                yield node;
            }
        };
    }

    /**
     * 把结构化过滤条件翻译成 Milvus 表达式。
     *
     * <p>用 {@code instanceof} 而不是 {@code switch} 模式匹配——后者在 Java 17 还是预览特性。
     */
    String toExpression(VectorFilter filter) {
        if (filter instanceof VectorFilter.None) {
            // 到不了这里：search / deleteByFilter 已短路。留此分支是为了「万一漏了短路」时**响亮地失败**，
            // 而不是悄悄退化成「空表达式 = 不过滤」（那会变成越权）。
            throw new IllegalStateException("None 过滤条件应在调用前短路，不该走到表达式翻译");
        }
        if (filter instanceof VectorFilter.Always) {
            return "";
        }
        if (filter instanceof VectorFilter.Eq eq) {
            return eq.field() + " == \"" + escape(eq.value()) + "\"";
        }
        if (filter instanceof VectorFilter.ContainsAny any) {
            return any.values().stream()
                    .map(value -> "ARRAY_CONTAINS(" + any.field() + ", \"" + escape(value) + "\")")
                    .collect(Collectors.joining(" or "));
        }
        if (filter instanceof VectorFilter.And and) {
            return and.clauses().stream()
                    .map(this::toExpression)
                    .filter(expression -> !expression.isBlank())
                    .map(expression -> "(" + expression + ")")
                    .collect(Collectors.joining(" and "));
        }
        throw new IllegalArgumentException("未知的过滤条件：" + filter);
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** 检索结果里的标量字段：只取我们声明过的那些（向量本身不返回） */
    private Map<String, Object> metadataOf(JsonNode hit) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        for (String name : fieldNames()) {
            JsonNode value = hit.path(name);
            if (!value.isMissingNode() && !value.isNull()) {
                metadata.put(name, jsonToValue(value));
            }
        }
        return metadata;
    }

    /** Milvus 的数组字段返回 {@code {"Data":{"StringData":{"data":[…]}}}} 这种包裹结构，这里拆平 */
    private static Object jsonToValue(JsonNode value) {
        if (value.isArray()) {
            List<String> items = new ArrayList<>();
            value.forEach(item -> items.add(item.asText()));
            return items;
        }
        JsonNode wrapped = value.path("Data").path("StringData").path("data");
        if (wrapped.isArray()) {
            List<String> items = new ArrayList<>();
            wrapped.forEach(item -> items.add(item.asText()));
            return items;
        }
        if (value.isInt() || value.isLong()) {
            return value.asInt();
        }
        return value.asText();
    }

    private List<String> fieldNames() {
        return fields.stream().map(FieldDef::name).toList();
    }

    /** 发一个 POST，校验 Milvus 的 {@code code}，返回 {@code data} 节点 */
    private JsonNode post(String path, Object body) {
        String json;
        try {
            json = objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("构造 Milvus 请求体失败", e);
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + API_PREFIX + path))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("调用 Milvus 失败（" + path + "）：" + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("调用 Milvus 被中断", e);
        }
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Milvus 返回 HTTP " + response.statusCode() + "（" + path + "）："
                    + response.body());
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(response.body());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("解析 Milvus 响应失败（" + path + "）", e);
        }
        int code = root.path("code").asInt(-1);
        if (code != 0) {
            throw new IllegalStateException("Milvus 报错（" + path + "）：" + root.path("message").asText());
        }
        return root.path("data");
    }

    /** 供测试断言：集合字段名 */
    public List<String> declaredFields() {
        return fieldNames();
    }

    /** 供测试断言：集合名 */
    public String collectionName() {
        return collection;
    }
}
