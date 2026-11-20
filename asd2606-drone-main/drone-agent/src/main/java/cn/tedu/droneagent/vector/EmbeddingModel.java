package cn.tedu.droneagent.vector;

import java.util.ArrayList;
import java.util.List;

/**
 * 嵌入模型（基础设施层）：把文本变成向量。
 *
 * <h2>为什么是接口</h2>
 * 需求方定了「走 embedding API、供应商与模型名配置化」，且**本地模型后续再议**（见 `ADR-0006` 决策 5）。
 * 把「怎么算向量」收在接口后，今天接 API、明天换本地模型，**检索与入库都不动**。
 *
 * <h2>为什么单条与批量都要有</h2>
 * <ul>
 *   <li>检索时只查一两条 → {@link #embed(String)}；</li>
 *   <li>入库时成百上千条 → {@link #embedAll(List)}，API 实现**一次请求带多条输入**，
 *       少往返、更快也更省（默认实现逐条调用，够用但不是最优）。</li>
 * </ul>
 *
 * <h2>它不负责什么</h2>
 * <ul>
 *   <li><b>不管缓存</b>：要不要缓存同样的文本，由调用方决定。</li>
 *   <li><b>不管权限与过滤</b>：那是检索侧的事。</li>
 * </ul>
 */
public interface EmbeddingModel {

    /** 向量维度。必须与向量库集合的维度一致，否则写入会被拒 */
    int dimension();

    /** 把一段文本算成向量 */
    float[] embed(String text);

    /**
     * 批量算向量，**顺序与入参一致**。
     *
     * <p>默认实现逐条调用；实现类若能一次请求算多条，应覆写它。
     */
    default List<float[]> embedAll(List<String> texts) {
        List<float[]> vectors = new ArrayList<>(texts.size());
        for (String text : texts) {
            vectors.add(embed(text));
        }
        return vectors;
    }
}
