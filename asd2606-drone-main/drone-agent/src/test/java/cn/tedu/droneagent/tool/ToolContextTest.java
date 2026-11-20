package cn.tedu.droneagent.tool;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ToolContext} 的安全约束：Token 不能出现在 toString 里。
 *
 * <p>这条不是洁癖：调用链上有不少 {@code log.debug("context={}", context)} 式的写法，
 * record 默认的 toString 会把所有字段打印出来，一个 token 泄漏就足够横向移动。
 */
class ToolContextTest {

    @Test
    @DisplayName("toString 必须遮蔽 Token")
    void toStringMasksToken() {
        ToolContext context = new ToolContext(Set.of("admin"), Set.of("agent:chat"), "jwt-should-not-leak");

        String text = context.toString();

        assertFalse(text.contains("jwt-should-not-leak"), text);
        assertTrue(text.contains("token=***"), text);
    }

    @Test
    @DisplayName("没有 Token 时 hasToken=false，toString 显示 null")
    void anonymousHasNoToken() {
        ToolContext context = ToolContext.anonymous();

        assertFalse(context.hasToken());
        assertTrue(context.toString().contains("token=null"));
    }

    @Test
    @DisplayName("完整身份带 userId，检索记忆的工具靠它做隔离")
    void carriesUserId() {
        ToolContext context = ToolContext.ofUser(42L, Set.of("pilot"), Set.of("task:execute"), null);

        assertTrue(context.userId() == 42L);
        assertTrue(context.toString().contains("userId=42"));
    }

    @Test
    @DisplayName("匿名与只有角色的上下文没有 userId（工具应 fail-closed）")
    void withoutUserHasNoId() {
        assertTrue(ToolContext.anonymous().userId() == null);
        assertTrue(ToolContext.ofRoles(Set.of("admin")).userId() == null);
    }

    @Test
    @DisplayName("withTraceId 保留身份，且 traceId 进 toString（Token 仍遮蔽）")
    void withTraceIdKeepsIdentity() {
        ToolContext context = ToolContext
                .ofUser(42L, Set.of("pilot"), Set.of("task:execute"), "jwt-should-not-leak")
                .withTraceId("trace-1234");

        assertEquals(42L, context.userId());
        assertEquals("trace-1234", context.traceId());
        assertTrue(context.toString().contains("traceId=trace-1234"));
        assertFalse(context.toString().contains("jwt-should-not-leak"));
    }
}
