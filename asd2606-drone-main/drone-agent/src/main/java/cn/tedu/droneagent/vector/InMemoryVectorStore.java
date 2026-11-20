package cn.tedu.droneagent.vector;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 向量库的**内存实现**：供**单元测试**用，不连任何外部依赖（宪法 §5）。
 *
 * <h2>为什么造它，而不是在测试里打桩接口</h2>
 * 打桩只能验证「调用发生了」，验证不了「检索**结果对不对**」——而结果正确（谁排在前面、权限有没有挡住）
 * 恰恰是这条链路最要紧的事。内存实现能**真跑一遍**相似度与过滤，测试才有意义。
 *
 * <h2>为什么它是「忠实的替身」</h2>
 * 真实向量库的维度是**集合固定的**，维度对不上会被拒。这里也照做：构造时给定维度，
 * 写入与查询都对不上就**直接抛错**——免得在内存里蒙混过关、上了真库才炸（见「坑」）。
 *
 * <h2>它不负责什么</h2>
 * <ul>
 *   <li><b>不持久化</b>：进程结束即丢；它只是测试替身。</li>
 *   <li><b>不做多实例共享</b>：与 `ADR-0003` 的单实例前提一致。</li>
 * </ul>
 */
public class InMemoryVectorStore implements VectorStore {

    /** 集合维度，写入与查询都必须与它一致（与真库行为对齐） */
    private final int dimension;

    private final Map<String, VectorRecord> byId = new ConcurrentHashMap<>();

    public InMemoryVectorStore(int dimension) {
        if (dimension <= 0) {
            throw new IllegalArgumentException("维度必须为正数：" + dimension);
        }
        this.dimension = dimension;
    }

    @Override
    public void upsert(List<VectorRecord> records) {
        Objects.requireNonNull(records, "records 不能为空");
        for (VectorRecord record : records) {
            requireDimension(record.vector().length);
            byId.put(record.id(), record);
        }
    }

    @Override
    public List<ScoredRecord> search(float[] queryVector, VectorFilter filter, int topK) {
        Objects.requireNonNull(queryVector, "queryVector 不能为空");
        Objects.requireNonNull(filter, "filter 不能为空：检索必须显式给出过滤条件");
        requireDimension(queryVector.length);
        if (topK <= 0) {
            return List.of();
        }

        double queryNorm = norm(queryVector);
        List<ScoredRecord> hits = new ArrayList<>();
        for (VectorRecord record : byId.values()) {
            if (!filter.matches(record.metadata())) {
                continue;   // 权限过滤前置：不符合条件的记录**根本不参与打分**
            }
            hits.add(new ScoredRecord(record.id(),
                    cosine(queryVector, queryNorm, record.vector()), record.metadata()));
        }
        // 主序：相似度从高到低；次序：id 升序 —— 相似度并列时也有稳定顺序，测试可重复
        hits.sort(Comparator.comparingDouble(ScoredRecord::score).reversed()
                .thenComparing(ScoredRecord::id));
        return hits.size() <= topK ? List.copyOf(hits) : List.copyOf(hits.subList(0, topK));
    }

    @Override
    public void deleteByFilter(VectorFilter filter) {
        Objects.requireNonNull(filter, "filter 不能为空：删除必须显式给出条件");
        byId.values().removeIf(record -> filter.matches(record.metadata()));
    }

    @Override
    public int size() {
        return byId.size();
    }

    private void requireDimension(int actual) {
        if (actual != dimension) {
            throw new IllegalArgumentException(
                    "向量维度不匹配：集合是 " + dimension + "，实际是 " + actual);
        }
    }

    private static double norm(float[] vector) {
        double sum = 0;
        for (float value : vector) {
            sum += (double) value * value;
        }
        return Math.sqrt(sum);
    }

    /** 余弦相似度；任一侧为零向量时返回 0（而非 NaN） */
    private static double cosine(float[] a, double normA, float[] b) {
        double normB = norm(b);
        if (normA == 0 || normB == 0) {
            return 0;
        }
        double dot = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
        }
        return dot / (normA * normB);
    }
}
