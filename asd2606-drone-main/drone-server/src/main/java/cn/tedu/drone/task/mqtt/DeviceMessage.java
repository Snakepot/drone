package cn.tedu.drone.task.mqtt;

import java.time.LocalDateTime;

/**
 * 一条「已经过解析、可以交给业务处理」的设备消息。
 *
 * <h2>为什么用 record 而不是 Lombok 的 @Data</h2>
 * 这个对象是<b>不可变</b>的：一旦从 MQTT 回调里造出来，就只读地往队列里传，
 * 谁都不该改它。record 一行就能表达「字段都 final、构造即赋值」，
 * 而且天然给了 {@code equals/hashCode/toString}（排查问题时打印出来很好看）。
 *
 * <p>项目里大多数 PO/VO 用 {@code @Data} 是因为它们要 MyBatis 或 Jackson
 * 反向填充，必须有无参构造和 setter；这个类两者都不需要，所以用 record 更合适 ——
 * <b>选择工具按用途来，不是全项目一刀切</b>。
 *
 * @param topic      消息实际来自哪个 Topic（含具体设备编号，便于排查）
 * @param deviceCode 最终认定的设备编号：Topic 里的那个（Topic 经过 ACL 授权，更可信）
 * @param receivedAt 我们收到它的时刻，落库时作为 reported_at
 * @param envelope   解析后的报文信封，用来取「要建索引的那几个字段」
 * @param raw        报文原文（UTF-8 解码后的 JSON 文本）
 */
public record DeviceMessage(String topic,
                            String deviceCode,
                            LocalDateTime receivedAt,
                            TelemetryEnvelope envelope,
                            String raw) {
}

/*
 * 关于上面的 raw 字段，这里多解释一句它为什么必须存在：
 *
 * TelemetryEnvelope 是「有损」的 —— 它只声明了我们关心的那几个字段，
 * Jackson 反序列化时会把报文里其它字段直接丢掉。如果落库时拿信封再序列化一遍
 * 存进 task_evidence.payload，那么存的其实是二手信息：
 * 原始报文里多出来的字段（设备固件版本、原始传感器读数……）就永久消失了。
 *
 * 而证据这种东西，将来最常见的追问恰恰是「当时设备到底发了什么」。
 * 所以规矩是：要建索引的字段解析出来，其余原样归档 ——
 * 解析只用来决定「这条数据归谁、算不算重复」，不用来决定「存什么」。
 */
