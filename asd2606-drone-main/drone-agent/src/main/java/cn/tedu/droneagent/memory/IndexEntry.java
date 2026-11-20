package cn.tedu.droneagent.memory;

import java.time.Instant;

/**
 * 长期记忆索引里的一条记录：**一个会话一行**。
 *
 * <h2>为什么要单独有个索引</h2>
 * 会话正文按日期分片存放，若要靠正文回答「我有哪些会话、最近聊了什么」，
 * 就得把所有正文文件都读一遍。索引把这件事变成「只读一个小文件」：
 * 会话列表、按会话定位所在文件，全都靠它。
 *
 * @param conversationId 会话标识
 * @param startedAt      会话开始时间。<b>索引里只精确到分钟</b>，秒会被丢掉
 * @param messageCount   该会话落盘时的消息条数
 * @param title          首句截断，仅供人扫一眼；写入时会被清洗与截断，读出来可能短于原文
 * @param fileName       正文所在文件名（只记文件名，不记路径——用户目录由 userId 决定）
 *
 * <h2>它在链路里的位置，以及它不负责什么</h2>
 * <p><b>位置</b>：索引里**一行**的内存表示 —— {@link MemoryIndex} 写它、也读回它，
 * 会话列表则直接把它转成对外的响应。</p>
 *
 * <ul>
 *   <li><b>不读写文件</b>：它只是数据，落成一行、解析回对象都是 {@link MemoryIndex} 的事。</li>
 *   <li><b>不做清洗与截断</b>：标题怎么清洗、时间精确到哪一位，在写入方决定；
 *       本记录只保证拿到的东西是完整的。</li>
 *   <li><b>不校验归属</b>：它根本不含 userId —— 归属是靠「每个用户一个索引文件」解决的。</li>
 * </ul>
 */
public record IndexEntry(
        String conversationId,
        Instant startedAt,
        int messageCount,
        String title,
        String fileName
) {

    public IndexEntry {
        if (conversationId == null || conversationId.isBlank()) {
            throw new IllegalArgumentException("conversationId 不能为空");
        }
        if (startedAt == null) {
            throw new IllegalArgumentException("startedAt 不能为空");
        }
        if (fileName == null || fileName.isBlank()) {
            throw new IllegalArgumentException("fileName 不能为空");
        }
    }
}
