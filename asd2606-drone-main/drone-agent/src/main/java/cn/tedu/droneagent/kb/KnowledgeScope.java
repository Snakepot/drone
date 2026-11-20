package cn.tedu.droneagent.kb;

import cn.tedu.droneagent.vector.VectorFilter;

import java.util.Collection;
import java.util.List;

/**
 * 知识库的**可见范围**：把「当前用户的权限码」翻译成检索用的过滤条件。
 *
 * <h2>为什么单独一个类</h2>
 * 「权限码存在哪个字段」「没有权限码时怎么办」是**知识库场景**的约定，
 * 不该塞进基础设施层（向量层不认识 `perm_codes` 这个字段名）。
 * 这里把它收口，向量层只管按条件过滤。
 *
 * <h2>【安全】fail-closed</h2>
 * 用户**没有任何权限码**时返回 {@link VectorFilter#none()}（**匹配零条**），
 * 而**不是**「没有条件」。若返回空条件，向量库会把「不限制」理解成「全都给」——
 * 那就是把内部资料对所有人开放，是**越权**。
 *
 * <p>同理，{@code ContainsAny} 的取值在本项目里**不允许为空**（构造时就拒绝），
 * 从两头堵住「空 = 不过滤」这条歧路。
 */
public final class KnowledgeScope {

    /** 可见权限码所在的字段名（与集合 schema 一致） */
    public static final String PERMISSION_FIELD = "perm_codes";

    private KnowledgeScope() {
    }

    /**
     * 构造「命中当前用户**任一**权限码即可见」的条件。
     *
     * @param permissionCodes 当前用户的权限码（来自 `drone-server` 的 RBAC）；
     *                        **为空时返回「匹配零条」**，即谁都看不到
     */
    public static VectorFilter visibleTo(Collection<String> permissionCodes) {
        if (permissionCodes == null || permissionCodes.isEmpty()) {
            return VectorFilter.none();
        }
        return VectorFilter.containsAny(PERMISSION_FIELD, List.copyOf(permissionCodes));
    }
}
