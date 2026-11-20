package cn.tedu.drone;

import cn.tedu.drone.common.JwtUtil;
import cn.tedu.drone.task.mqtt.DeviceMessage;
import cn.tedu.drone.task.mqtt.TelemetryEnvelope;
import cn.tedu.drone.task.mqtt.TelemetryHandler;
import cn.tedu.drone.task.pojo.AlertPO;
import cn.tedu.drone.task.pojo.AlertSeverity;
import cn.tedu.drone.task.ws.AlertNotifier;
import cn.tedu.drone.task.ws.TaskNotifier;
import cn.tedu.drone.task.ws.TaskWsSessionRegistry;
import cn.tedu.drone.user.pojo.UserPO;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * WebSocket 推送的集成测试。
 *
 * <h2>为什么单独一个测试类，不放进 {@code DroneServerApplicationTests}</h2>
 * 两个原因，都很硬：
 * <ol>
 *   <li><b>需要真实的网络连接</b>：MockMvc 是「假 HTTP」，它能测 Controller，
 *       但测不了 WebSocket —— 握手、连接、推送都是网络层的事。
 *       所以这里用 {@code webEnvironment = RANDOM_PORT} 起一个真服务，
 *       再用真实的 WebSocket 客户端连上去</li>
 *   <li><b>需要事务真的提交</b>：推送挂在「事务提交后」的回调上（见 TaskServiceImpl.notifyAfterCommit）。
 *       而另一个测试类是 {@code @Transactional} 的，跑完会回滚 —— 回滚就不算提交，
 *       推送回调根本不会触发，测了也是白测</li>
 * </ol>
 *
 * <h2>代价：数据会真的写进开发库，所以自己收尾</h2>
 * 没有事务回滚的保护，测试造的数据会真实落库。因此：所有造出来的数据都带统一前缀
 * （用户名 {@code 13900003xxx}、设备编号 {@code DRN-95xx}、任务名 {@code WS测试-*}），
 * 在 {@link #cleanup()} 里按前缀删干净。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                // 关掉超时释放的定时任务：它跑在独立线程/独立事务里，
                // 会在测试过程中直接改开发库（理由同 DroneServerApplicationTests）
                "drone.task.auto-release.enabled=false",
                // 同理关掉 MQTT 接入。「证据过期扫描」定时任务归这个开关管，
                // 不关的话它每 60 秒扫一遍、同样跑在独立线程独立事务里，
                // 会把这个类特意留在库里的数据改成 stale
                "drone.mqtt.enabled=false"
        })
class TaskWebSocketTests {

    private static final String ADMIN_USERNAME = "13800001005";
    private static final String PASSWORD = "123456";

    /**
     * 管理员刘洋的 id。用它连一条 WebSocket，验证「飞手操作后管理员能收到通知」。
     * 值是固定的（见 docs/sql/data.sql），写死比每次查一遍更直白。
     */
    private static final int ADMIN_USER_ID = 5;

    /** 造出来的数据的统一前缀，收尾时按它删 */
    private static final String PILOT_PREFIX = "13900003";
    private static final String DRONE_PREFIX = "DRN-95";
    private static final String TASK_PREFIX = "WS测试";

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TaskWsSessionRegistry registry;

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * 直接喂设备报文（跳过 MQTT 传输层）来造告警 ——
     * 「怎么收到的」已经由 MqttSubscriberTests 验过，这里要验的是「报警之后谁被通知了」
     */
    @Autowired
    private TelemetryHandler telemetryHandler;

    @Autowired
    private AlertNotifier alertNotifier;

    /** 测试过程中建立的连接，收尾时统一关掉 */
    private final List<WebSocketSession> openSessions = new ArrayList<>();

    @AfterEach
    void cleanup() throws Exception {
        for (WebSocketSession session : openSessions) {
            if (session.isOpen()) {
                session.close(CloseStatus.NORMAL);
            }
        }
        openSessions.clear();

        // 按前缀清理：先删子表再删主表（表之间没有外键，但保持这个习惯）
        jdbcTemplate.update("DELETE FROM task_log WHERE task_id IN (SELECT id FROM `task` WHERE task_name LIKE ?)", TASK_PREFIX + "%");
        jdbcTemplate.update("DELETE FROM `task` WHERE task_name LIKE ?", TASK_PREFIX + "%");
        jdbcTemplate.update("DELETE FROM user_role WHERE user_id IN (SELECT id FROM `user` WHERE username LIKE ?)", PILOT_PREFIX + "%");
        jdbcTemplate.update("DELETE FROM `user` WHERE username LIKE ?", PILOT_PREFIX + "%");
        // 告警与证据要先于设备删：这两张表按 device_code 关联（不是外键，但顺序上先清从属数据）。
        // 「证据」这一行是补上来的 —— 告警推送的用例会喂设备报文，
        // 报文经过处理口会落证据，原来只清了告警和设备，于是每跑一次测试就漏几行证据在库里
        jdbcTemplate.update("DELETE FROM task_alert WHERE device_code LIKE ?", DRONE_PREFIX + "%");
        jdbcTemplate.update("DELETE FROM task_evidence WHERE device_code LIKE ?", DRONE_PREFIX + "%");
        jdbcTemplate.update("DELETE FROM `drone` WHERE code LIKE ?", DRONE_PREFIX + "%");
    }

    // ------------------------------------------------------------------
    // 告警推送（实施步骤第三步）
    // ------------------------------------------------------------------

    /**
     * 设备报警时，「能处理告警的人」应当立刻收到推送。
     *
     * <p>这条用例验的正是设计文档第八章那句话：**通知的目的只是要尽快告诉能处理告警的人**。
     * 在此之前，设备电量掉到 10%、库里躺着一条 `critical`，但没有任何人会被叫醒。
     *
     * <p>顺带验了限频：同一个问题<b>重发一次不会再推</b> ——
     * 限频不是靠定时器算的，而是靠落库那条 `WHERE NOT EXISTS`
     * （同一问题还有未处理的告警时根本不产生新行，也就走不到推送）。
     */
    @Test
    void alertShouldPushToHandlersOncePerProblem() throws Exception {
        BlockingQueue<String> adminInbox = new LinkedBlockingQueue<>();
        connect(ADMIN_USER_ID, adminInbox);

        String token = adminToken();
        String deviceCode = DRONE_PREFIX + "AL1";
        createDrone(deviceCode, "告警推送机");

        // 电量 10 → 触发 bat.critical（critical 级，必须推）
        raiseAlert(deviceCode, 9001L, "2027-09-09 10:00:00", """
                {"battery":10}""");

        // 不能直接 poll 第一条：服务端连上时会先发一条 ready（见前端 useTaskSocket 的处理），
        // 所以按「类型」去等，把不相关的先丢掉
        @SuppressWarnings("unchecked")
        Map<String, Object> message = (Map<String, Object>) (Map<?, ?>)
                awaitMessageOfType(adminInbox, "alertRaised", 5);
        assertThat(message).as("管理员应在 5 秒内收到告警推送").isNotNull();
        assertThat(message.get("type")).isEqualTo("alertRaised");
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) message.get("data");
        assertThat(data.get("alertCode")).isEqualTo("bat.critical");
        assertThat(data.get("alertCodeLabel")).isEqualTo("电量严重不足");
        assertThat(data.get("severity")).isEqualTo("critical");
        assertThat(data.get("deviceCode")).isEqualTo(deviceCode);
        // 推送里只放「够用来提示」的最小信息，不放 payload 之类 ——
        // 前端收到后自己去告警页拿完整数据（那次调用要过登录与权限校验）
        assertThat(data).doesNotContainKey("payload");

        // 同一个问题再发一条：落库被抑制了，所以不该再推
        raiseAlert(deviceCode, 9002L, "2027-09-09 10:00:01", """
                {"battery":9}""");
        assertThat(adminInbox.poll(2, TimeUnit.SECONDS))
                .as("同一问题还有未处理的告警时，不该重复推送").isNull();

        // 顺带确认库里也确实只有一条（推送与落库的抑制是同一处判据）
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM task_alert WHERE device_code = ?", Long.class, deviceCode))
                .isEqualTo(1L);
    }

    /**
     * `info` 级告警**不推**。
     *
     * <p>设备上线、起飞这些每天几十上百条，推了只会让人对通知脱敏 ——
     * 等到真的 `critical` 来时，人已经习惯性忽略了。
     * <b>「不推」是为了让「推」还有意义。</b>
     *
     * <p>这条直接调 {@link AlertNotifier}：现在还没有哪种遥测会判出 `info`
     * （状态类与事件类告警是第四步），所以没法从报文那头造出来。
     */
    @Test
    void infoAlertShouldNotBePushed() {
        AlertPO info = new AlertPO()
                .setId(1L)
                .setAlertCode("evt.takeoff")
                .setSeverity(AlertSeverity.INFO)
                .setDeviceCode(DRONE_PREFIX + "AL2")
                .setObservedAt(LocalDateTime.now());

        assertThat(alertNotifier.notifyAlertRaised(info)).as("info 级不推").isZero();
    }

    /**
     * `critical` 告警除了推给能处理的人，**还要推给该任务的飞手**。
     *
     * <p>这是设计文档第六章矩阵里「管理侧 + 该任务的飞手」的另一半。
     * 理由就一句话：<b>飞手就在现场，是唯一能立刻采取行动的人</b>
     * （返航、降落、中止任务）。只推给坐在办公室的管理员，等于把最该知道的人漏掉了。
     *
     * <p>同时验了两条边界：
     * <ul>
     *   <li>推送里带上了 {@code taskId} 与 {@code actionHint} ——
     *       飞手端要靠它们提示「哪条任务、该做什么」</li>
     *   <li>没挂任务的告警不会乱推给某个飞手（那条由下面的分支用例覆盖）</li>
     * </ul>
     */
    @Test
    void criticalAlertShouldAlsoPushToTaskPilot() throws Exception {
        BlockingQueue<String> pilotInbox = new LinkedBlockingQueue<>();
        BlockingQueue<String> adminInbox = new LinkedBlockingQueue<>();

        String token = adminToken();
        String deviceCode = DRONE_PREFIX + "AP1";
        int pilotId = createPilot(PILOT_PREFIX + "011", "WS告警飞手");
        int droneId = createDrone(deviceCode, "告警推飞手机");
        int taskId = createTask(token, TASK_PREFIX + "告警推飞手",
                "2027-09-10 09:00:00", "2027-09-10 11:00:00");
        dispatch(token, taskId, pilotId, droneId);
        // 必须先「开始执行」：告警的 task_id 是处理链路第 ④ 步
        // 「找这台设备执行中的任务」查出来的，任务不在执行中就没有归属
        call(tokenFor(pilotId), HttpMethod.POST, "/task/start", """
                {"taskId":%d}""".formatted(taskId));

        connect(pilotId, pilotInbox);
        connect(ADMIN_USER_ID, adminInbox);

        raiseAlert(deviceCode, 9101L, "2027-09-10 09:30:00", """
                {"battery":10}""");

        // 管理侧照旧收到（权限点那一路没被改坏）
        assertThat(awaitMessageOfType(adminInbox, "alertRaised", 5))
                .as("管理侧应收到告警推送").isNotNull();

        @SuppressWarnings("unchecked")
        Map<String, Object> message = (Map<String, Object>) (Map<?, ?>)
                awaitMessageOfType(pilotInbox, "alertRaised", 5);
        assertThat(message).as("该任务的飞手应在 5 秒内收到 critical 告警").isNotNull();

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) message.get("data");
        assertThat(((Number) data.get("taskId")).intValue()).isEqualTo(taskId);
        assertThat(data.get("alertCode")).isEqualTo("bat.critical");
        // 飞手端要靠这句话告诉飞手「现在该干什么」
        assertThat(data.get("actionHint")).isEqualTo("立即返航并就近降落");
    }

    /**
     * 没挂任务的 `critical` 告警<b>不会</b>推给任何飞手 —— 只推管理侧。
     *
     * <p>设备自己开机自检报的电池高温就属于这种：它跟任何一次作业都没关系，
     * 硬要推给某个飞手就是无中生有。这时候「不推」才是对的，
     * 而且不是错误 —— 管理侧收到就够了。
     */
    @Test
    void criticalAlertWithoutTaskShouldNotPushToPilot() throws Exception {
        BlockingQueue<String> adminInbox = new LinkedBlockingQueue<>();
        connect(ADMIN_USER_ID, adminInbox);

        // 只登记设备、不建任务：告警照样产生（这是告警与证据共有的性质），
        // 但 task_id 为空，没有飞手可推
        String deviceCode = DRONE_PREFIX + "AP2";
        createDrone(deviceCode, "无任务告警机");
        raiseAlert(deviceCode, 9201L, "2027-09-11 09:00:00", """
                {"battery":10}""");

        @SuppressWarnings("unchecked")
        Map<String, Object> message = (Map<String, Object>) (Map<?, ?>)
                awaitMessageOfType(adminInbox, "alertRaised", 5);
        assertThat(message).as("管理侧照旧收到").isNotNull();
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) message.get("data");
        assertThat(data.get("taskId")).as("没有任务时 taskId 为空").isNull();
    }

    // ------------------------------------------------------------------
    // 握手鉴权
    // ------------------------------------------------------------------

    @Test
    void handshakeWithoutTokenShouldBeRejected() {
        String url = "ws://localhost:" + port + "/ws/task";

        // 没带 token：握手拦截器返回 401，客户端会直接连不上
        assertThatThrownBy(() -> new StandardWebSocketClient()
                .execute(new CollectingHandler(new LinkedBlockingQueue<>()), url)
                .get(5, TimeUnit.SECONDS))
                .as("没有 token 的握手必须失败")
                .isInstanceOf(Exception.class);
    }

    @Test
    void handshakeWithInvalidTokenShouldBeRejected() {
        String url = "ws://localhost:" + port + "/ws/task?token=not-a-real-token";

        assertThatThrownBy(() -> new StandardWebSocketClient()
                .execute(new CollectingHandler(new LinkedBlockingQueue<>()), url)
                .get(5, TimeUnit.SECONDS))
                .as("伪造 token 的握手必须失败")
                .isInstanceOf(Exception.class);
    }

    @Test
    void handshakeWithValidTokenShouldSucceedAndBeRegistered() throws Exception {
        int pilotId = createPilot("13900003001", "WS握手飞手");

        BlockingQueue<String> received = new LinkedBlockingQueue<>();
        connect(pilotId, received);

        // 连上之后服务端会先回一条 ready，客户端据此知道「真的连上了」
        assertThat(received.poll(5, TimeUnit.SECONDS)).contains("ready");
        assertThat(registry.countByUser((long) pilotId)).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // 推送送达
    // ------------------------------------------------------------------

    @Test
    void dispatchShouldPushNotificationToAssignedPilot() throws Exception {
        String adminToken = adminToken();
        int pilotId = createPilot("13900003002", "WS收信飞手");
        int droneId = createDrone("DRN-9501", "WS推送机");

        BlockingQueue<String> received = new LinkedBlockingQueue<>();
        connect(pilotId, received);
        received.poll(5, TimeUnit.SECONDS); // 先吃掉 ready

        int taskId = createTask(adminToken, "WS测试-派发通知", "2027-03-01 09:00:00", "2027-03-01 11:00:00");
        dispatch(adminToken, taskId, pilotId, droneId);

        // 关键断言：飞手在自己那条 WebSocket 上收到了通知
        String message = received.poll(5, TimeUnit.SECONDS);
        assertThat(message)
                .as("派发后飞手应收到推送")
                .isNotNull()
                .contains(TaskNotifier.TYPE_TASK_DISPATCHED)
                .contains("WS测试-派发通知")
                .contains("DRN-9501");

        // 顺便验一下「最小化」：报文里不该出现任务内容这类字段
        assertThat(message).doesNotContain("routeInfo").doesNotContain("createBy");
    }

    @Test
    void dispatchShouldNotPushToOtherPilots() throws Exception {
        String adminToken = adminToken();
        int pilotA = createPilot("13900003003", "WS被派飞手");
        int pilotB = createPilot("13900003004", "WS围观飞手");
        int droneId = createDrone("DRN-9502", "WS隔离机");

        BlockingQueue<String> receivedA = new LinkedBlockingQueue<>();
        BlockingQueue<String> receivedB = new LinkedBlockingQueue<>();
        connect(pilotA, receivedA);
        connect(pilotB, receivedB);
        receivedA.poll(5, TimeUnit.SECONDS); // 吃掉各自的 ready
        receivedB.poll(5, TimeUnit.SECONDS);

        int taskId = createTask(adminToken, "WS测试-只推给被派的人", "2027-03-02 09:00:00", "2027-03-02 11:00:00");
        dispatch(adminToken, taskId, pilotA, droneId);

        assertThat(receivedA.poll(5, TimeUnit.SECONDS)).as("被指派的飞手应收到").isNotNull();
        // 等 1 秒确认 B 那边确实没有 —— 推送是按用户定向的，不是广播
        assertThat(receivedB.poll(1, TimeUnit.SECONDS)).as("没被指派的飞手不该收到").isNull();
    }

    @Test
    void pingShouldGetPong() throws Exception {
        int pilotId = createPilot("13900003005", "WS心跳飞手");

        BlockingQueue<String> received = new LinkedBlockingQueue<>();
        WebSocketSession session = connect(pilotId, received);
        received.poll(5, TimeUnit.SECONDS); // ready

        session.sendMessage(new TextMessage("{\"type\":\"ping\"}"));

        assertThat(received.poll(5, TimeUnit.SECONDS)).contains("pong");
    }

    // ------------------------------------------------------------------
    // 通知管理员（飞手开始执行 / 拒绝执行）
    // ------------------------------------------------------------------

    /**
     * 拒绝执行要推给「能重新指派的人」，并且报文里带上是谁拒的、为什么拒不接。
     *
     * <p>这是所有通知里唯一需要管理员动手的一类，所以断言得细一点：
     * 类型要对、原因要在、状态要是退回后的「待派发」。
     */
    @Test
    void refuseShouldNotifyDispatchersWithReason() throws Exception {
        String adminToken = adminToken();
        int pilotId = createPilot("13900003006", "WS拒绝飞手");
        int droneId = createDrone("DRN-9503", "WS拒绝机");

        // 管理员（有 task:dispatch，所以是通知的接收方）与飞手各连一条
        BlockingQueue<String> adminReceived = new LinkedBlockingQueue<>();
        BlockingQueue<String> pilotReceived = new LinkedBlockingQueue<>();
        connect(ADMIN_USER_ID, adminReceived);
        connect(pilotId, pilotReceived);
        adminReceived.poll(5, TimeUnit.SECONDS);
        pilotReceived.poll(5, TimeUnit.SECONDS);

        int taskId = createTask(adminToken, "WS测试-拒绝通知", "2027-03-03 09:00:00", "2027-03-03 11:00:00");
        dispatch(adminToken, taskId, pilotId, droneId);
        adminReceived.poll(5, TimeUnit.SECONDS); // 派发那条与自己无关，这里其实收不到；保险起见清一下队列
        pilotReceived.poll(5, TimeUnit.SECONDS); // 飞手先收到「派给你了」

        // 飞手拒绝
        call(tokenFor(pilotId), HttpMethod.POST, "/task/refuse", """
                {"taskId":%d,"reason":"当天有别的安排"}
                """.formatted(taskId));

        String message = adminReceived.poll(5, TimeUnit.SECONDS);
        assertThat(message)
                .as("拒绝后管理员应收到通知")
                .isNotNull()
                .contains(TaskNotifier.TYPE_TASK_REFUSED)
                .contains("WS测试-拒绝通知")
                // 谁拒的、为什么拒不接 —— 管理员据此决定换人还是改时间
                .contains("WS拒绝飞手")
                .contains("当天有别的安排")
                // 任务这时已经退回待派发，管理员可以直接重新指派
                .contains("\"status\":\"pending\"");

        // 飞手不该收到「自己拒绝」的通知：这条是发给管理员的
        assertThat(pilotReceived.poll(1, TimeUnit.SECONDS)).isNull();
    }

    @Test
    void startShouldNotifyDispatchers() throws Exception {
        String adminToken = adminToken();
        int pilotId = createPilot("13900003007", "WS开始飞手");
        int droneId = createDrone("DRN-9504", "WS开始机");

        BlockingQueue<String> adminReceived = new LinkedBlockingQueue<>();
        connect(ADMIN_USER_ID, adminReceived);
        adminReceived.poll(5, TimeUnit.SECONDS);

        int taskId = createTask(adminToken, "WS测试-开始通知", "2027-03-04 09:00:00", "2027-03-04 11:00:00");
        dispatch(adminToken, taskId, pilotId, droneId);

        call(tokenFor(pilotId), HttpMethod.POST, "/task/start", """
                {"taskId":%d}
                """.formatted(taskId));

        String message = adminReceived.poll(5, TimeUnit.SECONDS);
        assertThat(message)
                .as("开始执行后管理员应收到通知")
                .isNotNull()
                .contains(TaskNotifier.TYPE_TASK_STARTED)
                .contains("WS测试-开始通知")
                .contains("\"status\":\"executing\"");
    }

    // ------------------------------------------------------------------
    // 测试用的 HTTP 小工具
    // ------------------------------------------------------------------

    /** 管理员登录拿 token */
    private String adminToken() {
        ResponseEntity<Map> response = rest.postForEntity("/user/login",
                Map.of("username", ADMIN_USERNAME, "password", PASSWORD), Map.class);
        Map<?, ?> data = (Map<?, ?>) response.getBody().get("data");
        return String.valueOf(data.get("token"));
    }

    private HttpEntity<String> jsonEntity(String token, String json) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        return new HttpEntity<>(json, headers);
    }

    private Map<?, ?> call(String token, HttpMethod method, String path, String json) {
        ResponseEntity<Map> response = rest.exchange(path, method, jsonEntity(token, json), Map.class);
        Map<?, ?> body = response.getBody();
        assertThat(body).as("接口应返回统一结构 " + path).isNotNull();
        assertThat(String.valueOf(body.get("state"))).as("接口应成功 " + path + " -> " + body).isEqualTo("200");
        return body;
    }

    /** 造一个启用状态的飞手，返回它的 id */
    private int createPilot(String username, String name) {
        String token = adminToken();
        call(token, HttpMethod.POST, "/user/add", """
                {"username":"%s","name":"%s","password":"123456","roleCode":"pilot"}
                """.formatted(username, name));

        Map<?, ?> body = call(token, HttpMethod.GET,
                "/user/list?keyword=" + username, null);
        Map<?, ?> page = (Map<?, ?>) body.get("data");
        Map<?, ?> first = (Map<?, ?>) ((List<?>) page.get("list")).get(0);
        return ((Number) first.get("id")).intValue();
    }

    /** 造一台待命设备，返回它的 id */
    private int createDrone(String code, String name) {
        String token = adminToken();
        call(token, HttpMethod.POST, "/device/add", """
                {"code":"%s","name":"%s","type":"multi-rotor"}
                """.formatted(code, name));

        Map<?, ?> body = call(token, HttpMethod.GET, "/device/list?keyword=" + code, null);
        Map<?, ?> page = (Map<?, ?>) body.get("data");
        Map<?, ?> first = (Map<?, ?>) ((List<?>) page.get("list")).get(0);
        return ((Number) first.get("id")).intValue();
    }

    /** 建一个任务，返回它的 id */
    private int createTask(String token, String taskName, String start, String end) {
        Map<?, ?> body = call(token, HttpMethod.POST, "/task/create", """
                {"taskName":"%s","priority":"P1","planStartTime":"%s","planEndTime":"%s"}
                """.formatted(taskName, start, end));
        return ((Number) ((Map<?, ?>) body.get("data")).get("id")).intValue();
    }

    private void dispatch(String token, int taskId, int pilotId, int droneId) {
        call(token, HttpMethod.POST, "/task/dispatch", """
                {"taskId":%d,"pilotId":%d,"droneId":%d}
                """.formatted(taskId, pilotId, droneId));
    }

    /**
     * 以某个用户的身份连上 WebSocket。
     *
     * <p>token 直接用 {@link JwtUtil} 生成，不走登录接口 ——
     * 握手只关心「token 是否有效、subject 是谁」，这样能让用例更聚焦，
     * 也少一次 HTTP 往返。（登录接口本身在另一个测试类里已经覆盖了）
     */
    private WebSocketSession connect(int userId, BlockingQueue<String> received) throws Exception {
        String url = "ws://localhost:" + port + "/ws/task?token=" + tokenFor(userId);

        WebSocketSession session = new StandardWebSocketClient()
                .execute(new CollectingHandler(received), url)
                .get(5, TimeUnit.SECONDS);
        openSessions.add(session);
        return session;
    }

    /**
     * 给某个用户 id 生成一个可用的 token。
     *
     * <p>飞手和管理员都用它 —— WebSocket 握手只认「token 有效 + subject 是用户 id」，
     * 不区分角色。角色与权限是在业务接口那一层才校验的。
     */
    /**
     * 在队列里等一条指定类型的推送，把其它类型的先丢掉（返回原始 JSON 对象）。
     *
     * <p>为什么不直接取第一条：连上时服务端会先发一条 {@code ready}。
     * 断言「第一条就是告警」会很脆，而且失败信息也误导人。
     */
    private Object awaitMessageOfType(BlockingQueue<String> inbox, String type, int seconds) throws Exception {
        long deadline = System.currentTimeMillis() + seconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            String raw = inbox.poll(500, TimeUnit.MILLISECONDS);
            if (raw == null) {
                continue;
            }
            Map<?, ?> parsed = objectMapper.readValue(raw, Map.class);
            if (type.equals(parsed.get("type"))) {
                return parsed;
            }
        }
        return null;
    }

    /** 喂一条遥测让后端识别出告警（跳过 MQTT 传输层，直接交给处理口） */
    private void raiseAlert(String deviceCode, long seq, String observedAt, String payloadJson) throws Exception {
        String raw = """
                {"deviceCode":"%s","type":"position","observedAt":"%s","seq":%d,"payload":%s}"""
                .formatted(deviceCode, observedAt, seq, payloadJson.trim());
        TelemetryEnvelope envelope = objectMapper.readValue(raw, TelemetryEnvelope.class);
        telemetryHandler.handle(new DeviceMessage(
                "drone/" + deviceCode + "/telemetry", deviceCode, LocalDateTime.now(), envelope, raw));
    }

    private String tokenFor(int userId) {
        return jwtUtil.generateToken(new UserPO().setId((long) userId).setUsername("ws-test"));
    }

    /** 把收到的每条消息丢进队列，供用例断言 */
    private static class CollectingHandler extends TextWebSocketHandler {
        private final BlockingQueue<String> received;

        CollectingHandler(BlockingQueue<String> received) {
            this.received = received;
        }

        @Override
        protected void handleTextMessage(WebSocketSession session, TextMessage message) {
            received.add(message.getPayload());
        }
    }
}
