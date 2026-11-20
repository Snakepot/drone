package cn.tedu.droneagent.vector;

/**
 * 集合里一个**标量字段**的定义。
 *
 * <h2>为什么要把它抽出来，而不是让适配器写死字段名</h2>
 * 向量库适配器属于**基础设施层**，不该认识某个业务的字段（那是知识库或记忆的事）。
 * 但真库的集合 schema 是**建表时就固定**的（Milvus 还要求给字符串字段写最大长度、给数组写容量），
 * 所以「有哪些字段」必须由**交给适配器的调用方**提供——适配器只负责把它翻译成建集合的报文。
 *
 * <p>这样知识库给一套字段、将来记忆检索给另一套，**适配器一行不改**（见 `plans` 第 9 节）。
 *
 * @param name        字段名
 * @param kind        字段类型
 * @param maxLength   {@link Kind#VARCHAR} / {@link Kind#STRING_ARRAY} 的最大长度；其他类型忽略
 * @param maxCapacity {@link Kind#STRING_ARRAY} 的元素个数上限；其他类型忽略
 */
public record FieldDef(String name, Kind kind, int maxLength, int maxCapacity) {

    public enum Kind {
        /** 变长字符串 */
        VARCHAR,
        /** 32 位整数 */
        INT32,
        /** 字符串数组（权限码就是它，可用 ARRAY_CONTAINS 过滤） */
        STRING_ARRAY
    }

    public static FieldDef varchar(String name, int maxLength) {
        return new FieldDef(name, Kind.VARCHAR, maxLength, 0);
    }

    public static FieldDef int32(String name) {
        return new FieldDef(name, Kind.INT32, 0, 0);
    }

    public static FieldDef stringArray(String name, int maxLength, int maxCapacity) {
        return new FieldDef(name, Kind.STRING_ARRAY, maxLength, maxCapacity);
    }
}
