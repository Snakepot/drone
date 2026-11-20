package cn.tedu.droneagent.tool;

import cn.tedu.droneagent.config.ToolVisibilityRule;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 工具可见性的**权限门**：按配置规则判断「这个用户能不能看到这个工具」。
 *
 * <h2>两道门，各管各的</h2>
 * <pre>
 *   角色门（ToolGovernance.allows）  ← 能不能用（危险工具只授予特定角色）
 *   权限门（本类）                    ← 能看哪一块（如没有 staff:list 就看不到员工工具）
 * </pre>
 * 两个都通过才下发。刻意分开放：角色是「岗位」，权限是「数据范围」，
 * 合成一个判断会让配置很难解释。
 *
 * <h2>匹配顺序：先匹配先用</h2>
 * 规则按配置文件里的顺序匹配，第一条命中就定结果。这样「特例」可以排在「通用」前面，
 * 例如 {@code drone__user_me}（登录即可见）必须排在 {@code drone__user_*}（要 staff:list）之前。
 *
 * <h2>未匹配 = 可见</h2>
 * 地图、知识检索、Skill 元工具没有对应的业务权限码。若默认隐藏，功能会直接消失；
 * 所以默认放行，安全兜底仍靠执行侧的权限复核。
 */
public class ToolVisibility {

    private final List<Rule> rules;

    public ToolVisibility(List<ToolVisibilityRule> rules) {
        List<Rule> compiled = new ArrayList<>();
        if (rules != null) {
            for (ToolVisibilityRule rule : rules) {
                if (rule == null || rule.pattern() == null || rule.pattern().isBlank()) {
                    throw new IllegalStateException("agent.tool.visible-by 里有规则没写 pattern");
                }
                compiled.add(new Rule(globToPattern(rule.pattern()), Set.copyOf(rule.anyOf())));
            }
        }
        this.rules = List.copyOf(compiled);
    }

    /** 不做任何权限裁剪（测试与「规则为空」的部署） */
    public static ToolVisibility allowAll() {
        return new ToolVisibility(List.of());
    }

    /**
     * 权限门判定。
     *
     * @param toolName    工具名（如 {@code drone__task_list}）
     * @param permissions 当前用户的权限码；为 null 视为空
     * @return true = 可以下发给模型
     */
    public boolean allows(String toolName, Set<String> permissions) {
        Set<String> owned = permissions == null ? Set.of() : permissions;
        for (Rule rule : rules) {
            if (!rule.pattern().matcher(toolName).matches()) {
                continue;
            }
            // 第一条命中的规则说了算：空 anyOf = 不要求权限码（登录即可见）
            if (rule.anyOf().isEmpty()) {
                return true;
            }
            for (String code : rule.anyOf()) {
                if (owned.contains(code)) {
                    return true;
                }
            }
            return false;
        }
        return true;
    }

    /** 规则条数（启动日志与测试用） */
    public int ruleCount() {
        return rules.size();
    }

    /**
     * 把只有 {@code *} 的通配模式转成正则。
     *
     * <p>其余字符一律 {@link Pattern#quote(String)}，避免工具名里的下划线、点号被当成正则元字符。
     * 例如 {@code drone__task_*} → {@code ^\Qdrone__task_\E.*$}。
     */
    private static Pattern globToPattern(String glob) {
        StringBuilder regex = new StringBuilder("^");
        int from = 0;
        int star;
        while ((star = glob.indexOf('*', from)) >= 0) {
            regex.append(Pattern.quote(glob.substring(from, star))).append(".*");
            from = star + 1;
        }
        regex.append(Pattern.quote(glob.substring(from))).append('$');
        return Pattern.compile(regex.toString());
    }

    /** 编译后的一条规则 */
    private record Rule(Pattern pattern, Set<String> anyOf) {
    }
}
