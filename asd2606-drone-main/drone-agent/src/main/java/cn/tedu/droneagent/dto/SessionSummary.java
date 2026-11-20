package cn.tedu.droneagent.dto;

import java.time.Instant;

/**
 * 会话列表里的一项。
 *
 * <h2>为什么没有「最后活跃时间」</h2>
 * 方案第 3 节的接口清单里列了这一字段，但落地时发现**索引里没有它**：
 * 索引记录的是会话开始时间，长期记忆里也不存最后活跃时间。
 * 要加就得改索引的行格式，而格式一改，已有的索引文件会解析失败——
 * 那等于把历史会话从列表里抹掉。所以本期**去掉该字段**，列表按归档顺序排列。
 * 需要它时应作为独立改动，连带做一次索引格式迁移。
 *
 * @param conversationId 会话标识
 * @param title          首句截断，仅供列表展示；已落盘的会话里这一句是脱敏后的
 * @param startedAt      会话开始时间（索引只精确到分钟）
 * @param messageCount   消息条数
 */
public record SessionSummary(String conversationId, String title, Instant startedAt, int messageCount) {
}
