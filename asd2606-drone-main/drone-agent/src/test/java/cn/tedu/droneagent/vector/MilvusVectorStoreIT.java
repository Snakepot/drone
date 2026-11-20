package cn.tedu.droneagent.vector;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MilvusVectorStore} 的**集成测试**：连**真的 Milvus**（Docker 单机）。
 *
 * <p>它与单元测试分开：单元测试**不许连真库**（宪法 §5），本类默认**不参与 `mvn test`**
 * （文件名以 {@code IT} 结尾，Surefire 默认不收），且要显式打开才跑：
 * <pre>
 *   MILVUS_IT=true mvn test -Dtest=MilvusVectorStoreIT
 * </pre>
 * 默认连 {@code http://localhost:19530}（实测：Milvus 的 REST 与 gRPC 共用 19530），
 * 可用 {@code MILVUS_URL} 覆盖。**用独立的探针集合**，跑完即删，不碰业务集合。
 */
@Tag("integration")
@EnabledIfEnvironmentVariable(named = "MILVUS_IT", matches = "true")
class MilvusVectorStoreIT {

    private static final String BASE_URL = System.getenv().getOrDefault("MILVUS_URL", "http://localhost:19530");

    private static final String COLLECTION = "kb_it_probe";

    private static final int DIM = 4;

    private static final List<FieldDef> FIELDS = List.of(
            FieldDef.varchar("doc_id", 128),
            FieldDef.varchar("file_name", 256),
            FieldDef.varchar("section", 1024),
            FieldDef.varchar("content", 8192),
            FieldDef.int32("chunk_index"),
            FieldDef.stringArray("perm_codes", 128, 8));

    private MilvusVectorStore store;

    @BeforeEach
    void setUp() {
        store = new MilvusVectorStore(BASE_URL, COLLECTION, DIM, FIELDS);
        drop();          // 从干净状态开始
        store.ensureCollection();
        store.deleteByFilter(VectorFilter.eq("doc_id", ""));
    }

    @AfterEach
    void tearDown() {
        drop();
    }

    private static VectorRecord row(String id, String docId, String section, String content,
                                    int index, List<String> perms, float x) {
        return new VectorRecord(id, new float[]{x, 0, 0, 0}, Map.of(
                "doc_id", docId,
                "file_name", docId + ".md",
                "section", section,
                "content", content,
                "chunk_index", index,
                "perm_codes", perms));
    }

    @Test
    @DisplayName("集合按声明的字段建出来（含向量维度校验）")
    void createsCollectionWithDeclaredFields() {
        assertEquals(0, store.size());
        assertTrue(store.declaredFields().containsAll(List.of(
                "doc_id", "file_name", "section", "content", "chunk_index", "perm_codes")));
    }

    @Test
    @DisplayName("权限前置过滤：无权限的资料在**检索阶段**就命中不到（AC-3）")
    void permissionFilterExcludesUnauthorized() throws Exception {
        store.upsert(List.of(
                row("m#0", "manual", "手册 › 保养", "电池要保养", 0, List.of("agent:chat"), 1.0f),
                row("i#0", "insurance", "保险 › 责任", "三者责任险保额", 0, List.of("agent:finance"), 0.99f)));
        settle();

        List<ScoredRecord> hits = store.search(new float[]{1, 0, 0, 0},
                VectorFilter.containsAny("perm_codes", List.of("agent:chat")), 5);

        assertEquals(1, hits.size(), "无 agent:chat 权限的保险资料不能被检索出来");
        assertEquals("m#0", hits.get(0).id());
        assertEquals("手册 › 保养", hits.get(0).metadata().get("section"), "出处要能带回来");
    }

    @Test
    @DisplayName("同 id 再写即覆盖（幂等重建），不会变成两条")
    void upsertSameIdOverwrites() throws Exception {
        store.upsert(List.of(row("m#0", "manual", "手册 › 保养", "旧内容", 0, List.of("agent:chat"), 1.0f)));
        settle();
        store.upsert(List.of(row("m#0", "manual", "手册 › 保养", "新内容", 0, List.of("agent:chat"), 1.0f)));
        settle();

        assertEquals(1, store.size(), "同 id 覆盖后仍应只有一条");
        List<ScoredRecord> hits = store.search(new float[]{1, 0, 0, 0}, VectorFilter.always(), 5);
        assertEquals("新内容", hits.get(0).metadata().get("content"));
    }

    @Test
    @DisplayName("按条件删除后不再被检索到（资料删除 / 重建时用）")
    void deleteByFilterRemoves() throws Exception {
        store.upsert(List.of(
                row("m#0", "manual", "手册 › 保养", "电池要保养", 0, List.of("agent:chat"), 1.0f),
                row("i#0", "insurance", "保险 › 责任", "三者责任险", 0, List.of("agent:chat"), 0.9f)));
        settle();

        store.deleteByFilter(VectorFilter.eq("doc_id", "manual"));
        settle();

        assertEquals(1, store.size());
        List<ScoredRecord> hits = store.search(new float[]{1, 0, 0, 0}, VectorFilter.always(), 5);
        assertEquals(List.of("i#0"), hits.stream().map(ScoredRecord::id).toList());
    }

    @Test
    @DisplayName("过滤条件翻译成 Milvus 表达式（有/无权限、多值 or、and 组合）")
    void translatesFilterToExpression() {
        assertEquals("", store.toExpression(VectorFilter.always()));
        assertEquals("doc_id == \"manual\"", store.toExpression(VectorFilter.eq("doc_id", "manual")));
        assertEquals("ARRAY_CONTAINS(perm_codes, \"a\") or ARRAY_CONTAINS(perm_codes, \"b\")",
                store.toExpression(VectorFilter.containsAny("perm_codes", List.of("a", "b"))));
        assertEquals("(doc_id == \"m\") and (ARRAY_CONTAINS(perm_codes, \"a\"))",
                store.toExpression(VectorFilter.and(
                        VectorFilter.eq("doc_id", "m"),
                        VectorFilter.containsAny("perm_codes", List.of("a")))));
    }

    /** Milvus 默认一致性是 Bounded：写完稍等一下再查，免得偶发查不到 */
    private static void settle() throws InterruptedException {
        Thread.sleep(1500);
    }

    private static void drop() {
        try {
            ObjectNode body = new ObjectMapper().createObjectNode().put("collectionName", COLLECTION);
            HttpClient.newHttpClient().send(HttpRequest.newBuilder()
                            .uri(URI.create(BASE_URL + "/v2/vectordb/collections/drop"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new IllegalStateException("清理探针集合失败", e);
        }
    }
}
