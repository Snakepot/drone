package cn.tedu.droneagent.service;

import cn.tedu.droneagent.config.DeepSeekProperties;
import cn.tedu.droneagent.context.ContextMessage;
import cn.tedu.droneagent.prompt.TestPrompts;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ChatService} 关于「发给模型的请求长什么样」的单元测试。
 *
 * <h2>这组用例是从哪来的</h2>
 * 本类原来有一套「校验客户端提交的历史」的用例（角色白名单、长度上限、尾部必须是提问等）。
 * `REQ-AGENT-0003` 把历史改成**服务端组装**之后，那些校验整体移到了
 * {@link cn.tedu.droneagent.memory.ContextAssembler}——在源头拦得更早，
 * 留在这一层既拦不住也已经多余。于是那些用例随之作废（现由
 * {@code ContextAssemblerTest} 覆盖同名行为），本类改为只测它现在真正负责的事：
 * **系统提示词的位置与上下文的顺序**。
 */
class ChatServiceTest {

    private static final String SYSTEM_PROMPT = "你是无人机管理平台的助手";

    private final ChatService service = new ChatService(
            new DeepSeekProperties("http://unused", "unused", "test-model", null, false),
            TestPrompts.of(SYSTEM_PROMPT), new ObjectMapper());

    private static ContextMessage user(String content) {
        return ContextMessage.session(new cn.tedu.droneagent.tool.ModelMessage("user", content, null, null));
    }

    private static ContextMessage assistant(String content) {
        return ContextMessage.session(new cn.tedu.droneagent.tool.ModelMessage("assistant", content, null, null));
    }

    @Test
    @DisplayName("系统提示词永远排第一条，且内容来自服务端配置")
    void systemPromptAlwaysComesFirst() {
        List<ContextMessage> messages = service.withSystemPrompt(List.of(user("你好")), java.util.Set.of());

        assertEquals(2, messages.size());
        assertEquals("system", messages.get(0).message().role());
        assertEquals(SYSTEM_PROMPT, messages.get(0).message().content());
    }

    @Test
    @DisplayName("上下文保持给定顺序，不被重排")
    void keepsGivenOrder() {
        List<ContextMessage> messages = service.withSystemPrompt(List.of(
                user("问题一"), assistant("回复一"), user("问题二")), java.util.Set.of());

        assertEquals(List.of("system", "user", "assistant", "user"),
                messages.stream().map(message -> message.message().role()).toList());
        assertEquals("问题二", messages.get(messages.size() - 1).message().content());
    }

    @Test
    @DisplayName("上下文为空时只剩系统提示词，不抛异常")
    void emptyContextYieldsOnlySystemPrompt() {
        List<ContextMessage> messages = service.withSystemPrompt(List.of(), java.util.Set.of());

        assertEquals(1, messages.size());
        assertEquals("system", messages.get(0).message().role());
    }

    @Test
    @DisplayName("会话压缩使用独立提示词 memory.compaction（目录里没有时按名字报错）")
    void compactUsesItsOwnPrompt() {
        RuntimeException error = assertThrows(RuntimeException.class,
                () -> service.compact("用户：你好", 100));

        assertTrue(error.getMessage().contains("memory.compaction"),
                "应明确报出缺的是哪个提示词，实际=" + error.getMessage());
    }

    @Test
    @DisplayName("摘要提示词来自目录：目录里没有 tool.summary 时按名字报错（证明不再硬编码）")
    void summarizeTakesPromptFromCatalog() {
        // 这个 service 的目录只有系统提示词，没有 tool.summary
        ChatService without = new ChatService(
                new DeepSeekProperties("http://unused", "unused", "test-model", null, false),
                TestPrompts.of(SYSTEM_PROMPT), new ObjectMapper());

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> without.summarize("一段待压缩的文本", 100));

        assertTrue(e.getMessage().contains("tool.summary"),
                "报错应指明取的是哪条提示词，实际：" + e.getMessage());
    }
}
