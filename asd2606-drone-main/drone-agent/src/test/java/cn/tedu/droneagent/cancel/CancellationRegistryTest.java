package cn.tedu.droneagent.cancel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 取消注册表（REQ-AGENT-0029）：归属、置位与清理。 */
class CancellationRegistryTest {

    @Test
    @DisplayName("按 userId+conversationId 登记与取消；别人取消不了")
    void cancelsOnlyOwnRequest() {
        CancellationRegistry registry = new CancellationRegistry();
        registry.register(5L, "conv-1", "trace-1");

        assertFalse(registry.isCancelled(5L, "conv-1"));
        assertFalse(registry.cancel(6L, "conv-1"), "不同用户不能取消别人的请求");
        assertTrue(registry.cancel(5L, "conv-1"));
        assertTrue(registry.isCancelled(5L, "conv-1"));
        assertTrue(registry.cancel(5L, "conv-1"), "重复取消仍返回 true（幂等）");
    }

    @Test
    @DisplayName("请求结束清理后，再取消返回 false")
    void clearRemovesEntry() {
        CancellationRegistry registry = new CancellationRegistry();
        registry.register(5L, "conv-1", "trace-1");

        registry.clear(5L, "conv-1");

        assertFalse(registry.cancel(5L, "conv-1"));
        assertNull(registry.traceIdOf(5L, "conv-1"));
    }

    @Test
    @DisplayName("没有在途请求时取消返回 false")
    void cancelMissingReturnsFalse() {
        assertFalse(new CancellationRegistry().cancel(5L, "not-running"));
    }
}
