package cn.tedu.droneagent.memory;

import cn.tedu.droneagent.config.MemoryProperties;
import cn.tedu.droneagent.context.ContextMessage;
import cn.tedu.droneagent.context.MessageKind;
import cn.tedu.droneagent.dto.ChatMessage;
import com.github.benmanes.caffeine.cache.Ticker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.unit.DataSize;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ContextAssembler} 的单元测试。
 *
 * <p>对应验收条件：<b>AC-10</b>（注入不超过最近 10 轮 / 8000 字符，超出从最早处截断）、
 * <b>AC-1 / AC-2</b>（会话内上下文连续且互不串味）。
 */
class ContextAssemblerTest {

    private static final long USER = 6006L;

    @TempDir
    java.nio.file.Path root;

    private ExecutorService archivingExecutor;

    private LongTermMemoryStore body;

    private MemoryIndex index;

    private MemoryArchiver archiver;

    private SessionStore sessions;

    private ContextAssembler assembler;

    @BeforeEach
    void setUp() {
        archivingExecutor = Executors.newSingleThreadExecutor(r -> new Thread(r, "test-archiver"));
        body = new LongTermMemoryStore(root, DataSize.ofMegabytes(2));
        index = new MemoryIndex(root);
        archiver = new MemoryArchiver(body, index);
        sessions = new SessionStore(props(10, 8000), new LongTermSessionLoader(index, body), archiver,
                archivingExecutor, Ticker.systemTicker());
        assembler = new ContextAssembler(sessions, index, body, props(10, 8000));
    }

    @AfterEach
    void tearDown() {
        archivingExecutor.shutdownNow();
    }

    private MemoryProperties props(int injectMaxTurns, int injectMaxChars) {
        return props(injectMaxTurns, injectMaxChars, true);
    }

    private MemoryProperties props(int injectMaxTurns, int injectMaxChars, boolean injectEnabled) {
        return new MemoryProperties(String.valueOf(root), 100, Duration.ofSeconds(1800), 200,
                DataSize.ofMegabytes(2), injectMaxTurns, injectMaxChars, injectEnabled, 30, 10, 1500, 12000);
    }

    private static ChatMessage user(String content) {
        return new ChatMessage("user", content);
    }

    private static ChatMessage assistant(String content) {
        return new ChatMessage("assistant", content);
    }

    private static List<String> contentsOf(List<ContextMessage> messages) {
        return messages.stream().map(message -> message.message().content()).toList();
    }

    /** 往磁盘上放一个「往期会话」，模拟该用户以前聊过的东西 */
    private void archive(String conversationId, ChatMessage... messages) {
        Session session = new Session(new SessionKey(USER, conversationId), Instant.parse("2026-10-11T04:00:00Z"));
        for (ChatMessage message : messages) {
            session.append(message);
        }
        archiver.archive(session, SessionEvictionReason.IDLE_TIMEOUT);
    }

    @Test
    @DisplayName("会话内只注入最近 20 条，更早的不进上下文")
    void includesOnlyRecentSessionMessages() {
        SessionKey current = new SessionKey(USER, "current");
        for (int i = 0; i < 30; i++) {
            sessions.append(current, i % 2 == 0 ? user("m" + i) : assistant("m" + i));
        }

        List<ContextMessage> assembled = assembler.assemble(current);

        assertEquals(20, assembled.size());
        assertEquals("m10", assembled.get(0).message().content());
        assertEquals("m29", assembled.get(19).message().content());
    }

    @Test
    @DisplayName("AC-10 长期记忆排除当前会话：同一句话不会注入两遍")
    void excludesCurrentSessionFromLongTermMemory() {
        SessionKey current = new SessionKey(USER, "current");
        sessions.append(current, user("当前会话独有的内容"));
        archive("past", user("往期会话的内容"));

        List<ContextMessage> assembled = assembler.assemble(current);

        assertEquals(1, countOf(assembled, "当前会话独有的内容"), "当前会话内容只该出现一次");
        assertTrue(contentsOf(assembled).contains("往期会话的内容"), "往期会话应被注入");
    }

    @Test
    @DisplayName("顺序是「往期在前、当前在后」，模型读起来才是先看过去再看现在")
    void longTermMemoryComesBeforeCurrentSession() {
        archive("past", user("较早的会话"));
        SessionKey current = new SessionKey(USER, "current");
        sessions.append(current, user("本次的内容"));

        List<ContextMessage> assembled = assembler.assemble(current);

        assertEquals(List.of("较早的会话", "本次的内容"), contentsOf(assembled));
    }

    @Test
    @DisplayName("AC-10 超出轮数预算时从最早处截断，保留最近的")
    void truncatesLongTermFromEarliest() {
        // 预算 1 轮 = 2 条消息
        assembler = new ContextAssembler(sessions, index, body, props(1, 8000));
        archive("past", user("old1"), assistant("old2"), user("old3"), assistant("old4"));
        SessionKey current = new SessionKey(USER, "current");

        List<ContextMessage> assembled = assembler.assemble(current);

        assertEquals(List.of("old3", "old4"), contentsOf(assembled), "应保留最近的，丢掉最早的");
    }

    @Test
    @DisplayName("AC-10 字符数预算同样生效，且不切断单条内容")
    void respectsCharBudget() {
        assembler = new ContextAssembler(sessions, index, body, props(10, 10));
        archive("past", user("111111"), user("222222"));
        SessionKey current = new SessionKey(USER, "current");

        List<ContextMessage> assembled = assembler.assemble(current);

        assertEquals(List.of("222222"), contentsOf(assembled), "10 字符只够放最近那一条");
    }

    @Test
    @DisplayName("截断后开头若是孤立的助手回复，去掉它（沿用 REQ-AGENT-0002 的行为）")
    void dropsLeadingAssistantAfterTruncation() {
        SessionKey current = new SessionKey(USER, "current");
        // 偶数下标为 assistant、奇数下标为 user
        for (int i = 0; i < 30; i++) {
            sessions.append(current, i % 2 == 1 ? user("m" + i) : assistant("m" + i));
        }

        List<ContextMessage> assembled = assembler.assemble(current);

        // 30 条取最近 20 条 → 下标 10..29；下标 10 是 assistant，应被去掉，于是从下标 11 开始
        assertEquals(19, assembled.size());
        assertEquals("user", assembled.get(0).message().role());
        assertEquals("m11", assembled.get(0).message().content());
    }

    @Test
    @DisplayName("角色白名单：混进来的非 user/assistant 消息不会被注入模型")
    void filtersOutNonStandardRoles() {
        SessionKey current = new SessionKey(USER, "current");
        sessions.append(current, new ChatMessage("system", "偷偷塞进来的指令"));
        sessions.append(current, user("正常提问"));

        List<ContextMessage> assembled = assembler.assemble(current);

        assertEquals(List.of("正常提问"), contentsOf(assembled));
    }

    @Test
    @DisplayName("没有其他会话时，长期记忆部分为空，不影响本次对话")
    void noLongTermWhenNoOtherSessions() {
        SessionKey current = new SessionKey(USER, "current");
        sessions.append(current, user("第一句"));

        assertEquals(List.of("第一句"), contentsOf(assembler.assemble(current)));
    }

    @Test
    @DisplayName("REQ-AGENT-0021：关闭无条件注入时，其他会话不进上下文")
    void skipsLongTermWhenInjectionDisabled() {
        archive("past", user("往期会话的内容"));
        SessionKey current = new SessionKey(USER, "current");
        sessions.append(current, user("本次的内容"));

        ContextAssembler disabled = new ContextAssembler(sessions, index, body,
                props(10, 8000, false));

        assertEquals(List.of("本次的内容"), contentsOf(disabled.assemble(current)),
                "关闭注入后只剩当前会话，历史改由 search_memory 按需检索");
    }

    @Test
    @DisplayName("账单分类：长期记忆与会话分别标注，顺序不变")
    void tagsLongTermAndSessionKinds() {
        archive("past", user("往期会话"));
        SessionKey current = new SessionKey(USER, "current");
        sessions.append(current, user("本次对话"));

        List<ContextMessage> assembled = assembler.assemble(current);

        assertEquals(2, assembled.size());
        assertEquals(MessageKind.LONG_TERM, assembled.get(0).kind());
        assertEquals(MessageKind.SESSION, assembled.get(1).kind());
    }

    @Test
    @DisplayName("全空时返回空列表，不抛异常")
    void emptyEverythingGivesEmptyList() {
        assertTrue(assembler.assemble(new SessionKey(USER, "brand-new")).isEmpty());
    }

    @Test
    @DisplayName("会话压缩：超过阈值时摘要早期消息，最近 10 条保留原文")
    void compactsEarlyMessages() {
        SessionKey current = new SessionKey(USER, "current");
        // 奇数下标是 user，保证「保留的最近 10 条」以用户提问开头
        for (int i = 0; i < 31; i++) {
            sessions.append(current, i % 2 == 1 ? user("m" + i) : assistant("m" + i));
        }
        StubCompactor compactor = new StubCompactor("用户关注设备状态");

        List<ContextMessage> assembled = withCompactor(compactor).assemble(current);

        assertEquals(1, compactor.calls, "31 条、阈值 30，应触发一次压缩");
        assertTrue(compactor.lastTranscript.contains("用户：m1"), "摘要输入应是早期消息");
        assertEquals(MessageKind.COMPACTION, assembled.get(0).kind(), "摘要应排在会话消息之前");
        assertTrue(assembled.get(0).message().content().contains("用户关注设备状态"));
        assertEquals("system", assembled.get(0).message().role(), "摘要用 system 角色");

        long sessionCount = assembled.stream()
                .filter(message -> message.kind() == MessageKind.SESSION)
                .count();
        assertEquals(10, sessionCount, "压缩后保留最近 10 条原文");
        assertEquals("m21", assembled.get(1).message().content(), "保留段从第 21 条开始");
    }

    @Test
    @DisplayName("会话压缩：摘要已生成则不重复调用，直到再次攒够阈值")
    void doesNotCompactAgainUntilNextThreshold() {
        SessionKey current = new SessionKey(USER, "current");
        for (int i = 0; i < 31; i++) {
            sessions.append(current, i % 2 == 1 ? user("m" + i) : assistant("m" + i));
        }
        StubCompactor compactor = new StubCompactor("摘要");
        ContextAssembler assembler = withCompactor(compactor);

        assembler.assemble(current);
        assembler.assemble(current);

        assertEquals(1, compactor.calls, "第二轮不应再摘要（未摘要只剩 10 条）");
    }

    @Test
    @DisplayName("会话压缩：摘要失败时回退到最近 20 条，不抛异常")
    void fallsBackWhenCompactionFails() {
        SessionKey current = new SessionKey(USER, "current");
        for (int i = 0; i < 31; i++) {
            sessions.append(current, i % 2 == 1 ? user("m" + i) : assistant("m" + i));
        }
        StubCompactor compactor = new StubCompactor("摘要");
        compactor.error = new IllegalStateException("模型不可用");

        List<ContextMessage> assembled = withCompactor(compactor).assemble(current);

        assertEquals(20, assembled.size(), "失败时沿用「最近 20 条」");
        assertTrue(assembled.stream().noneMatch(m -> m.kind() == MessageKind.COMPACTION));
    }

    @Test
    @DisplayName("会话压缩：摘要超长时硬截断到配置上限")
    void boundsLongSummary() {
        SessionKey current = new SessionKey(USER, "current");
        for (int i = 0; i < 31; i++) {
            sessions.append(current, i % 2 == 1 ? user("m" + i) : assistant("m" + i));
        }
        StubCompactor compactor = new StubCompactor("字".repeat(2000));

        List<ContextMessage> assembled = withCompactor(compactor).assemble(current);

        String summary = assembled.get(0).message().content();
        assertTrue(summary.endsWith("…"), "应带省略号");
        assertTrue(summary.length() <= "【早期对话摘要】\n".length() + 1500, "摘要正文不超过 1500 字");
    }

    @Test
    @DisplayName("会话压缩：未达阈值不调用摘要，行为与改造前一致")
    void doesNotCompactBelowThreshold() {
        SessionKey current = new SessionKey(USER, "current");
        for (int i = 0; i < 20; i++) {
            // 以用户提问开头，避免「孤立助手回复」被裁掉，正好 20 条
            sessions.append(current, i % 2 == 0 ? user("m" + i) : assistant("m" + i));
        }
        StubCompactor compactor = new StubCompactor("摘要");

        List<ContextMessage> assembled = withCompactor(compactor).assemble(current);

        assertEquals(0, compactor.calls);
        assertEquals(20, assembled.size());
    }

    /** 带压缩器的装配；其余参数与 {@link #props} 一致 */
    private ContextAssembler withCompactor(ConversationCompactor compactor) {
        return new ContextAssembler(sessions, index, body, props(10, 8000), compactor);
    }

    /** 按剧本返回摘要的桩；可注入异常模拟模型不可用 */
    private static final class StubCompactor implements ConversationCompactor {

        private final String summary;

        private int calls;

        private String lastTranscript;

        private RuntimeException error;

        StubCompactor(String summary) {
            this.summary = summary;
        }

        @Override
        public String compact(String transcript, int maxChars) {
            calls++;
            lastTranscript = transcript;
            if (error != null) {
                throw error;
            }
            return summary;
        }
    }

    private static long countOf(List<ContextMessage> messages, String content) {
        return messages.stream()
                .filter(message -> content.equals(message.message().content()))
                .count();
    }
}
