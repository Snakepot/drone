package cn.tedu.drone.task.mqtt;

/**
 * 设备消息的<b>处理口</b>：MQTT 这一层只管把消息收进来，怎么处理由实现类决定。
 *
 * <h2>为什么要多这一层接口</h2>
 * 把「收」和「处理」分开，换来三件具体的好处：
 * <ol>
 *   <li><b>可测</b>：测试里直接 new 一个实现类、塞一条 {@link DeviceMessage} 进去，
 *       验证证据有没有落库 —— 完全不需要真的起一个 broker。MQTT 的端到端测试
 *       只留一个（证明连得上、订得到），业务规则用快得多的单元测试覆盖。</li>
 *   <li><b>不互相拖累</b>：将来加了第二个数据源（比如厂商云 API 拉数据），
 *       它同样造 {@link DeviceMessage} 交给同一个实现，
 *       处理逻辑一行都不用改。</li>
 *   <li><b>依赖方向干净</b>：mqtt 包不 import service 包，
 *       处理逻辑反过来依赖这个接口。传输层不认识业务层，业务层认识传输层的口。</li>
 * </ol>
 *
 * <h2>实现者必须遵守的约定</h2>
 * <ul>
 *   <li><b>不许抛异常</b>：调用方是消费线程，一条坏数据不能把线程打死。
 *       处理不了就记日志返回（消费线程外面还有一层兜底，但那是保险，不是借口）。</li>
 *   <li><b>必须幂等</b>：同一条消息被投递两次是常态（QoS 1 保证「至少一次」），
 *       第二次执行不能产生额外效果。</li>
 * </ul>
 */
public interface TelemetryHandler {

    /**
     * 处理一条设备消息。
     *
     * @param message 已经解析并规范化过的消息，不会为 null
     */
    void handle(DeviceMessage message);
}
