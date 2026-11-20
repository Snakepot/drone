package cn.tedu.droneagent.vector;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link InMemoryVectorStore} 的单元测试。
 *
 * <p>对应验收条件：`REQ-AGENT-0010` 的 <b>AC-3</b>（无权内容**不参与检索**）与
 * <b>AC-8</b>（单测不依赖真实 Milvus）——正是这个内存实现让 AC-8 成立。
 *
 * <p>用 2 维向量，几何直观：{@code (1,0)} 与 {@code (0,1)} 正交，与 {@code (1,1)} 夹角 45°。
 */
class InMemoryVectorStoreTest {

    private static final int DIM = 2;

    private final InMemoryVectorStore store = new InMemoryVectorStore(DIM);

    private static VectorRecord rec(String id, float x, float y, Map<String, Object> metadata) {
        return new VectorRecord(id, new float[]{x, y}, metadata);
    }

    private static Map<String, Object> perms(String... codes) {
        return Map.of("perm_codes", List.of(codes));
    }

    @Test
    @DisplayName("检索按相似度从高到低：与查询同向的最像，正交的最不像")
    void searchOrdersBySimilarity() {
        store.upsert(List.of(
                rec("a", 1, 0, perms("admin")),
                rec("b", 0, 1, perms("admin")),
                rec("c", 1, 1, perms("admin"))));

        List<ScoredRecord> hits = store.search(new float[]{1, 0}, VectorFilter.always(), 3);

        assertEquals(List.of("a", "c", "b"), hits.stream().map(ScoredRecord::id).toList());
        assertEquals(1.0, hits.get(0).score(), 1e-9);
        assertEquals(0.0, hits.get(2).score(), 1e-9);
    }

    @Test
    @DisplayName("同 id 再次写入即覆盖（资料更新后重建的幂等基础）")
    void upsertSameIdOverwrites() {
        store.upsert(List.of(rec("a", 1, 0, perms("admin"))));
        store.upsert(List.of(rec("a", 0, 1, perms("admin"))));

        assertEquals(1, store.size(), "同 id 应只有一条");
        List<ScoredRecord> hits = store.search(new float[]{0, 1}, VectorFilter.always(), 1);
        assertEquals("a", hits.get(0).id());
        assertEquals(1.0, hits.get(0).score(), 1e-9, "应是覆盖后的向量");
    }

    @Test
    @DisplayName("维度对不上直接报错（忠实地对齐真库行为，别在内存里蒙混过关）")
    void dimensionMismatchRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> store.upsert(List.of(new VectorRecord("a", new float[]{1, 0, 0}, Map.of()))));
        assertThrows(IllegalArgumentException.class,
                () -> store.search(new float[]{1, 0, 0}, VectorFilter.always(), 1));
        assertThrows(IllegalArgumentException.class, () -> new InMemoryVectorStore(0));
    }

    @Test
    @DisplayName("权限过滤前置：不含该权限码的记录**根本不出现在结果里**（AC-3）")
    void permissionFilterExcludesUnauthorized() {
        store.upsert(List.of(
                rec("manual", 1, 0, perms("agent:chat")),
                rec("insurance", 1, 0, perms("agent:finance"))));

        List<ScoredRecord> hits = store.search(new float[]{1, 0},
                VectorFilter.containsAny("perm_codes", List.of("agent:chat")), 10);

        assertEquals(List.of("manual"), hits.stream().map(ScoredRecord::id).toList(),
                "无 agent:chat 权限的保险方案不能被检索出来");
    }

    @Test
    @DisplayName("多个权限码时，命中任一即可见")
    void containsAnyMatchesAnyCode() {
        store.upsert(List.of(rec("d", 1, 0, perms("agent:finance"))));

        List<ScoredRecord> hits = store.search(new float[]{1, 0},
                VectorFilter.containsAny("perm_codes", List.of("agent:chat", "agent:finance")), 10);

        assertEquals(1, hits.size());
    }

    @Test
    @DisplayName("Eq 与 And 可组合（如「同一份资料 且 有权限」）")
    void eqAndAndCombine() {
        store.upsert(List.of(
                rec("h1", 1, 0, Map.of("doc_id", "manual", "perm_codes", List.of("admin"))),
                rec("h2", 1, 0, Map.of("doc_id", "insurance", "perm_codes", List.of("admin")))));

        List<ScoredRecord> hits = store.search(new float[]{1, 0},
                VectorFilter.and(
                        VectorFilter.eq("doc_id", "manual"),
                        VectorFilter.containsAny("perm_codes", List.of("admin"))), 10);

        assertEquals(List.of("h1"), hits.stream().map(ScoredRecord::id).toList());
    }

    @Test
    @DisplayName("topK 截断；<=0 直接返回空")
    void topKLimit() {
        store.upsert(List.of(
                rec("a", 1, 0, perms("admin")),
                rec("b", 1, 0.1f, perms("admin")),
                rec("c", 1, 0.2f, perms("admin"))));

        assertEquals(2, store.search(new float[]{1, 0}, VectorFilter.always(), 2).size());
        assertTrue(store.search(new float[]{1, 0}, VectorFilter.always(), 0).isEmpty());
    }

    @Test
    @DisplayName("按条件删除：删掉后不再被检索出来")
    void deleteByFilter() {
        store.upsert(List.of(
                rec("h1", 1, 0, Map.of("doc_id", "manual")),
                rec("h2", 1, 0, Map.of("doc_id", "insurance"))));

        store.deleteByFilter(VectorFilter.eq("doc_id", "manual"));

        assertEquals(1, store.size());
        assertEquals(List.of("h2"),
                store.search(new float[]{1, 0}, VectorFilter.always(), 10).stream()
                        .map(ScoredRecord::id).toList());
    }

    @Test
    @DisplayName("零向量不产生 NaN（余弦退化为 0）")
    void zeroVectorYieldsZeroScore() {
        store.upsert(List.of(rec("z", 0, 0, perms("admin"))));

        List<ScoredRecord> hits = store.search(new float[]{1, 0}, VectorFilter.always(), 1);

        assertEquals(1, hits.size());
        assertEquals(0.0, hits.get(0).score());
    }

    @Test
    @DisplayName("过滤条件不可为空：忘了传权限过滤要当场报错，而不是静默全量检索")
    void nullFilterRejected() {
        assertThrows(NullPointerException.class, () -> store.search(new float[]{1, 0}, null, 5));
        assertThrows(NullPointerException.class, () -> store.deleteByFilter(null));
    }
}
