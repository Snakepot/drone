package cn.tedu.droneagent.memory;

import java.util.regex.Pattern;

/**
 * 短期记忆的键：<b>用户 + 会话</b>。
 *
 * <h2>为什么必须带 userId</h2>
 * 「会话 id」是前端生成的，两个用户完全可能撞成同一个值（甚至有人故意拿别人的 id 来试）。
 * 只按会话 id 存，撞上就是串号；把 userId 并入键之后，**越权在第一层就不成立**——
 * 拿别人的会话 id 只会查到自己的空会话，查不到别人的内容。
 *
 * <h2>为什么 conversationId 要限定字符集</h2>
 * 它会**原样写进记忆文件的格式里**：索引行是 {@code conv=<id> | ...}，正文段头同理。
 * id 里若带 {@code |} 或换行，一行会被多切一段、或者凭空多出一行，把别人的记录顶歪。
 * 在构造处收口（唯一入口）比在每个写文件的地方各挡一次可靠。
 *
 * <h2>它在链路里的位置，以及它不负责什么</h2>
 * <p><b>位置</b>：所有会话操作的入口标识 —— 缓存查表、读写文件、串行化加锁，键都是它。
 *
 * <ul>
 *   <li><b>不做权限校验</b>：它只保证 id **形态**合法；
 *       「这条会话是不是他的」由键里带 userId 这个事实天然解决，不需要额外判断。</li>
 *   <li><b>不负责查找</b>：拿键去查会话是 {@link SessionStore} 的事，它只是个值对象。</li>
 * </ul>
 */
public record SessionKey(Long userId, String conversationId) {

    /** 允许的 id 形态：前端生成的 UUID 或类似短标识 */
    private static final Pattern SAFE_CONVERSATION_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    public SessionKey {
        if (userId == null) {
            throw new IllegalArgumentException("userId 不能为空");
        }
        if (conversationId == null || conversationId.isBlank()) {
            throw new IllegalArgumentException("conversationId 不能为空");
        }
        if (!SAFE_CONVERSATION_ID.matcher(conversationId).matches()) {
            throw new IllegalArgumentException(
                    "conversationId 只能包含字母、数字、下划线、连字符，且长度 1~64：" + conversationId);
        }
    }
}
