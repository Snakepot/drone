package cn.tedu.droneagent.budget;

import cn.tedu.droneagent.config.BudgetProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 预算检查（REQ-AGENT-0028）：口径、开关与读取失败放行。 */
class BudgetServiceTest {

    @Test
    @DisplayName("日额度：达到上限即超限，未达到不拦")
    void checksDailyLimit() {
        BudgetService service = new BudgetService(
                new BudgetProperties(true, 1000, 100), userId -> 100L, traceId -> 0L);

        BudgetService.Check exceeded = service.checkDaily(5);
        assertTrue(exceeded.exceeded());
        assertEquals(100, exceeded.used());
        assertEquals(100, exceeded.limit());

        BudgetService below = new BudgetService(
                new BudgetProperties(true, 1000, 100), userId -> 99L, traceId -> 0L);
        assertFalse(below.checkDaily(5).exceeded());
    }

    @Test
    @DisplayName("单请求：按 trace 累计，超限即停")
    void checksRequestLimit() {
        BudgetService service = new BudgetService(
                new BudgetProperties(true, 50, 1000), userId -> 0L, traceId -> 60L);

        BudgetService.Check check = service.checkRequest("trace-1");
        assertTrue(check.exceeded());
        assertEquals(60, check.used());
        assertEquals(50, check.limit());
    }

    @Test
    @DisplayName("关闭开关：永不超限")
    void disabledNeverExceeds() {
        BudgetService service = new BudgetService(
                new BudgetProperties(false, 1, 1), userId -> 999_999L, traceId -> 999_999L);

        assertFalse(service.checkDaily(5).exceeded());
        assertFalse(service.checkRequest("t").exceeded());
    }

    @Test
    @DisplayName("读取用量失败：按未超限放行（可用性优先）")
    void readFailureFailsOpen() {
        BudgetService service = new BudgetService(
                new BudgetProperties(true, 10, 10),
                userId -> {
                    throw new IllegalStateException("账单文件损坏");
                },
                traceId -> {
                    throw new IllegalStateException("账单文件损坏");
                });

        assertFalse(service.checkDaily(5).exceeded());
        assertFalse(service.checkRequest("t").exceeded());
    }
}
