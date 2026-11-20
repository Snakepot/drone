package cn.tedu.droneagent.memory;

import cn.tedu.droneagent.dto.ChatMessage;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Optional;

/**
 * 会话懒加载：内存里没有该会话时，从长期记忆把它恢复回来。
 *
 * <p>这是「重启后还记得」这条链路的最后一环——
 * {@link SessionStore} 在缓存未命中时调用它，于是进程重启、或会话被淘汰过之后，
 * 用户回来接着聊仍然接得上。
 *
 * <h2>恢复出来的内容是**脱敏后**的</h2>
 * 正文落盘前已经脱敏（见 {@link SensitiveMasker}），所以这里读回来的也是脱敏版本：
 * 用户当初说的手机号，恢复后是 {@code 138****8000}，模型看到的也是这个。
 *
 * <p>这是「落盘必须脱敏」与「能从盘上恢复」两条要求叠加的必然结果，不是缺陷。
 * 但它有实际影响：**跨重启的长对话里，敏感字段的信息已经不可逆地损失了**。
 * 记住这一点，免得日后把「模型记不住我上次说的号码」当成 bug 去查。
 *
 * <h2>为什么每次都要读一遍整份索引</h2>
 * 索引里才有该会话对应的正文文件名。加载只发生在缓存未命中时——
 * 一个会话在一次进程生命周期里最多触发一次，之后就一直待在内存里，
 * 所以这点读盘开销是被摊薄掉的，不值得为此再加一层索引缓存。
 *
 * <h2>它在链路里的位置，以及它不负责什么</h2>
 * <p><b>位置</b>：{@link SessionLoader} 的当前实现，**长期记忆流回内存的唯一入口**；
 * 由 {@link SessionStore} 在缓存未命中时调用。</p>
 *
 * <ul>
 *   <li><b>不落盘</b>：它只读；把会话写出去是 {@link MemoryArchiver} 的事。</li>
 *   <li><b>不管缓存</b>：取回来的会话放不放进内存、放多久，由 {@link SessionStore} 决定。</li>
 *   <li><b>不脱敏</b>：正文里本来就已经是脱敏后的内容，这里只负责读回来
 *       （所以恢复出的内容也是脱敏版，见上一节）。</li>
 * </ul>
 */
@Slf4j
public class LongTermSessionLoader implements SessionLoader {

    private final MemoryIndex index;

    private final LongTermMemoryStore store;

    public LongTermSessionLoader(MemoryIndex index, LongTermMemoryStore store) {
        this.index = index;
        this.store = store;
    }

    @Override
    public Session load(SessionKey key) {
        Optional<IndexEntry> entry = findIndexEntry(key);
        if (entry.isEmpty()) {
            // 长期记忆里没有这个会话：不告警，这是最常见的正常情况（新会话）
            return null;
        }

        String fileName = entry.get().fileName();
        List<ChatMessage> messages = store.read(key.userId(), fileName, key.conversationId()).orElse(null);
        if (messages == null) {
            // 索引说有、正文里没有。按写入顺序（先正文后索引）这种组合不该出现，
            // 多半是正文文件被手工删过或搬走了。按「不存在」处理并告警，别让它静默
            log.warn("索引里有会话 {}（文件 {}），但正文里找不到该段，按不存在处理",
                    key, fileName);
            return null;
        }

        Session session = new Session(key, entry.get().startedAt());
        messages.forEach(session::append);
        // INFO 而不是 debug：这是「文件 → 内存」真正发生的那一刻。
        // 用箭头写明方向，读日志的人不用先搞清「谁是谁的长期记忆」
        log.info("【内存←文件】已把会话恢复回内存：userId={}，conversationId={}，消息数={}",
                key.userId(), key.conversationId(), messages.size());
        return session;
    }

    /** 索引按「最新在前」返回，同名会话已被收敛成一条，所以取第一条即最新 */
    private Optional<IndexEntry> findIndexEntry(SessionKey key) {
        return index.list(key.userId()).stream()
                .filter(entry -> entry.conversationId().equals(key.conversationId()))
                .findFirst();
    }
}
