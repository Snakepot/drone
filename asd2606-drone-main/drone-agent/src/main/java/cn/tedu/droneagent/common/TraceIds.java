package cn.tedu.droneagent.common;

import java.security.SecureRandom;

/**
 * 短追踪 id 的生成。
 *
 * <h2>为什么不用 UUID</h2>
 * 它只用来把「一次 `/chat` 的各层日志」串起来，**不需要全局唯一**，只要「并发时不易撞」即可。
 * 8 个十六进制字符足够，比 36 位的 UUID 好读、好比对。
 *
 * <h2>它在链路里的位置，以及它不负责什么</h2>
 * <ul>
 *   <li><b>只是给日志加一个相关键</b>：由 {@code ConversationService.ask} 每次请求生成一个，
 *       再**显式传参**往下带（不靠 ThreadLocal —— 本项目已踩过「跨线程取不到」的坑）。</li>
 *   <li><b>不做完整链路追踪系统</b>：不建 span 树、不上报；但会通过
 *       {@code X-Trace-Id} 跨服务传播（`REQ-AGENT-0026`）。</li>
 * </ul>
 */
public final class TraceIds {

    private static final SecureRandom RANDOM = new SecureRandom();

    private TraceIds() {
    }

    /** 生成一个新的追踪 id（8 个十六进制字符） */
    public static String newId() {
        return String.format("%08x", RANDOM.nextInt());
    }
}
