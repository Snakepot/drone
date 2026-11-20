package cn.tedu.droneagent.vector;

import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Objects;

/**
 * {@link Retriever} 的默认实现：**算查询向量 → 带过滤检索 → 卡阈值**。
 *
 * <h2>两个刻意的「省一步」</h2>
 * <ol>
 *   <li><b>过滤条件为 {@link VectorFilter#none()} 时直接返回空</b>——
 *       这是权限的 fail-closed（用户没有任何权限码）。此时**连查询向量都不必算**，
 *       省掉一次嵌入 API 调用（贵且慢）。</li>
 *   <li><b>空查询直接返回空</b>——没必要为一句空话去调模型。</li>
 * </ol>
 *
 * <h2>它不负责什么</h2>
 * <ul>
 *   <li><b>不重试</b>：嵌入或检索失败直接抛，由调用方（工具/入库）决定降级还是重试。</li>
 *   <li><b>不做分页</b>：一次只取 top-K。</li>
 * </ul>
 */
@Slf4j
public class VectorRetriever implements Retriever {

    private final EmbeddingModel embedder;

    private final VectorStore store;

    private final int topK;

    private final double minScore;

    /**
     * @param embedder 算查询向量的模型
     * @param store    向量库
     * @param topK     最多取几条（对应 {@code agent.kb.top-k}）
     * @param minScore 相似度下限，低于它的丢掉（对应 {@code agent.kb.min-score}）
     */
    public VectorRetriever(EmbeddingModel embedder, VectorStore store, int topK, double minScore) {
        this.embedder = embedder;
        this.store = store;
        this.topK = topK;
        this.minScore = minScore;
    }

    @Override
    public List<ScoredRecord> retrieve(String query, VectorFilter filter) {
        Objects.requireNonNull(filter, "filter 不能为空：检索必须显式给出过滤条件");
        if (filter instanceof VectorFilter.None) {
            log.debug("【检索】过滤条件为「匹配零条」，直接返回空（不调用嵌入服务）");
            return List.of();
        }
        if (query == null || query.isBlank()) {
            return List.of();
        }

        float[] queryVector = embedder.embed(query);
        List<ScoredRecord> hits = store.search(queryVector, filter, topK);
        List<ScoredRecord> kept = hits.stream().filter(hit -> hit.score() >= minScore).toList();
        log.info("【检索】命中 {} 条（阈值 {} 挡掉 {} 条）", kept.size(), minScore, hits.size() - kept.size());
        return kept;
    }
}
