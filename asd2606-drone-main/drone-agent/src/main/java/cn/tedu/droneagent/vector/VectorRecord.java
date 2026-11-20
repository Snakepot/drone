package cn.tedu.droneagent.vector;

import java.util.Map;
import java.util.Objects;

/**
 * 一条**向量记录**：id + 向量 + 元数据。向量库里存的就是它。
 *
 * <h2>为什么元数据用 {@code Map} 而不是固定字段</h2>
 * 这一层（{@code vector} 包）是**基础设施**，不给某个业务场景定字段名：知识库要存
 * 「来源 / 章节 / 权限码」，将来记忆检索要存「会话号 / 用户 / 日期」。
 * 字段名由**写入方与集合 schema 约定**，本层只负责原样存取与按条件过滤。
 *
 * <h2>为什么构造时拷贝向量</h2>
 * {@code float[]} 是**可变**的：若直接持有外部传进来的数组，调用方之后改了它，
 * 库里的向量会**悄悄跟着变**。构造时拷贝一次，把这种「改了 A 影响 B」的坑堵掉。
 *
 * @param id       主键。同 id 再次写入即**覆盖**（幂等重建的基础）
 * @param vector   向量，长度必须与集合维度一致
 * @param metadata 随记录一起存取与过滤的标量字段
 */
public record VectorRecord(String id, float[] vector, Map<String, Object> metadata) {

    public VectorRecord {
        Objects.requireNonNull(id, "id 不能为空");
        Objects.requireNonNull(vector, "vector 不能为空");
        vector = vector.clone();
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
