package cn.tedu.droneagent.memory;

/**
 * 会话的**懒加载**入口：内存里没有该会话时，从长期记忆把它取回来。
 *
 * <h2>为什么要单独抽一个接口</h2>
 * 短期存储（内存）与长期存储（文件）是两件事，实现在不同的类里。
 * 内存这边只认这个接口，就能在不依赖文件实现的前提下先跑起来、先写完测试；
 * 文件那边将来实现它即可接上。若无此接口，两者会互相卡住无法单独推进。
 *
 * <h2>它在链路里的位置，以及它不负责什么</h2>
 * <p><b>位置</b>：**长期记忆流回短期记忆的入口**。{@link SessionStore} 缓存未命中时调它，
 * 实现见 {@link LongTermSessionLoader}。
 *
 * <ul>
 *   <li><b>不决定什么时候加载</b>：只在缓存没命中时被调，时机在 {@link SessionStore} 那边。</li>
 *   <li><b>不管缓存</b>：取回来的会话放不放进内存、放多久，都不是它的事。</li>
 * </ul>
 */
@FunctionalInterface
public interface SessionLoader {

    /**
     * @return 该会话；长期记忆里没有则返回 {@code null}（调用方会建一个空会话）
     */
    Session load(SessionKey key);
}
