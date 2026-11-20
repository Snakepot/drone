package cn.tedu.droneagent.vector;

import java.util.List;

/**
 * 向量库的**统一入口**（基础设施层）：写入、按相似度检索、按条件删除。
 *
 * <h2>为什么先立它、且过滤条件必须显式传</h2>
 * <ul>
 *   <li><b>可替换</b>：内存实现供单测（宪法 §5 不允许单测连真库），Milvus 适配供生产；
 *       上层（知识库场景）只依赖本接口，换成别的向量库**不动上层**（见 `ADR-0006` 决策 3）。</li>
 *   <li><b>检索必带过滤</b>：{@link #search} 的过滤条件**不可为空**——不是「方便少写一个参数」，
 *       而是**逼调用方显式表达权限**。没这一步，「忘了加权限」就是一次越权。</li>
 * </ul>
 *
 * <h2>它不负责什么</h2>
 * <ul>
 *   <li><b>不建业务上的集合 schema</b>：写哪些元数据字段由调用方（场景层）与集合约定。</li>
 *   <li><b>不切块、不算向量</b>：那是 {@code kb} 与 {@code EmbeddingModel} 的事。</li>
 * </ul>
 */
public interface VectorStore {

    /**
     * 写入或**覆盖**（按 id）。同 id 再写即替换，这是「资料更新后重建索引」的幂等基础。
     *
     * @param records 待写入的记录；向量维度必须与集合一致
     */
    void upsert(List<VectorRecord> records);

    /**
     * 按相似度检索 **top-K**。
     *
     * @param queryVector 查询向量，维度必须与集合一致
     * @param filter      过滤条件，**不可为空**（权限必须前置过滤，见接口说明）
     * @param topK        最多返回几条；{@code <= 0} 返回空
     * @return 命中的记录，按相似度**从高到低**；相似度相同时按 id 升序（保证结果可重复）
     */
    List<ScoredRecord> search(float[] queryVector, VectorFilter filter, int topK);

    /**
     * 按条件删除。资料被删除或重建时用它清掉旧块，避免「删了还能被检索出来」。
     *
     * @param filter 删除条件，**不可为空**（拒绝「一个参数写错就清空全库」）
     */
    void deleteByFilter(VectorFilter filter);

    /** 当前记录数（供测试与运维观测） */
    int size();
}
