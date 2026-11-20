package cn.tedu.droneagent.tool;

import cn.tedu.droneagent.tool.PendingActionStore.PendingAction;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PendingActionStore} 的单元测试。
 *
 * <p>对应验收条件：`docs/sdd/specs/REQ-AGENT-0006-危险工具治理.md` 的
 * <b>AC-4</b>（过期即拒）、<b>AC-5</b>（重复确认只执行一次），以及归属校验。
 */
class PendingActionStoreTest {

    private static final long USER = 1001L;

    @TempDir
    Path root;

    private PendingActionStore persisted() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        return new PendingActionStore(Duration.ofMinutes(5), root, mapper);
    }

    private static final List<ModelMessage> SNAPSHOT = List.of(ModelMessage.user("删掉设备 #12"));

    /** 模型原始的 assistant 工具帧（续跑要原样回放） */
    private static final ModelMessage FRAME = ModelMessage.assistantToolCalls(
            List.of(new ToolCall("call_1", "delete_device", "{\"id\":12}")), "reasoning");

    @Test
    @DisplayName("存入后能取出；再取一次就没了（消费即失效，重复确认不会执行两次）")
    void takeIsConsumedOnce() {
        PendingActionStore store = new PendingActionStore(Duration.ofMinutes(5));
        PendingAction action = store.put(USER, "conv-1", "delete_device", "{\"id\":12}", SNAPSHOT, FRAME, "call_1");

        Optional<PendingActionStore.PendingAction> first = store.take(action.id(), USER);
        Optional<PendingActionStore.PendingAction> second = store.take(action.id(), USER);

        assertTrue(first.isPresent());
        assertEquals("delete_device", first.get().toolName());
        assertEquals(SNAPSHOT, first.get().messages());
        assertFalse(second.isPresent(), "同一张票据不该被消费两次");
    }

    @Test
    @DisplayName("别人拿 id 也消费不掉（归属校验）")
    void cannotTakeOthersAction() {
        PendingActionStore store = new PendingActionStore(Duration.ofMinutes(5));
        PendingAction action = store.put(USER, "conv-1", "delete_device", "{}", SNAPSHOT, FRAME, "call_1");

        assertFalse(store.take(action.id(), 9999L).isPresent());
        // 别人试过之后，本人仍然能用
        assertTrue(store.take(action.id(), USER).isPresent());
    }

    @Test
    @DisplayName("AC-4 过期即拒")
    void expiredActionRejected() {
        // ttl 取 1 纳秒：创建之后必然已过期
        PendingActionStore store = new PendingActionStore(Duration.ofNanos(1));
        PendingAction action = store.put(USER, "conv-1", "delete_device", "{}", SNAPSHOT, FRAME, "call_1");

        assertFalse(store.take(action.id(), USER).isPresent());
    }

    @Test
    @DisplayName("持久化：重启后（新实例）仍能确认")
    void survivesRestart() {
        PendingAction action = persisted().put(USER, "conv-1", "delete_device", "{\"id\":12}",
                SNAPSHOT, FRAME, "call_1", "trace-1");

        PendingActionStore restarted = persisted();
        Optional<PendingAction> taken = restarted.take(action.id(), USER);

        assertTrue(taken.isPresent(), "票据应能从文件恢复");
        assertEquals("trace-1", taken.get().traceId());
        assertFalse(restarted.take(action.id(), USER).isPresent(), "消费后重启也不该复活");
    }

    @Test
    @DisplayName("持久化：取消会话后票据作废，重启也查不到")
    void cancelByConversationRemovesFile() {
        PendingActionStore store = persisted();
        PendingAction action = store.put(USER, "conv-1", "delete_device", "{}", SNAPSHOT, FRAME, "call_1");

        assertEquals(1, store.removeByConversation(USER, "conv-1"));
        assertEquals(0, store.listByConversation(USER, "conv-1").size());
        assertFalse(persisted().take(action.id(), USER).isPresent());
    }

    @Test
    @DisplayName("持久化：过期票据在启动时清理")
    void expiredFilesArePurgedOnStartup() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        PendingActionStore shortTtl = new PendingActionStore(Duration.ofNanos(1), root, mapper);
        PendingAction action = shortTtl.put(USER, "conv-1", "delete_device", "{}", SNAPSHOT, FRAME, "call_1");

        // 用「已过期」的 TTL 重新加载：启动时应清理
        PendingActionStore restarted = new PendingActionStore(Duration.ofNanos(1), root, mapper);

        assertEquals(0, restarted.size());
        assertFalse(restarted.take(action.id(), USER).isPresent());
    }

    @Test
    @DisplayName("不存在的 id / 空 id 都返回空，不抛异常")
    void unknownIdIsEmpty() {
        PendingActionStore store = new PendingActionStore(Duration.ofMinutes(5));

        assertFalse(store.take("不存在", USER).isPresent());
        assertFalse(store.take(null, USER).isPresent());
        assertFalse(store.take("  ", USER).isPresent());
    }
}
