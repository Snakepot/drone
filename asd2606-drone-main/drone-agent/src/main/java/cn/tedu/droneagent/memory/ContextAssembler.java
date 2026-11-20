package cn.tedu.droneagent.memory;

import cn.tedu.droneagent.config.MemoryProperties;
import cn.tedu.droneagent.context.ContextMessage;
import cn.tedu.droneagent.context.MessageKind;
import cn.tedu.droneagent.dto.ChatMessage;
import cn.tedu.droneagent.tool.ModelMessage;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 上下文组装：把「本次会话的内容」与「该用户过往会话的记忆」拼成发给模型的消息列表。
 *
 * <h2>拼出来的顺序</h2>
 * <pre>
 * [系统提示词]              ← 由 ChatService 放在最前，不经本类
 * [长期记忆：较早的会话…]     ← 最老在前
 * [当前会话：最近若干条…]      ← 含用户刚刚发的这一条
 * </pre>
 * 按时间顺序排，模型读起来才是「先看过去、再看现在」。
 *
 * <h2>为什么长期记忆要排除当前会话</h2>
 * 当前会话已经整段注入在下面了。再从这里拼一遍，同一句话会出现两次——
 * 既白占预算，又会让模型以为发生过两轮相同对话。
 *
 * <h2>预算与截断</h2>
 * 长期记忆同时受两道限制，谁先到就停：轮数与字符数。
 * <b>「轮」按一问一答算，即 2 条消息</b>——这是本类的解释，写入配置时按此理解。
 *
 * <p>截断方向是**从最早处丢**：从最近的会话往回取，一旦某条放不下就整体停止，
 * 不再尝试更早的。近期内容比远期有用，这是刻意的。
 *
 * <p>会话内那部分只按条数封顶（沿用 {@code REQ-AGENT-0002} 的 20 条），不另设字符上限——
 * 它本来就是这个会话刚聊过的内容，再砍一刀只会让对话忽然断片。
 *
 * <h2>它不负责什么</h2>
 * <ul>
 *   <li><b>不调模型</b>：它只准备消息，发不发出去是 {@code ChatService} 的事。</li>
 *   <li><b>不写会话</b>：把消息写回会话是 {@code ConversationService} 的事。</li>
 *   <li><b>不做脱敏</b>：内存与发给模型的上下文都保留原文；只有落盘的副本才脱敏
 *       （见 {@link SensitiveMasker}）。这是有意的——模型需要完整语义。</li>
 * </ul>
 */
@Slf4j
public class ContextAssembler {

    /** 会话内上下文最多注入多少条，沿用 `REQ-AGENT-0002` 的值 */
    private static final int SESSION_MESSAGE_LIMIT = 20;

    /** 「一轮」按一问一答算 */
    private static final int MESSAGES_PER_TURN = 2;

    /** 摘要注入时的前缀：让模型一眼看出这是背景，不是用户刚说的话 */
    private static final String SUMMARY_PREFIX = "【早期对话摘要】\n";

    private final SessionStore sessions;

    private final MemoryIndex index;

    private final LongTermMemoryStore longTermMemory;

    /** 摘要器；为 null 表示不启用压缩（既有测试与不需要压缩的场景） */
    private final ConversationCompactor compactor;

    private final int injectMaxTurns;

    private final int injectMaxChars;

    /**
     * 是否每轮无条件注入其他会话的长期记忆（`REQ-AGENT-0021`）。
     *
     * <p>默认关闭：改为模型按需调用 {@code search_memory}。保留开关是为了回滚——
     * 老行为是「最近 10 轮 / 8000 字符」。
     */
    private final boolean injectEnabled;

    /** 未摘要消息超过它才压缩；保证至少大于保留条数，避免每轮都压 */
    private final int compactTrigger;

    private final int compactKeep;

    private final int compactSummaryChars;

    private final int compactInputChars;

    public ContextAssembler(SessionStore sessions, MemoryIndex index,
                            LongTermMemoryStore longTermMemory, MemoryProperties properties) {
        this(sessions, index, longTermMemory, properties, null);
    }

    public ContextAssembler(SessionStore sessions, MemoryIndex index,
                            LongTermMemoryStore longTermMemory, MemoryProperties properties,
                            ConversationCompactor compactor) {
        this.sessions = sessions;
        this.index = index;
        this.longTermMemory = longTermMemory;
        this.compactor = compactor;
        this.injectMaxTurns = properties.injectMaxTurns();
        this.injectMaxChars = properties.injectMaxChars();
        this.injectEnabled = properties.injectEnabled();
        this.compactKeep = Math.max(0, properties.compactKeepMessages());
        // 触发点必须大于保留条数：否则每轮都会把刚攒的一两条再摘要一遍
        this.compactTrigger = Math.max(properties.compactTriggerMessages(), compactKeep + 1);
        this.compactSummaryChars = Math.max(1, properties.compactSummaryChars());
        this.compactInputChars = Math.max(1, properties.compactInputChars());
    }

    /**
     * 组装某会话这一轮要发给模型的消息（不含系统提示词）。
     *
     * <p>返回的是**带计量包装**的消息：长期记忆与会话分成两类，
     * 账单据此才能把「这条钱花在记忆上还是花在当前对话上」说清楚。
     *
     * @param current 本次请求对应的会话
     * @return 按时间顺序排列的消息；两个来源都没有内容时返回空列表
     */
    public List<ContextMessage> assemble(SessionKey current) {
        Session session = sessions.getOrCreate(current);
        maybeCompact(current, session);

        List<ContextMessage> longTerm = longTermMessages(current);
        List<ContextMessage> sessionOnly = sessionMessages(session);

        // 这条日志是回答「模型为什么这么答」的第一手依据：
        // 光看 ChatService 打出来的那坨请求体，是数不出「长期记忆占了几条」的
        log.debug("组装上下文：userId={}，conversationId={}，长期记忆={}条，会话内={}条（其中摘要 {}），合计={}条",
                current.userId(), current.conversationId(),
                longTerm.size(), sessionOnly.size(), session.summary() == null ? 0 : 1,
                longTerm.size() + sessionOnly.size());

        List<ContextMessage> messages = new ArrayList<>(longTerm);
        messages.addAll(sessionOnly);
        return List.copyOf(messages);
    }

    /**
     * 会话内压缩（`REQ-AGENT-0020`）：未摘要的消息超过阈值时，把「除最近若干条以外」的部分压成摘要。
     *
     * <p>只在请求线程、会话锁内执行，因此同一会话不会并发压缩。
     * 任何失败都只记 WARN：摘要只是增强，不能影响用户这一问。
     */
    private void maybeCompact(SessionKey key, Session session) {
        if (compactor == null) {
            return;
        }
        int total = session.size();
        int from = session.summarizedUpTo();
        if (total - from <= compactTrigger) {
            return;
        }
        int upTo = total - compactKeep;
        if (upTo <= from) {
            return;
        }

        List<ChatMessage> chunk = session.messages().subList(from, upTo);
        String transcript = transcriptOf(chunk);
        if (transcript.isBlank()) {
            // 这一段全是非白名单角色，没必要调模型；直接推进游标
            session.compact("（这段早期对话没有可摘要的文字内容）", upTo);
            return;
        }
        try {
            String summary = compactor.compact(transcript, compactSummaryChars);
            if (summary == null || summary.isBlank()) {
                log.warn("【上下文压缩】模型返回空摘要，保持原上下文：userId={}，conversationId={}",
                        key.userId(), key.conversationId());
                return;
            }
            String bounded = bound(summary, compactSummaryChars);
            session.compact(bounded, upTo);
            log.info("【上下文压缩】完成：userId={}，conversationId={}，摘要 {} 条消息（下标 {}..{}），"
                            + "摘要 {} 字，保留最近 {} 条", key.userId(), key.conversationId(),
                    upTo - from, from, upTo, bounded.length(), compactKeep);
        } catch (RuntimeException e) {
            // 回退到旧行为：只取最近 20 条，用户这一问照常进行
            log.warn("【上下文压缩】失败，回退到只保留最近 {} 条：userId={}，conversationId={}，原因={}",
                    SESSION_MESSAGE_LIMIT, key.userId(), key.conversationId(), e.getMessage());
        }
    }

    /** 把待摘要的消息拼成「用户：… / 助手：…」；超长时保留更接近当前时间的后半段 */
    private String transcriptOf(List<ChatMessage> chunk) {
        StringBuilder text = new StringBuilder();
        for (ChatMessage message : chunk) {
            if (!isAllowedRole(message)) {
                continue;
            }
            text.append("user".equals(message.role()) ? "用户：" : "助手：")
                    .append(message.content()).append('\n');
        }
        if (text.length() <= compactInputChars) {
            return text.toString().stripTrailing();
        }
        String tail = text.substring(text.length() - compactInputChars);
        return "（更早的内容已省略）\n" + tail.stripLeading();
    }

    /** 摘要硬截断（提示词不保证听话）；截断点落在代理对中间时回退一位 */
    private static String bound(String summary, int maxChars) {
        if (summary.length() <= maxChars) {
            return summary;
        }
        int keep = Math.max(0, maxChars - 1);
        if (keep > 0 && Character.isHighSurrogate(summary.charAt(keep - 1))) {
            keep--;
        }
        return summary.substring(0, keep) + "…";
    }

    /** 当前会话最近的若干条 +（可选）历史摘要。会话不在内存时会触发懒加载（见 {@link SessionStore}） */
    private List<ContextMessage> sessionMessages(Session session) {
        List<ChatMessage> all = session.messages();
        int from = Math.min(session.summarizedUpTo(), all.size());
        List<ChatMessage> allowed = all.subList(from, all.size()).stream()
                .filter(ContextAssembler::isAllowedRole)
                .toList();

        List<ChatMessage> tail = allowed.subList(Math.max(0, allowed.size() - SESSION_MESSAGE_LIMIT), allowed.size());

        // 截断后开头若是孤立的助手回复，去掉它——留着会让模型对着一句没头没尾的话继续接
        // （这条规则沿用 `REQ-AGENT-0002` 的 AC-6）
        int start = 0;
        while (start < tail.size() && !"user".equals(tail.get(start).role())) {
            start++;
        }
        List<ChatMessage> recent = tail.subList(start, tail.size());

        List<ContextMessage> result = new ArrayList<>(recent.size() + 1);
        if (session.summary() != null && !session.summary().isBlank()) {
            // 摘要用 system 角色：它是背景，不该伪装成用户发言
            result.add(new ContextMessage(
                    new ModelMessage("system", SUMMARY_PREFIX + session.summary(), null, null, null),
                    MessageKind.COMPACTION, null));
        }
        for (ChatMessage message : recent) {
            result.add(ContextMessage.of(message, MessageKind.SESSION));
        }
        return List.copyOf(result);
    }

    /** 该用户其他会话的记忆，从最近往回取，直到轮数或字符数到顶 */
    private List<ContextMessage> longTermMessages(SessionKey current) {
        if (!injectEnabled) {
            // REQ-AGENT-0021：默认不再每轮注入；由模型按需调用 search_memory
            return List.of();
        }
        int messageBudget = injectMaxTurns * MESSAGES_PER_TURN;
        List<ChatMessage> newestFirst = new ArrayList<>();
        int chars = 0;
        boolean budgetExhausted = false;

        for (IndexEntry entry : index.list(current.userId())) {
            if (budgetExhausted) {
                break;
            }
            if (entry.conversationId().equals(current.conversationId())) {
                continue;
            }

            List<ChatMessage> history = longTermMemory
                    .read(current.userId(), entry.fileName(), entry.conversationId())
                    .orElse(List.of());

            for (int i = history.size() - 1; i >= 0 && !budgetExhausted; i--) {
                ChatMessage message = history.get(i);
                if (!isAllowedRole(message)) {
                    continue;
                }
                if (newestFirst.size() >= messageBudget || chars + message.content().length() > injectMaxChars) {
                    // 从最早处截断：这条放不下就整体停止，不再试更早的
                    // （打一条日志出来，否则「只注入了几条」会被误当成「本来就没多少记忆」）
                    log.debug("长期记忆已达预算，从最早处截断：userId={}，已收={}条/{}字符，上限为{}轮/{}字符",
                            current.userId(), newestFirst.size(), chars, injectMaxTurns, injectMaxChars);
                    budgetExhausted = true;
                    break;
                }
                newestFirst.add(message);
                chars += message.content().length();
            }
        }

        Collections.reverse(newestFirst);
        return newestFirst.stream()
                .map(message -> ContextMessage.of(message, MessageKind.LONG_TERM))
                .toList();
    }

    /** 角色白名单：只放行 user 与 assistant */
    private static boolean isAllowedRole(ChatMessage message) {
        return "user".equals(message.role()) || "assistant".equals(message.role());
    }
}
