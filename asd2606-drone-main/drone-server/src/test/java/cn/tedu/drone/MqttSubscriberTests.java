package cn.tedu.drone;

import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * MQTT 端到端：真的往 broker 发一条报文，验证它经订阅器、队列、消费线程落进证据表。
 *
 * <h2>这条用例唯一的职责：证明「传输链路通」</h2>
 * 业务规则（校验、去重、归属、依据升级）在 {@code DroneServerApplicationTests}
 * 里用直调处理器的方式覆盖了，又快又稳。那些测试<b>故意绕开了 MQTT</b>，
 * 因为它们关心的是「拿到消息之后怎么处理」，和消息怎么来的无关。
 *
 * <p>反过来说，那些测试也证明不了这些事 —— 它们只能由本类来验：
 * <ul>
 *   <li>订阅器真的连得上 broker、真的订到了配置里那三个 Topic</li>
 *   <li>Topic 的结构真的被解析出了设备编号（{@code drone/{编号}/telemetry} 的第 2 层）</li>
 *   <li>回调真的把消息交给了队列，消费线程真的把它写进了库</li>
 * </ul>
 * 换句话说：那一组测的是「处理对不对」，这一条测的是「管子通不通」。
 *
 * <h2>broker 不在就跳过，而不是失败</h2>
 * 端到端依赖一个外部进程（本地 EMQX）。CI 机器上没有它、同事第一次拉代码也没起它 ——
 * 这时候报「测试失败」是在冤枉代码。所以用 {@code assumeTrue} 检查端口，
 * 连不上就<b>跳过</b>并在报告里写明原因：不通过，但也不误导。
 * 真实场景里这条用例失败时你该先看 broker，而不是先看代码。
 *
 * <h2>没有事务回滚，所以自己收尾</h2>
 * 这条链路要真的提交到库里才看得到（消费线程跑在另一个线程、另一个事务里），
 * 所以不能用 {@code @Transactional}。代价是所有数据都会真落库，
 * 于是沿用 {@code TaskWebSocketTests} 的做法：造的数据统一带前缀，用完按前缀删干净。
 */
@SpringBootTest(properties = {
        // 定时任务照例关掉：它跑在独立线程独立事务里，不受测试控制
        "drone.task.auto-release.enabled=false",
        // 这个类要验的正是 MQTT，所以打开它（其它测试类都是关的）
        "drone.mqtt.enabled=true",
        "drone.mqtt.broker-url=tcp://localhost:1883",
        // 把「证据过期」阈值设得极大：扫描任务在本类里是活着的，
        // 用默认的 120 秒会把开发库里所有「执行中 + 仅声明」的任务标成过期 ——
        // 那是一堆和本次测试无关的副作用，不该由测试制造
        "drone.mqtt.evidence-stale-seconds=86400"
})
class MqttSubscriberTests {

    /** broker 的地址与端口。EMQX 默认明文端口就是 1883 */
    private static final String BROKER_URL = "tcp://localhost:1883";
    private static final int BROKER_PORT = 1883;

    /** 造出来的数据统一前缀，收尾时按它删 */
    private static final String DEVICE_PREFIX = "DRN-94";

    /** 与 application.yml 里的时间格式保持一致，报文头用的就是它 */
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 等结果的轮询上限：MQTT 是异步的，收报文 → 入队 → 写库都需要时间 */
    private static final long WAIT_MILLIS = 10_000;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * 没起 broker 就整类跳过。
     *
     * <p>用「能不能建立 TCP 连接」判断，是最直白也最不容易误报的方式 ——
     * 不依赖 EMQX 的 HTTP 接口、也不需要凭据。
     */
    @BeforeAll
    static void requireBroker() {
        assumeTrue(canConnect(BROKER_PORT), "本地 " + BROKER_URL + " 上没有 MQTT broker，跳过 MQTT 端到端用例");
    }

    @AfterEach
    void cleanup() {
        // 先删从属数据再删设备：这两张表虽然没建外键，
        // 但按「谁依赖谁」的顺序清理更符合直觉，也避免将来真加了外键时这条语句突然失效
        jdbcTemplate.update("DELETE FROM task_alert WHERE device_code LIKE ?", DEVICE_PREFIX + "%");
        jdbcTemplate.update("DELETE FROM task_evidence WHERE device_code LIKE ?", DEVICE_PREFIX + "%");
        jdbcTemplate.update("DELETE FROM `drone` WHERE code LIKE ?", DEVICE_PREFIX + "%");
    }

    /**
     * 报文发到 broker 之后，应当自动出现在证据表里；且设备编号以 Topic 为准。
     *
     * <p>报文正文里刻意填了<b>另一台</b>已登记设备的编号：MQTT 的 ACL 是按 Topic 授权的，
     * Topic 里那一段是可信的，正文里的字段谁都能改。所以落库的必须是 Topic 里的编号 ——
     * 否则等于「谁能发数据谁就能把证据栽到别的设备头上」。
     */
    @Test
    void telemetryPublishedToBrokerShouldLandInEvidenceTable() throws Exception {
        String topicDeviceCode = DEVICE_PREFIX + "01";
        String bodyDeviceCode = DEVICE_PREFIX + "02";
        registerDevice(topicDeviceCode);
        registerDevice(bodyDeviceCode);

        String topic = "drone/" + topicDeviceCode + "/telemetry";

        // 热身：连接与订阅都是异步的，测试方法开始时订阅可能刚好还没生效。
        // 反复发一条（每次 seq 都不同，不会撞去重键）直到落库，
        // 确认链路真的通了 —— 这样后面的正式断言就不会因为「发早了」偶发失败
        awaitSubscriptionReady(topic, topicDeviceCode);

        // 正式这一次：Topic 一台设备、正文另一台设备
        long seq = System.currentTimeMillis();
        String observedAt = LocalDateTime.now().withNano(0).format(TIME_FORMAT);
        String raw = telemetryJson(bodyDeviceCode, seq, observedAt);
        publish(topic, raw, 0);

        String landedAt = awaitDeviceCodeOf(seq, WAIT_MILLIS);
        assertThat(landedAt)
                .as("MQTT 报文应在 %d ms 内落库；Topic=%s", WAIT_MILLIS, topic)
                .isEqualTo(topicDeviceCode);

        // 归档的必须是设备发出来的原文，而不是我们解析后的子集
        assertThat(jdbcTemplate.queryForObject(
                "SELECT payload FROM task_evidence WHERE seq = ?", String.class, seq)).isEqualTo(raw);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT DATE_FORMAT(observed_at, '%Y-%m-%d %H:%i:%s') FROM task_evidence WHERE seq = ?",
                String.class, seq)).isEqualTo(observedAt);
    }

    /**
     * 电量告警报文发到 broker 之后，除了证据，还应落一条告警（实施步骤第一步的端到端验证）。
     *
     * <p>这条用例补的是 {@code DroneServerApplicationTests} 里那批告警用例的一个空白：
     * 那些用例是把消息直接交给处理口（跳过 MQTT），验的是<b>识别逻辑</b>；
     * 这条走真实 broker，验的是<b>从报文到告警落库这一整条链路真的通</b>。
     * 两者缺一不可 —— 识别逻辑对、但订阅没订上，用户看到的就是「什么都没发生」。
     */
    @Test
    void lowBatteryTelemetryShouldRaiseAlertOnRealBroker() throws Exception {
        String deviceCode = DEVICE_PREFIX + "11";
        registerDevice(deviceCode);
        String topic = "drone/" + deviceCode + "/telemetry";
        awaitSubscriptionReady(topic, deviceCode);

        long seq = System.currentTimeMillis();
        String observedAt = LocalDateTime.now().withNano(0).format(TIME_FORMAT);
        // 电量 10 会触发 bat.critical；顺带带上 motorState=normal，确认它不会被误判成电机异常
        String raw = """
                {"deviceCode":"%s","type":"position","observedAt":"%s","seq":%d,
                 "payload":{"battery":10,"motorState":"normal"}}""".formatted(deviceCode, observedAt, seq);
        publish(topic, raw, 0);

        Map<String, Object> alert = awaitAlertOf(deviceCode, WAIT_MILLIS);
        assertThat(alert).as("告警应在 %d ms 内落库；device=%s", WAIT_MILLIS, deviceCode).isNotNull();
        assertThat(alert.get("alert_code")).isEqualTo("bat.critical");
        assertThat(alert.get("severity")).isEqualTo("critical");
        assertThat(alert.get("status")).isEqualTo("pending");
        // pilot_action_required 不能省：配置里给 bat.critical 配了动作提示，飞手端要靠它显示待办
        assertThat(((Number) alert.get("pilot_flag")).longValue()).isEqualTo(1L);
        assertThat(String.valueOf(alert.get("action_hint"))).isNotBlank();
        // 同一条报文两个维度都要留：证据 + 告警。
        // 证据按 seq 精确定位，不按设备计数 —— 热身阶段也往这个 topic 发过报文，
        // 按设备数会把热身那条一起数进来（这条断言一开始就是这么写错的）
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM task_evidence WHERE device_code = ? AND seq = ?",
                Long.class, deviceCode, seq))
                .as("证据也要落库（seq=%d）", seq).isEqualTo(1L);
    }

    // ------------------------------------------------------------------
    // 辅助方法
    // ------------------------------------------------------------------

    /**
     * 轮询等某台设备的告警出现，返回它的关键字段；超时返回 null。
     *
     * <p>用「设备 + 最先出现的那条」而不是按 seq 定位：告警表**没有 seq** ——
     * 它的去重键是 (device_code, alert_code, observed_at)，因为告警是「状态判定」
     * 而不是「采样」（见 docs/告警体系设计.md 4.3）。
     */
    private Map<String, Object> awaitAlertOf(String deviceCode, long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    // pilot_action_required 是 tinyint(1)，驱动默认会映射成 Boolean，
                    // 加 0 让它以数字形态返回，断言里不用去猜类型
                    "SELECT alert_code, severity, status, action_hint, "
                            + "pilot_action_required + 0 AS pilot_flag FROM task_alert "
                            + "WHERE device_code = ? ORDER BY id", deviceCode);
            if (!rows.isEmpty()) {
                return rows.get(0);
            }
            Thread.sleep(200);
        }
        return null;
    }

    /**
     * 反复发一条报文，直到它落库为止，用来确认「订阅已经生效」。
     *
     * <p>为什么需要它：MQTT 的订户在连上之后才收到消息，而连接是异步的。
     * 如果测试一上来就发正式的那条，恰好在订阅生效前发出，那条消息就<b>永久丢失</b>
     * （cleanSession=true，broker 不会为离线订户留存），测试会莫名其妙地红一次。
     * 热身把这种不确定性一次性地消化掉。
     */
    private void awaitSubscriptionReady(String warmupTopic, String deviceCode) throws Exception {
        long deadline = System.currentTimeMillis() + WAIT_MILLIS;
        long warmupSeq = System.currentTimeMillis() * 1000;

        while (System.currentTimeMillis() < deadline) {
            publish(warmupTopic, telemetryJson(deviceCode, warmupSeq, "2027-01-01 00:00:00"), 0);
            // 单次只等 1 秒：等不到就再发一条。这样「订阅在第 3 秒才生效」
            // 这种情况也能在一次调用里自愈，而不是白等 10 秒然后失败
            if (awaitDeviceCodeOf(warmupSeq, 1000) != null) {
                return;
            }
            warmupSeq++;
        }
        // 走到这里说明 10 秒都没通 —— 抛出说得清的错，
        // 而不是让正式断言去报一个含义模糊的「值不相等」
        throw new IllegalStateException("等待 MQTT 订阅生效超时（" + WAIT_MILLIS + " ms），请检查 broker 是否正常、"
                + "以及 drone.mqtt.topics 的配置是否覆盖了 " + warmupTopic);
    }

    /** 轮询等待某条 seq 的证据出现，返回它的设备编号；超时返回 null */
    private String awaitDeviceCodeOf(long seq, long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            List<String> codes = jdbcTemplate.queryForList(
                    "SELECT device_code FROM task_evidence WHERE seq = ?", String.class, seq);
            if (!codes.isEmpty()) {
                return codes.get(0);
            }
            Thread.sleep(200);
        }
        return null;
    }

    /**
     * 直接用 SQL 登记一台设备。
     *
     * <p>不走 {@code /device/add}：这条用例要验的是 MQTT 链路，
     * 设备档案只是它的前置条件。为此去装 MockMvc、登录拿 token 属于绕远路，
     * 而且会把「设备接口有问题」和「MQTT 链路有问题」这两件事混在一条用例里。
     */
    private void registerDevice(String code) {
        jdbcTemplate.update(
                "INSERT INTO `drone` (code, name, type, status) VALUES (?, ?, 'multi-rotor', 'idle')",
                code, "MQTT 端到端设备");
    }

    /** 发一条报文。用同步客户端：测试里消息就一条，没必要搞异步 */
    private void publish(String topic, String payload, int qos) throws Exception {
        // clientId 带随机后缀：同一个 broker 上重复的 clientId 会互相踢下线，
        // 这条规则对测试客户端同样成立（两个测试并行跑时就会撞上）
        String clientId = "drone-e2e-publisher-" + Long.toHexString(System.nanoTime());
        MqttClient publisher = new MqttClient(BROKER_URL, clientId, new MemoryPersistence());
        try {
            MqttConnectOptions options = new MqttConnectOptions();
            options.setCleanSession(true);
            options.setConnectionTimeout(5);
            publisher.connect(options);

            MqttMessage message = new MqttMessage(payload.getBytes(StandardCharsets.UTF_8));
            message.setQos(qos);
            publisher.publish(topic, message);
        } finally {
            // 一定要断开：留着连接会占 broker 的资源，也可能让后续运行拿到脏会话
            if (publisher.isConnected()) {
                publisher.disconnect();
            }
            publisher.close();
        }
    }

    private String telemetryJson(String deviceCode, long seq, String observedAt) {
        return """
                {"deviceCode":"%s","type":"position","observedAt":"%s","seq":%d,
                 "payload":{"lng":116.397,"lat":39.908,"alt":120.5}}"""
                .formatted(deviceCode, observedAt, seq);
    }

    /** 用一次 TCP 连接探活：最直白的「端口上有没有人」判断，不需要任何凭据 */
    private static boolean canConnect(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("localhost", port), 1000);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
