package cn.tedu.droneagent.vector;

import java.util.List;

/**
 * 检索原语（基础设施层）：**一句自然语言 → 一批命中的块**。
 *
 * <h2>它把三件事串起来</h2>
 * <ol>
 *   <li>把查询文本交给 {@link EmbeddingModel} 算成向量；</li>
 *   <li>带着**过滤条件**去 {@link VectorStore} 检索 top-K；</li>
 *   <li>按阈值（{@code agent.kb.min-score}）砍掉不够像的。</li>
 * </ol>
 *
 * <h2>为什么过滤条件必须显式传</h2>
 * 与 {@link VectorStore#search} 同一个理由：**权限只能前置**。
 * 传 {@link VectorFilter#none()} 表示「谁都看不到」（fail-closed），传 {@link VectorFilter#always()}
 * 表示「不过滤」——后者只该出现在测试里。
 *
 * <h2>它不负责什么</h2>
 * <ul>
 *   <li><b>不构造权限条件</b>：那是场景层的事（如知识库的 {@code KnowledgeScope}）。</li>
 *   <li><b>不做「同文档相邻块折叠」</b>：那要看业务字段（doc_id / chunk_index），属场景层。</li>
 * </ul>
 */
public interface Retriever {

    /**
     * 检索。
     *
     * @param query  用户的问题
     * @param filter 过滤条件，**不可为空**
     * @return 命中的记录，按相似度从高到低；无命中或过滤到空时返回空列表
     */
    List<ScoredRecord> retrieve(String query, VectorFilter filter);
}
