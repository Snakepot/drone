package cn.tedu.droneagent.tool;

import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 危险工具的**治理**：谁是「危险工具」，以及「谁有权用」。
 *
 * <h2>两道判定</h2>
 * <ol>
 *   <li>{@link #isDangerous(String)}：命中危险清单的工具，执行前要**人工确认**（见 `REQ-AGENT-0006`）。</li>
 *   <li>{@link #allows(String, Set)}：**授权门**——当前用户的角色允不允许用这个工具。</li>
 * </ol>
 *
 * <h2>启动期就校验，别留到运行时</h2>
 * 清单里写了一个 `catalog` 里不存在的工具名（多半是拼错），**直接让服务启动失败**——
 * 否则表现会是「配了却没生效」，很难查。
 *
 * <h2>默认口子怎么开</h2>
 * 工具**没配**角色限制时：危险工具**默认拒绝**（fail-closed，宁可不让用），只读工具**默认放行**
 * （否则每加一个只读工具都要给所有角色登记一遍）。
 *
 * <h2>它在链路里的位置，以及它不负责什么</h2>
 * <ul>
 *   <li><b>不执行工具</b>：它只回答「能不能用、要不要确认」。</li>
 *   <li><b>不读用户身份</b>：角色由调用方传进来（见「显式传参」的既定约定）。</li>
 * </ul>
 */
@Slf4j
public class ToolGovernance {

    /** 危险工具清单 */
    private final Set<String> dangerous;

    /** 工具 → 允许使用它的角色集合（由配置的「角色 → 工具」**反转**而来，便于按工具查） */
    private final Map<String, Set<String>> rolesByTool;

    public ToolGovernance(List<ToolDescriptor> catalog, List<String> dangerous,
                          Map<String, List<String>> roleTools) {
        Set<String> known = new HashSet<>();
        if (catalog != null) {
            for (ToolDescriptor descriptor : catalog) {
                known.add(descriptor.name());
            }
        }

        this.dangerous = dangerous == null ? Set.of() : Set.copyOf(dangerous);

        Map<String, Set<String>> inverted = new HashMap<>();
        if (roleTools != null) {
            roleTools.forEach((role, tools) -> {
                if (tools == null) {
                    return;
                }
                for (String tool : tools) {
                    inverted.computeIfAbsent(tool, key -> new HashSet<>()).add(role);
                }
            });
        }
        this.rolesByTool = inverted;

        // 启动期校验：清单里的名字必须在 catalog 中存在
        for (String name : this.dangerous) {
            assertKnown(known, name, "agent.tool.dangerous");
        }
        for (String name : rolesByTool.keySet()) {
            assertKnown(known, name, "agent.tool.role-tools");
        }
    }

    /** 命中危险清单的工具，执行前要走人工确认 */
    public boolean isDangerous(String tool) {
        return dangerous.contains(tool);
    }

    /**
     * 授权门：当前用户的角色是否允许使用该工具。
     *
     * @param tool      工具名
     * @param userRoles 当前用户的角色；为 null / 空表示没有任何角色
     */
    public boolean allows(String tool, Set<String> userRoles) {
        Set<String> allowedRoles = rolesByTool.get(tool);
        if (allowedRoles == null) {
            // 没配角色限制：危险工具 fail-closed，其余默认放行
            return !isDangerous(tool);
        }
        if (userRoles == null || userRoles.isEmpty()) {
            return false;
        }
        return userRoles.stream().anyMatch(allowedRoles::contains);
    }

    private static void assertKnown(Set<String> known, String name, String where) {
        if (known.contains(name)) {
            return;
        }
        // MCP 工具的名字带「别名__」前缀。它们只在对应服务器**本次发现成功**时存在：
        // 服务器临时不可达属于可降级情形，不该让整个服务起不来。
        // 记 WARN 即可——工具没注册，就永远不会被调用，治理配置悬空是安全的。
        if (name != null && name.contains("__")) {
            log.warn("{} 里的工具「{}」本次未发现（多半是对应的 MCP 服务器未连上或未启用），该项本次不生效",
                    where, name);
            return;
        }
        throw new IllegalStateException(where + " 里的工具「" + name + "」不在 agent.tool.catalog 中");
    }
}
