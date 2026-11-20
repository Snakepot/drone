package cn.tedu.droneagent.plan;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanStoreTest {

    private static final long USER = 1001L;

    @Test
    @DisplayName("计划落盘后重启可读；同一 planId 只能消费一次")
    void persistsAndConsumesOnce(@TempDir Path dir) {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        PlanStore store = new PlanStore(Duration.ofMinutes(30), dir, mapper);
        Plan plan = plan("p-1");

        store.put(USER, plan);
        assertTrue(store.take(USER, "p-1").isPresent(), "第一次应取到");
        assertFalse(store.take(USER, "p-1").isPresent(), "第二次应已消费");

        store.put(USER, plan);
        PlanStore restarted = new PlanStore(Duration.ofMinutes(30), dir, mapper);
        assertTrue(restarted.take(USER, "p-1").isPresent(), "重启后应仍可读");
    }

    @Test
    @DisplayName("计划按用户隔离：别人拿到 planId 也取不到")
    void ownerOnly(@TempDir Path dir) {
        PlanStore store = new PlanStore(Duration.ofMinutes(30), dir,
                new ObjectMapper().findAndRegisterModules());
        store.put(USER, plan("p-2"));

        assertFalse(store.take(9999L, "p-2").isPresent());
        assertTrue(store.take(USER, "p-2").isPresent());
    }

    private static Plan plan(String id) {
        return new Plan(id, "test", Plan.Origin.GENERATED, 1, "goal", "user message",
                List.of(), List.of(new PlanStep("s1", "step", PlanStep.Kind.TOOL, "tool",
                java.util.Map.of(), List.of(), null, null)));
    }
}
