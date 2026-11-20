package cn.tedu.droneagent.memory;

import cn.tedu.droneagent.dto.ChatMessage;
import cn.tedu.droneagent.tool.ToolContext;
import cn.tedu.droneagent.tool.ToolResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.unit.DataSize;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 长期记忆检索：命中、未命中、跨用户隔离、匿名 fail-closed。 */
class MemorySearchToolTest {

    @TempDir
    Path root;

    private LongTermMemoryStore store() {
        return new LongTermMemoryStore(root, DataSize.ofMegabytes(2));
    }

    private static Session session(long userId, String conversationId, ChatMessage... messages) {
        Session session = new Session(new SessionKey(userId, conversationId),
                Instant.parse("2026-10-20T08:00:00Z"));
        for (ChatMessage message : messages) {
            session.append(message);
        }
        return session;
    }

    private static ToolContext user(long userId) {
        return ToolContext.ofUser(userId, Set.of("pilot"), Set.of("task:execute"), null);
    }

    @Test
    @DisplayName("命中：按任务号检索到历史原文，并带会话与时间")
    void findsByTaskId() {
        LongTermMemoryStore store = store();
        store.write(session(1L, "conv-a",
                new ChatMessage("user", "任务 TK-2026-1001 的负责人是张伟"),
                new ChatMessage("assistant", "已记录")));
        MemorySearchTool tool = new MemorySearchTool(store);

        ToolResult result = tool.invoke("{\"query\":\"TK-2026-1001\"}", user(1L));

        assertTrue(result.ok(), result.content());
        assertTrue(result.content().contains("张伟"), result.content());
        assertTrue(result.content().contains("conv-a"), "要带会话标识，便于模型注明出处");
        assertTrue(result.content().contains("2026-10-20"), "要带时间");
    }

    @Test
    @DisplayName("未命中：返回明确话术，不编造")
    void missReturnsHint() {
        MemorySearchTool tool = new MemorySearchTool(store());

        ToolResult result = tool.invoke("{\"query\":\"TK-9999-0001\"}", user(1L));

        assertTrue(result.ok());
        assertTrue(result.content().contains("没有找到相关记忆"), result.content());
    }

    @Test
    @DisplayName("隔离：只能检索到自己的会话")
    void cannotSeeOtherUsersMemory() {
        LongTermMemoryStore store = store();
        store.write(session(1L, "conv-a", new ChatMessage("user", "张伟负责 UAV-001")));
        store.write(session(2L, "conv-b", new ChatMessage("user", "李明负责 UAV-002")));
        MemorySearchTool tool = new MemorySearchTool(store);

        ToolResult result = tool.invoke("{\"query\":\"UAV\"}", user(2L));

        assertTrue(result.content().contains("UAV-002"));
        assertFalse(result.content().contains("UAV-001"), "不能看到别的用户的记忆");
    }

    @Test
    @DisplayName("匿名调用：fail-closed，不读任何文件")
    void anonymousIsRejected() {
        MemorySearchTool tool = new MemorySearchTool(store());

        ToolResult result = tool.invoke("{\"query\":\"UAV\"}", ToolContext.anonymous());

        assertFalse(result.ok());
        assertTrue(result.content().contains("没有用户身份"), result.content());
    }

    @Test
    @DisplayName("参数缺失：明确报错，不返回空结果")
    void missingQueryIsRejected() {
        MemorySearchTool tool = new MemorySearchTool(store());

        ToolResult result = tool.invoke("{}", user(1L));

        assertFalse(result.ok());
        assertTrue(result.content().contains("query"), result.content());
    }

    @Test
    @DisplayName("摘录有长度上限，不会把整段长对话塞回上下文")
    void excerptIsBounded() {
        LongTermMemoryStore store = store();
        store.write(session(1L, "conv-long",
                new ChatMessage("user", "任务 TK-2026-1001 的背景：" + "很长的描述".repeat(200))));
        MemorySearchTool tool = new MemorySearchTool(store);

        ToolResult result = tool.invoke("{\"query\":\"TK-2026-1001\"}", user(1L));

        assertTrue(result.content().length() <= 2000, "总长应受 2000 字符约束，实际 " + result.content().length());
        assertTrue(result.content().contains("…"), "截断处应有省略号");
    }
}
