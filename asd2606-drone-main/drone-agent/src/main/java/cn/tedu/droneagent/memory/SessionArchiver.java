package cn.tedu.droneagent.memory;

/**
 * 会话离开内存时的归档出口。
 *
 * <p>实现方负责把会话写进长期记忆。短期存储只负责判断「该归档了、原因是什么」，
 * 不关心写到哪里去——这样内存这一层可以在没有文件存储的情况下完整测试。
 *
 * <h2>它在链路里的位置，以及它不负责什么</h2>
 * <p><b>位置</b>：**短期记忆流向长期记忆的出口**。{@link SessionStore} 在会话离开内存时
 * 调它；真正的实现是 {@link MemoryArchiver}。
 *
 * <ul>
 *   <li><b>不决定什么时候归档</b>：时机由缓存那边的淘汰规则决定，本接口只是被调。</li>
 *   <li><b>不管重试</b>：接口上只有「归档」一个动作；失败了怎么办由实现自己决定
 *       （当前实现是有界重试队列）。</li>
 * </ul>
 */
@FunctionalInterface
public interface SessionArchiver {

    /**
     * @param session 被移出内存的会话（已包含全部消息）
     * @param reason  移出原因；只会是 {@link SessionEvictionReason} 中的值，即都需要落盘
     */
    void archive(Session session, SessionEvictionReason reason);
}
