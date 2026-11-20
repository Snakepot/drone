package cn.tedu.droneagent.kb;

import java.util.List;
import java.util.Objects;

/**
 * 一条**可见范围规则**：命中这个路径的（一批）资料，归这些权限码可见。
 *
 * <h2>为什么权限声明在「资料之外」</h2>
 * 内部资料是**给别人看的东西**（操作手册、保险方案……），格式五花八门（md / pdf / docx），
 * **不能为了接入检索去改原始资料**。所以可见范围一律**在配置里声明**，
 * 靠「资料在哪个路径」来匹配——资料本身一个字都不用动。
 *
 * <p>多条规则**按顺序匹配，先命中先用**（所以把更具体的规则写在前面）。
 *
 * @param path  相对知识库根目录的**路径模式**（glob），如 {@code 财务/**}、{@code **&#47;*.pdf}
 * @param perms 命中后授予的权限码，如 {@code [agent:finance]}
 */
public record PermissionRule(String path, List<String> perms) {

    public PermissionRule {
        Objects.requireNonNull(path, "path 不能为空");
        perms = List.copyOf(perms == null ? List.of() : perms);
    }
}
