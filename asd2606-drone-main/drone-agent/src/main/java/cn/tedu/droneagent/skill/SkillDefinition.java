package cn.tedu.droneagent.skill;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 一个标准化业务流程（Skill）的完整定义。
 *
 * <h2>三层内容分别放在哪</h2>
 * <ul>
 *   <li><b>L1</b>：{@code name / description / whenToUse / permissions} —— 常驻系统提示词；</li>
 *   <li><b>L2</b>：{@code body}（步骤与输出模板）—— 调 {@code use_skill} 时才交给模型；</li>
 *   <li><b>L3</b>：{@code resources}（references/ 下的文本）—— 再调 {@code read_skill_resource}。</li>
 * </ul>
 *
 * @param name        流程名（必须等于目录名）
 * @param description 一句话说明（进 L1）
 * @param whenToUse   适用场景（进 L1，帮助模型判断何时加载）
 * @param version     正整数版本
 * @param owner       业务归属（如「运营部」）
 * @param permissions 可见/可用所需的权限码；用户须**全部具备**
 * @param tools       该流程会用到的工具名；启动时校验是否存在
 * @param body        正文（L2）
 * @param resources   参考资料：相对路径 → 文本（L3）
 * @param source      来源路径，排障用
 */
public record SkillDefinition(
        String name,
        String description,
        String whenToUse,
        int version,
        String owner,
        List<String> permissions,
        List<String> tools,
        String body,
        Map<String, String> resources,
        String source
) {

    public SkillDefinition {
        permissions = permissions == null ? List.of() : List.copyOf(permissions);
        tools = tools == null ? List.of() : List.copyOf(tools);
        resources = resources == null ? Map.of() : Map.copyOf(resources);
    }

    /**
     * 当前用户是否可见/可用：**全部权限具备**才算。
     *
     * <p>用「全部」而不是「任一」：一个日报流程要同时查任务与告警，
     * 只满足一半时让它出现，模型会调用后拿到 403，体验更差。
     */
    public boolean visibleTo(Set<String> userPermissions) {
        return userPermissions != null && userPermissions.containsAll(permissions);
    }
}
