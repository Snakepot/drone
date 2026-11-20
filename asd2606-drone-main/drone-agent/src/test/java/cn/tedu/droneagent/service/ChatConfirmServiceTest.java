package cn.tedu.droneagent.service;

import cn.tedu.droneagent.common.LoginUser;
import cn.tedu.droneagent.common.UserContext;
import cn.tedu.droneagent.config.DeepSeekProperties;
import cn.tedu.droneagent.context.ContextMessage;
import cn.tedu.droneagent.context.MessageKind;
import cn.tedu.droneagent.dto.ChatMessage;
import cn.tedu.droneagent.dto.ChatResponse;
import cn.tedu.droneagent.memory.SessionKey;
import cn.tedu.droneagent.prompt.TestPrompts;
import cn.tedu.droneagent.tool.ToolContext;
import cn.tedu.droneagent.tool.ModelTurn;
import cn.tedu.droneagent.tool.PendingActionStore;
import cn.tedu.droneagent.tool.Tool;
import cn.tedu.droneagent.tool.ToolCall;
import cn.tedu.droneagent.tool.ToolDefinition;
import cn.tedu.droneagent.tool.ToolDescriptor;
import cn.tedu.droneagent.tool.ToolGovernance;
import cn.tedu.droneagent.tool.ToolRegistry;
import cn.tedu.droneagent.tool.ToolResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ChatConfirmService} 的单元测试。
 *
 * <p>对应验收条件：`docs/sdd/specs/REQ-AGENT-0006-危险工具治理.md` 的
 * <b>AC-3</b>（确认后执行并续跑）、<b>AC-4</b>（过期即拒）、<b>AC-5</b>（重复确认只执行一次），
 * 外加票据的会话归属。
 */
class ChatConfirmServiceTest {

    private static final long USER = 1001L;

    private static final SessionKey KEY = new SessionKey(USER, "conv-1");

    /** 确认路径的角色来自 {@link UserContext}（请求线程），这里模拟「已登录的管理员」 */
    @BeforeEach
    void setUp() {
        UserContext.set(new LoginUser().setId(USER).setRoles(Set.of("admin")));
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    /** 按脚本回答的模型桩 */
    private static final class ScriptedChat extends ChatService {

        private final Deque<ModelTurn> script;

        ScriptedChat(ModelTurn... turns) {
            super(new DeepSeekProperties("http://unused", "k", "m", null, false),
                    TestPrompts.of("sys"), new ObjectMapper());
            this.script = new ArrayDeque<>(List.of(turns));
        }

        @Override
        public ModelTurn chatStep(SessionKey key, List<ContextMessage> messages, List<ToolDefinition> tools,
                                  String traceId, java.util.Set<String> permissions) {
            return script.isEmpty() ? ModelTurn.text("（脚本用尽）") : script.poll();
        }
    }

    private static Tool deleteDevice(AtomicInteger executed) {
        return new Tool() {
            @Override
            public String name() {
                return "delete_device";
            }

            @Override
            public ToolResult invoke(String argumentsJson) {
                executed.incrementAndGet();
                return ToolResult.ok("已删除设备");
            }
        };
    }

    private static ToolRegistry registryOf(Tool tool) {
        return new ToolRegistry(List.of(tool),
                List.of(new ToolDescriptor("delete_device", "删除设备", "{}", false, false)), new ObjectMapper());
    }

    private static ToolGovernance adminOnly() {
        return new ToolGovernance(List.of(new ToolDescriptor("delete_device", "删除设备", "{}", false, false)),
                List.of("delete_device"), Map.of("admin", List.of("delete_device")));
    }

    private static ModelTurn deleteCall() {
        return new ModelTurn(null, List.of(new ToolCall("c1", "delete_device", "{\"id\":12}")));
    }

    /** 先跑一次 /chat，拿到「待确认」票据 id */
    private static String pendingIdOf(ToolCallingService service) {
        ToolCallingService.AskResult result = service.askOutcome(KEY,
                List.of(ContextMessage.of(new ChatMessage("user", "删掉设备12"),
                        MessageKind.SESSION)),
                "t0", ToolContext.ofRoles(Set.of("admin")));
        assertNotNull(result.pending(), "危险工具应先返回待确认");
        return result.pending().pendingId();
    }

    @Test
    @DisplayName("AC-3 确认后真正执行，并继续这一轮")
    void approveExecutesAndResumes() {
        AtomicInteger executed = new AtomicInteger();
        PendingActionStore store = new PendingActionStore(Duration.ofMinutes(5));
        ScriptedChat chat = new ScriptedChat(deleteCall(), ModelTurn.text("已删除设备 12"));
        ToolCallingService calling = new ToolCallingService(chat, registryOf(deleteDevice(executed)),
                adminOnly(), store);
        ChatConfirmService confirm = new ChatConfirmService(store, calling);

        String pendingId = pendingIdOf(calling);
        assertEquals(0, executed.get(), "确认前不该执行");

        ChatResponse response = confirm.confirm(KEY, pendingId, true);

        assertEquals("已删除设备 12", response.reply());
        assertEquals(1, executed.get(), "确认后应执行一次");
        assertNull(response.pending());
    }

    @Test
    @DisplayName("AC-5 重复确认只执行一次；第二次返回「已处理或已过期」")
    void repeatedConfirmExecutesOnce() {
        AtomicInteger executed = new AtomicInteger();
        PendingActionStore store = new PendingActionStore(Duration.ofMinutes(5));
        ScriptedChat chat = new ScriptedChat(deleteCall(), ModelTurn.text("已删除"));
        ToolCallingService calling = new ToolCallingService(chat, registryOf(deleteDevice(executed)),
                adminOnly(), store);
        ChatConfirmService confirm = new ChatConfirmService(store, calling);

        String pendingId = pendingIdOf(calling);

        confirm.confirm(KEY, pendingId, true);
        ChatResponse second = confirm.confirm(KEY, pendingId, true);

        assertEquals(1, executed.get(), "重复确认不该执行第二次");
        assertTrue(second.reply().contains("已处理或已过期"));
    }

    @Test
    @DisplayName("拒绝：不执行工具，但照样把这一轮跑完")
    void rejectDoesNotExecute() {
        AtomicInteger executed = new AtomicInteger();
        PendingActionStore store = new PendingActionStore(Duration.ofMinutes(5));
        ScriptedChat chat = new ScriptedChat(deleteCall(), ModelTurn.text("好的，已取消"));
        ToolCallingService calling = new ToolCallingService(chat, registryOf(deleteDevice(executed)),
                adminOnly(), store);
        ChatConfirmService confirm = new ChatConfirmService(store, calling);

        String pendingId = pendingIdOf(calling);

        ChatResponse response = confirm.confirm(KEY, pendingId, false);

        assertEquals(0, executed.get(), "拒绝不该执行工具");
        assertEquals("好的，已取消", response.reply());
    }

    @Test
    @DisplayName("AC-4 过期票据 → 拒绝执行")
    void expiredRejected() {
        AtomicInteger executed = new AtomicInteger();
        PendingActionStore store = new PendingActionStore(Duration.ofNanos(1));   // 建好即过期
        ScriptedChat chat = new ScriptedChat(deleteCall(), ModelTurn.text("x"));
        ToolCallingService calling = new ToolCallingService(chat, registryOf(deleteDevice(executed)),
                adminOnly(), store);
        ChatConfirmService confirm = new ChatConfirmService(store, calling);

        String pendingId = pendingIdOf(calling);

        ChatResponse response = confirm.confirm(KEY, pendingId, true);

        assertEquals(0, executed.get());
        assertTrue(response.reply().contains("已处理或已过期"));
    }

    @Test
    @DisplayName("票据不属于当前会话 → 拒绝")
    void wrongConversationRejected() {
        AtomicInteger executed = new AtomicInteger();
        PendingActionStore store = new PendingActionStore(Duration.ofMinutes(5));
        ScriptedChat chat = new ScriptedChat(deleteCall(), ModelTurn.text("x"));
        ToolCallingService calling = new ToolCallingService(chat, registryOf(deleteDevice(executed)),
                adminOnly(), store);
        ChatConfirmService confirm = new ChatConfirmService(store, calling);

        String pendingId = pendingIdOf(calling);

        ChatResponse response = confirm.confirm(new SessionKey(USER, "conv-2"), pendingId, true);

        assertEquals(0, executed.get());
        assertTrue(response.reply().contains("不属于当前会话"));
    }
}
