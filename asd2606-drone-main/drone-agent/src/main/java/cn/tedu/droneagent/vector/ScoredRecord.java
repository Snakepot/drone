package cn.tedu.droneagent.vector;

import java.util.Map;

/**
 * 检索命中的一条：记录 + 相似度得分。
 *
 * <p>得分是**余弦相似度**（越大越像，取值约 {@code [-1, 1]}）。调用方据此排序、卡阈值
 * （{@code agent.kb.min-score}），也据 {@code metadata} 组装引用出处。
 *
 * @param id       命中的记录 id
 * @param score    相似度（余弦）
 * @param metadata 记录携带的标量字段
 */
public record ScoredRecord(String id, double score, Map<String, Object> metadata) {
}
