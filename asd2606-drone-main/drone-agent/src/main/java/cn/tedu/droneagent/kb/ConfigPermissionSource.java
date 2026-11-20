package cn.tedu.droneagent.kb;

import lombok.extern.slf4j.Slf4j;

import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;

/**
 * 「可见范围」的声明机制：**配置里按路径规则授予**，配置默认兜底，都没有则**无人可见**。
 *
 * <h2>它解决什么</h2>
 * 常见做法是「在资料文件里写个头信息声明谁能看」——但那是**让你为了接入检索去改原始资料**，
 * 资料格式还五花八门，行不通。这里改成：**资料一个字不动**，在配置里按**路径**说「这个目录/这批文件归谁看」。
 *
 * <pre>
 * agent:
 *   kb:
 *     default-perms: []                    # 没命中任何规则时用；空 = 无人可见
 *     perm-rules:
 *       - path: "财务/**"                   # 相对知识库根目录
 *         perms: [agent:finance]
 *       - path: "**&#47;*.pdf"
 *         perms: [agent:chat]
 * </pre>
 *
 * <h2>取值顺序（先命中先用）</h2>
 * <ol>
 *   <li>按 {@code perm-rules} **顺序**匹配相对路径，**第一个命中**的规则生效；</li>
 *   <li>都没命中 → {@code default-perms}；</li>
 *   <li>默认也为空 → **空列表 = 谁都看不到**（fail-closed，宁可查不到也不能默认公开）。</li>
 * </ol>
 *
 * <h2>它不负责什么</h2>
 * <ul>
 *   <li><b>不校验权限码是否存在</b>：码的合法性由 `drone-server` 的 RBAC 说了算；写错了表现为「查不到」。</li>
 *   <li><b>不做过滤</b>：它只回答「这份资料归谁看」。</li>
 * </ul>
 */
@Slf4j
public class ConfigPermissionSource implements PermissionSource {

    private record CompiledRule(PathMatcher matcher, PermissionRule rule) {
    }

    private final Path root;

    private final List<CompiledRule> rules;

    private final List<String> defaults;

    /**
     * @param root     知识库根目录（用于把文件算成**相对路径**再匹配）
     * @param rules    路径规则，**顺序即优先级**
     * @param defaults 未命中任何规则时的默认权限码；为空表示没有默认
     */
    public ConfigPermissionSource(Path root, List<PermissionRule> rules, List<String> defaults) {
        this.root = root.toAbsolutePath().normalize();
        this.defaults = defaults == null ? List.of() : List.copyOf(defaults);

        List<CompiledRule> compiled = new ArrayList<>();
        for (PermissionRule rule : rules == null ? List.<PermissionRule>of() : rules) {
            compiled.add(new CompiledRule(
                    FileSystems.getDefault().getPathMatcher("glob:" + rule.path()), rule));
        }
        this.rules = List.copyOf(compiled);
    }

    @Override
    public List<String> permissionCodesOf(Path file) {
        Path relative = root.relativize(file.toAbsolutePath().normalize());
        for (CompiledRule candidate : rules) {
            if (candidate.matcher().matches(relative)) {
                return candidate.rule().perms();
            }
        }
        if (defaults.isEmpty()) {
            log.warn("【知识库】{} 未命中任何 perm-rules 且未配 default-perms → 该资料**无人可见**", relative);
        }
        return defaults;
    }
}
