package cn.tedu.droneagent.trace;

import java.util.Map;

/**
 * 一条链路 span（`REQ-AGENT-0026` P1）。
 *
 * <p>刻意贴近 OpenTelemetry 的最小字段：谁（service）、做了什么（name）、
 * 从属哪条链路（traceId/parentId）、多久（durationMs）、成没成（status）。
 * 不存 payload 原文，只存长度等元数据，避免把 PII 写进追踪文件。
 *
 * @param traceId    一次对话的追踪 id
 * @param spanId     本条 span 的 id
 * @param parentId   父 span；根 span 为 null
 * @param service    产生该 span 的服务名
 * @param name       span 名称，如 {@code chat.request} / {@code model.call} / {@code tool.call}
 * @param start      ISO-8601 开始时间
 * @param durationMs 耗时（毫秒）
 * @param status     ok / error
 * @param attrs      附加信息（工具名、轮次、长度等）
 */
public record TraceSpan(
        String traceId,
        String spanId,
        String parentId,
        String service,
        String name,
        String start,
        long durationMs,
        String status,
        Map<String, Object> attrs
) {

    public TraceSpan {
        attrs = attrs == null ? Map.of() : Map.copyOf(attrs);
    }
}
