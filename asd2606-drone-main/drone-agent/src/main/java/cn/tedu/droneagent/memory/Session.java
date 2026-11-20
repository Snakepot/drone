package cn.tedu.droneagent.memory;

import cn.tedu.droneagent.dto.ChatMessage;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 一个会话在内存里的状态。
 *
 * <h2>为什么方法要加锁</h2>
 * 缓存的淘汰线程随时可能在另一处调用 {@link #messages()}（例如归档时读内容），
 * 而请求线程正在 {@link #append}。不加锁就是并发读写同一个 {@code ArrayList}。
 *
 * <p>注意这把锁**只保证单次操作的原子性**，不解决「同一会话的两条请求谁先谁后」——
 * 那件事由上层串行化负责（否则两条请求的上下文会交错）。
 *
 * <h2>它在链路里的位置，以及它不负责什么</h2>
 * <p><b>位置</b>：短期记忆里真正被存的那个对象，由 {@link SessionKey} 标识，
 * 被 {@link SessionStore} 保管、被 {@code ContextAssembler} 读取、被归档写出。
 *
 * <ul>
 *   <li><b>不懂存储</b>：它不知道自己存在内存还是文件里，也不知道什么时候会被淘汰。</li>
 *   <li><b>不懂过期与归档</b>：判断空闲、容量、要不要落盘都不是它的事。</li>
 *   <li><b>不做串行化</b>：上面那把锁只管单次追加的原子性，不管请求顺序。</li>
 * </ul>
 */
public final class Session {

    private final SessionKey key;

    private final Instant startedAt;

    private final List<ChatMessage> messages = new ArrayList<>();

    /**
     * 早期对话的摘要（`REQ-AGENT-0020`）；{@code null} 表示还没压缩过。
     *
     * <p>刻意**不落盘**：归档写的是完整原文，摘要是「给模型看的视图」，
     * 重启后按需重新生成。丢了不丢数据，只是多一次摘要调用。
     */
    private String summary;

    /** 已摘要到第几条（不含）：{@code messages[0, summarizedUpTo)} 已进摘要 */
    private int summarizedUpTo;

    public Session(SessionKey key, Instant startedAt) {
        this.key = key;
        this.startedAt = startedAt;
    }

    public SessionKey key() {
        return key;
    }

    public Instant startedAt() {
        return startedAt;
    }

    public synchronized void append(ChatMessage message) {
        messages.add(message);
    }

    /** 返回快照而非内部列表，避免调用方（或归档线程）拿到可变引用后被并发修改 */
    public synchronized List<ChatMessage> messages() {
        return List.copyOf(messages);
    }

    public synchronized int size() {
        return messages.size();
    }

    /** 当前生效的历史摘要；没压缩过返回 {@code null} */
    public synchronized String summary() {
        return summary;
    }

    /** 已摘要到第几条（不含） */
    public synchronized int summarizedUpTo() {
        return summarizedUpTo;
    }

    /**
     * 记下摘要与游标。
     *
     * <p>游标只前进不后退；空摘要不写入（避免把一次失败的压缩当成成功）。
     *
     * @param summary 摘要正文
     * @param upTo    已摘要到的消息下标（不含）
     */
    public synchronized void compact(String summary, int upTo) {
        if (summary == null || summary.isBlank()) {
            return;
        }
        this.summary = summary;
        this.summarizedUpTo = Math.max(this.summarizedUpTo, Math.min(upTo, messages.size()));
    }
}
