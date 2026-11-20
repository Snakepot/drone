package cn.tedu.droneagent.vector;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link VectorRetriever} 的单元测试。
 *
 * <p>对应验收条件：`REQ-AGENT-0010` 的 <b>AC-5</b>（查不到就返回空，不编造）与
 * <b>AC-3</b> 的一半（无权限 → 检索为空）。
 *
 * <p>用内存向量库 + 计数桩嵌入模型，全程不连外部（宪法 §5）。
 */
class VectorRetrieverTest {

    /** 记下被调用了几次：用来断言「fail-closed 时连嵌入都不该调」 */
    private static final class CountingEmbedder implements EmbeddingModel {
        private int calls;

        @Override
        public int dimension() {
            return 2;
        }

        @Override
        public float[] embed(String text) {
            calls++;
            return new float[]{1, 0};
        }
    }

    private final InMemoryVectorStore store = new InMemoryVectorStore(2);

    private final CountingEmbedder embedder = new CountingEmbedder();

    private VectorRetriever retriever(double minScore) {
        return new VectorRetriever(embedder, store, 5, minScore);
    }

    private void seed() {
        store.upsert(List.of(
                new VectorRecord("a#0", new float[]{1, 0}, Map.of(
                        "content", "电池要保养", "perm_codes", List.of("agent:chat"))),
                new VectorRecord("b#0", new float[]{0, 1}, Map.of(
                        "content", "无关内容", "perm_codes", List.of("agent:chat")))));
    }

    @Test
    @DisplayName("命中按相似度排序，并按阈值砍掉不像的")
    void ranksAndAppliesThreshold() {
        seed();

        List<ScoredRecord> hits = retriever(0.5).retrieve("电池", VectorFilter.always());

        assertEquals(1, hits.size(), "b 的余弦相似度是 0，应被阈值 0.5 挡掉");
        assertEquals("a#0", hits.get(0).id());
        assertEquals("电池要保养", hits.get(0).metadata().get("content"));
    }

    @Test
    @DisplayName("过滤条件为「匹配零条」时直接返回空，且**不调用嵌入服务**（fail-closed 且省钱）")
    void noneFilterReturnsEmptyWithoutEmbedding() {
        seed();

        List<ScoredRecord> hits = retriever(0.0).retrieve("电池", VectorFilter.none());

        assertTrue(hits.isEmpty());
        assertEquals(0, embedder.calls, "fail-closed 时不该白调一次嵌入 API");
    }

    @Test
    @DisplayName("空查询直接返回空，也不调用嵌入服务")
    void blankQueryReturnsEmpty() {
        seed();

        assertTrue(retriever(0.0).retrieve("   ", VectorFilter.always()).isEmpty());
        assertTrue(retriever(0.0).retrieve(null, VectorFilter.always()).isEmpty());
        assertEquals(0, embedder.calls);
    }

    @Test
    @DisplayName("过滤条件不可为空：忘了加权限要当场报错")
    void nullFilterRejected() {
        assertThrows(NullPointerException.class, () -> retriever(0.0).retrieve("电池", null));
    }

    @Test
    @DisplayName("无命中的查询返回空列表（对应「查不到」）")
    void noMatchReturnsEmpty() {
        assertTrue(retriever(0.0).retrieve("库里没有的东西", VectorFilter.always()).isEmpty());
    }

    @Test
    @DisplayName("topK 生效")
    void respectsTopK() {
        store.upsert(List.of(
                new VectorRecord("a#0", new float[]{1, 0}, Map.of()),
                new VectorRecord("b#0", new float[]{1, 0.01f}, Map.of()),
                new VectorRecord("c#0", new float[]{1, 0.02f}, Map.of())));
        VectorRetriever limited = new VectorRetriever(embedder, store, 2, 0.0);

        assertEquals(2, limited.retrieve("任意", VectorFilter.always()).size());
    }
}
