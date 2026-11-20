package cn.tedu.drone.task.mqtt;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

/**
 * 设备上报报文的信封（envelope）。
 *
 * <h2>什么叫「信封」</h2>
 * 把「这条消息是关于什么的」和「内容本身」分开：
 * 外面这层固定字段用来<b>路由、去重、排序</b>，
 * 里面 {@code payload} 才是设备自己的业务数据（经纬度、电量、航点号……）。
 *
 * <p>好处是后端不用懂每一种设备的每一种报文：信封是统一的，
 * 我们照着信封做校验与落库，{@code payload} 原样存下来。
 * 将来要解析某个字段，再单独写解析，不影响接入这条主链路。
 *
 * <h2>报文长这样</h2>
 * <pre>{@code
 * {
 *   "deviceCode": "UAV-001",          // 可省略：省略时以 Topic 里的编号为准
 *   "type": "position",               // 消息类型，和 Topic 分类配合使用
 *   "observedAt": "2026-09-18 10:31:02",   // 设备说这件事发生的时刻
 *   "seq": 10231,                     // 设备内的自增序号，去重键的一部分
 *   "payload": { "lng": 116.39, "lat": 39.90, "alt": 120.5 }
 * }
 * }</pre>
 *
 * <h2>为什么 observedAt 用 String 而不是 LocalDateTime</h2>
 * 因为设备的时间格式我们说了不算。现实里至少有这几种：
 * {@code 2026-09-18 10:31:02}、{@code 2026-09-18T10:31:02+08:00}、
 * 甚至毫秒时间戳 {@code 1758162662000}。
 * 直接声明成 {@code LocalDateTime}，Jackson 遇到不认识的格式会直接抛异常，
 * <b>整条消息报废</b>；声明成 String 则先把原文接住，
 * 再由我们自己的解析器宽容地试几种格式 —— 认不出来才丢弃，
 * 而且丢弃时日志里能看到原文，方便和设备厂商对账。
 *
 * <h2>为什么 seq 参与去重，且不能省</h2>
 * QoS 1 是「至少一次」，意味着<b>同一条消息可能被投递两次</b>（设备重传、broker 重发）。
 * 去重只能靠「设备编号 + 类型 + 发生时刻 + 序号」四个一起 ——
 * 只用前三个的话，同一秒内的两条真实数据会被误判成重复丢掉。
 * 所以序号在我们这里不是可选装饰，是必需的。
 */
@Data
public class TelemetryEnvelope {

    /** 设备编号。可省略 —— 省略或与 Topic 里的编号打架时，以 Topic 为准（理由见 TelemetrySubscriber） */
    private String deviceCode;

    /** 消息类型，如 position / battery / event / status */
    private String type;

    /** 设备侧的发生时刻，原文接住，后面宽容解析 */
    private String observedAt;

    /** 设备内自增序号，断开重连后继续递增；参与去重键 */
    private Long seq;

    /**
     * 业务负载，原样保存。
     *
     * <p>类型是 {@link JsonNode}（Jackson 的树模型）而不是 String：
     * 如果声明成 String，那么 {@code "payload": { ... }} 这种<b>对象</b>会反序列化失败
     * （Jackson 不会自动把对象转成字符串）；声明成 JsonNode 则对象、数组、
     * 数字、字符串都能接住，取文本时调 {@link #payloadText()} 即可。
     */
    private JsonNode payload;

    /**
     * 把负载转成可以落库的 JSON 文本。
     *
     * <p>为什么不用 {@code payload.asText()}：那个方法对「对象」类型会返回空串，
     * 我们要的是完整的 JSON 字面量，所以用 {@code toString()}。
     */
    public String payloadText() {
        return payload == null ? null : payload.toString();
    }
}
