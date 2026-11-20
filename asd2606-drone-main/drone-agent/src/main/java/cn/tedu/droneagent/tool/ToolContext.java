package cn.tedu.droneagent.tool;

import java.util.Set;

/**
 * 执行工具时**调用方的身份**：角色 + 权限码 + 原始 Token。
 *
 * <h2>为什么要把身份「显式传」进工具，而不是让工具自己去取</h2>
 * 工具跑在**执行器线程**上（外部调用可能慢，不能占着请求线程干等），
 * 而当前用户是放在 {@link cn.tedu.droneagent.common.UserContext}（ThreadLocal）里的——
 * **换到执行器线程就取不到了**。本项目为此反复踩坑，规矩是：
 * **身份在请求线程上取好、显式往下传**。
 *
 * <h2>为什么还要带原始 Token</h2>
 * 调用 `drone-mcp-server` 时，平台侧要用**用户本人**的身份做鉴权与审计。
 * 角色/权限码只够本地判断，转发给下游的业务接口需要原始 JWT。
 * 该字段只在**声明了 `forward-authorization` 的 MCP 服务器**上使用，
 * 其他工具不应读取它。
 *
 * <h2>【安全】两条硬规矩</h2>
 * <ol>
 *   <li>{@link #anonymous()} 表示没有身份，工具应当**什么都不给**（fail-closed），
 *       绝不能当成「不受限制」；</li>
 *   <li>{@code token} **绝不能进日志或 toString**——record 默认的 toString 会把字段全打出来，
 *       所以这里重写了它。</li>
 * </ol>
 *
 * @param roles       角色编码，如 {@code [admin]}
 * @param permissions 权限编码，如 {@code [agent:chat, agent:finance]}
 * @param token       当前请求的原始 JWT（不含 `Bearer ` 前缀）；没有身份时为 null
 * @param userId      当前用户 ID；检索长期记忆等「只看自己数据」的工具需要它（`REQ-AGENT-0021`）
 * @param traceId     本次对话的追踪 id；随 MCP 调用头透传到下游（`REQ-AGENT-0026`）
 * @param operationId 写操作 id（`REQ-AGENT-0029`）：续跑/重试复用同一个，MCP 幂等层据此去重
 */
public record ToolContext(Set<String> roles, Set<String> permissions, String token, Long userId,
                          String traceId, String operationId) {

    public ToolContext {
        roles = roles == null ? Set.of() : Set.copyOf(roles);
        permissions = permissions == null ? Set.of() : Set.copyOf(permissions);
    }

    /** 兼容既有调用：只带角色与权限，不转发身份 */
    public ToolContext(Set<String> roles, Set<String> permissions) {
        this(roles, permissions, null, null);
    }

    /** 兼容既有调用：带角色、权限与 Token（不带 userId / traceId） */
    public ToolContext(Set<String> roles, Set<String> permissions, String token) {
        this(roles, permissions, token, null, null, null);
    }

    /** 兼容既有调用：带 userId、不带 traceId */
    public ToolContext(Set<String> roles, Set<String> permissions, String token, Long userId) {
        this(roles, permissions, token, userId, null, null);
    }

    /** 兼容既有调用：带 traceId、不带 operationId */
    public ToolContext(Set<String> roles, Set<String> permissions, String token, Long userId,
                       String traceId) {
        this(roles, permissions, token, userId, traceId, null);
    }

    /** 没有身份（如离线运行、测试） */
    public static ToolContext anonymous() {
        return new ToolContext(Set.of(), Set.of(), null, null, null, null);
    }

    /** 只有角色（授权判定用得到；按数据收窄的工具还需要权限码） */
    public static ToolContext ofRoles(Set<String> roles) {
        return new ToolContext(roles, Set.of(), null, null, null, null);
    }

    /** 完整身份（请求线程上取好，显式传给执行器线程上的工具） */
    public static ToolContext ofUser(Long userId, Set<String> roles, Set<String> permissions, String token) {
        return new ToolContext(roles, permissions, token, userId, null, null);
    }

    /** 带上本次对话的 traceId；身份字段保持不变 */
    public ToolContext withTraceId(String traceId) {
        return new ToolContext(roles, permissions, token, userId, traceId, operationId);
    }

    /** 带上写操作 id（续跑/重试复用） */
    public ToolContext withOperationId(String operationId) {
        return new ToolContext(roles, permissions, token, userId, traceId, operationId);
    }

    /** 是否携带了可转发的用户身份 */
    public boolean hasToken() {
        return token != null && !token.isBlank();
    }

    @Override
    public String toString() {
        // 刻意不打印 token：调用链上任何一处 log.info("context={}", context) 都不能泄漏凭据
        return "ToolContext[roles=" + roles + ", permissions=" + permissions
                + ", userId=" + userId
                + ", traceId=" + traceId
                + ", operationId=" + operationId
                + ", token=" + (hasToken() ? "***" : "null") + "]";
    }
}
