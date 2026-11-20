package cn.tedu.droneagent.config;

import java.util.List;

/**
 * 工具可见性规则（`REQ-AGENT-0018`）：满足权限码的人，才能看到匹配该模式的工具。
 *
 * <h2>为什么用「模式 → 权限码」而不是给每个工具写一条</h2>
 * 70 个业务工具按前缀天然分组（`drone__task_*`、`drone__device_*`），
 * 用通配模式能把配置量压到二三十条；个别特例（如 `user_me` 谁都能看）
 * 排在通用规则前面即可，靠「先匹配先用」表达优先级。
 *
 * @param pattern 工具名模式，支持 {@code *} 通配（匹配任意长度）
 * @param anyOf   需要的权限码；**任一满足**即可见。空列表表示「不要求权限码」
 */
public record ToolVisibilityRule(String pattern, List<String> anyOf) {

    public ToolVisibilityRule {
        anyOf = anyOf == null ? List.of() : List.copyOf(anyOf);
    }
}
