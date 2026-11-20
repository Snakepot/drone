package cn.tedu.droneagent.vector;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 检索的**过滤条件**：只让「符合条件」的记录参与相似度比较。
 *
 * <h2>为什么要有它，而不是直接传一个字符串表达式</h2>
 * 两个原因，缺一不可：
 * <ol>
 *   <li><b>权限必须前置</b>：无权内容要在**检索阶段**就被挡掉，而不是「先查出来再假装没看见」。
 *       于是过滤条件必须能下推到向量库；</li>
 *   <li><b>单测不能连真库</b>（宪法 §5）：内存实现要能**独立地**把同一个条件算出来，
 *       所以条件得是**结构化**的——一份描述，两个实现各自解读：
 *       内存实现直接 {@link #matches} 求值，Milvus 适配把它翻译成库的过滤表达式。</li>
 * </ol>
 * 若只传字符串，内存实现就得去解析 Milvus 的表达式语法，那是**把库的细节漏进基础设施层**。
 *
 * <h2>它不负责什么</h2>
 * <ul>
 *   <li><b>不知道业务字段含义</b>：字段名由调用方给（如知识库用 {@code perm_codes}）。</li>
 *   <li><b>不排序、不卡阈值</b>：那是检索与场景的事。</li>
 * </ul>
 *
 * <h2>【安全提醒】</h2>
 * {@link Always} **不设任何限制**，只该用在测试或确实无权限概念的场景。
 * **知识库检索必须传权限过滤**——用 {@code Always} 等于把内部资料对所有人开放。
 */
public sealed interface VectorFilter {

    /** 判断一条记录的元数据是否满足条件 */
    boolean matches(Map<String, Object> metadata);

    /** 不设限制（**仅测试 / 无权限场景**，见接口说明的安全提醒） */
    record Always() implements VectorFilter {
        @Override
        public boolean matches(Map<String, Object> metadata) {
            return true;
        }
    }

    /**
     * **匹配零条**：谁都不满足。
     *
     * <p>这是权限的 **fail-closed** 兜底：用户**没有任何权限码**时，必须返回它——
     * 而不是「没有条件」（那等于对所有人开放）。实现在 {@link VectorStore#search} 里**直接短路**，
     * 连向量库都不必访问。
     */
    record None() implements VectorFilter {
        @Override
        public boolean matches(Map<String, Object> metadata) {
            return false;
        }
    }

    /** 字段**等于**某个值 */
    record Eq(String field, String value) implements VectorFilter {
        public Eq {
            Objects.requireNonNull(field, "field 不能为空");
            Objects.requireNonNull(value, "value 不能为空");
        }

        @Override
        public boolean matches(Map<String, Object> metadata) {
            return value.equals(String.valueOf(metadata.get(field)));
        }
    }

    /** 字段（多值，如权限码列表）**包含其中之一**——权限过滤用它 */
    record ContainsAny(String field, List<String> values) implements VectorFilter {

        public ContainsAny {
            Objects.requireNonNull(field, "field 不能为空");
            values = List.copyOf(values);
            // 【安全】空值必须当场拒绝：翻译成库表达式时「空」会退化成「不过滤」＝对所有人开放。
            // «没有任何权限码» 的正确表达是 None（匹配零条），不是 ContainsAny 空列表。
            if (values.isEmpty()) {
                throw new IllegalArgumentException(
                        "ContainsAny 的取值不能为空；「谁都看不到」请用 VectorFilter.None()");
            }
        }

        @Override
        public boolean matches(Map<String, Object> metadata) {
            Object raw = metadata.get(field);
            if (!(raw instanceof Collection<?> candidates)) {
                return false;
            }
            for (Object candidate : candidates) {
                if (values.contains(String.valueOf(candidate))) {
                    return true;
                }
            }
            return false;
        }
    }

    /** 若干条件**同时成立** */
    record And(List<VectorFilter> clauses) implements VectorFilter {

        public And {
            clauses = List.copyOf(clauses);
        }

        @Override
        public boolean matches(Map<String, Object> metadata) {
            for (VectorFilter clause : clauses) {
                if (!clause.matches(metadata)) {
                    return false;
                }
            }
            return true;
        }
    }

    static VectorFilter always() {
        return new Always();
    }

    /** 匹配零条（权限的 fail-closed 兜底） */
    static VectorFilter none() {
        return new None();
    }

    static VectorFilter eq(String field, String value) {
        return new Eq(field, value);
    }

    static VectorFilter containsAny(String field, List<String> values) {
        return new ContainsAny(field, values);
    }

    static VectorFilter and(VectorFilter... clauses) {
        return new And(List.of(clauses));
    }
}
