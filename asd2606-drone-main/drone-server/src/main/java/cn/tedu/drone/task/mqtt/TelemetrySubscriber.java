package cn.tedu.drone.task.mqtt;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.client.mqttv3.IMqttActionListener;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.IMqttToken;
import org.eclipse.paho.client.mqttv3.MqttAsyncClient;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 设备数据订阅器：连上 broker，订阅约定的 Topic，把收到的报文丢进队列。
 *
 * <h2>它在整条链路里的位置</h2>
 * <pre>
 * 设备 → broker(EMQX) → [本类] → 有界队列 → 消费线程 → 证据落库
 *                       解析报文      背压保护    写数据库
 * </pre>
 * 本类只做三件事：<b>连接</b>、<b>订阅</b>、<b>把字节解析成对象后入队</b>。
 * 一个字节都不往数据库写 —— 原因见 {@link TelemetryIngestQueue} 的类注释。
 *
 * <h2>先搞懂「回调」是什么，再看代码（新手从这节读）</h2>
 * <b>回调 = 你先把方法交给别人，别人在合适的时刻替你调用它。</b>
 * 这里的「别人」是 Paho 客户端。所以本类只做两件事：
 * <ol>
 *   <li><b>注册</b>：启动时告诉 Paho「收到消息该找谁」——
 *       就一句 {@code client.setCallback(this)}</li>
 *   <li><b>等着被调用</b>：之后 Paho 在收到消息、连接断开等时刻，
 *       反过来调用本类实现的那几个方法</li>
 * </ol>
 *
 * <p>所以这个类里<b>没有任何一处是你主动调用 {@code messageArrived} 的</b>，
 * 它是 Paho 调的。这正是初学者看回调代码最别扭的地方：
 * 方法定义了却「没人调用」—— 因为调用方在别人的代码里。
 *
 * <h3>整个过程按时间顺序是这样的（日志里的编号就是下面这些）</h3>
 * <pre>
 * 启动阶段（在 Spring 的线程上跑，只跑一次）
 *   1 注册  创建 Paho 客户端
 *   2 注册  setCallback(this) —— 一次性把 4 个方法交给 Paho：
 *            ├─ connectComplete   连上了
 *            ├─ connectionLost    断了
 *            ├─ messageArrived    收到消息
 *            └─ deliveryComplete  投递完成
 *   3 发起  connect()，顺手给「这一次连接」注册一个一次性回调
 *
 * 之后（跑在 Paho 的网络线程上，什么时刻由网络决定）
 *   4 回调  connectComplete → 开始订阅
 *   5 发起  subscribe()，同样给它注册一个一次性回调
 *   6 回调  订阅成功 —— 到这一步链路才算真正通了
 *   7 回调  messageArrived → 解析 → 丢进队列 → 立刻返回
 *   8 回调  connectionLost（异常路径）→ 不用管，Paho 自己重连
 *   9 回调  deliveryComplete（我们只订阅不发布，基本不会走到）
 *  10 停机  disconnect()
 * </pre>
 *
 * <p><b>三处最容易混的地方</b>：
 * <ul>
 *   <li><b>两个地方都在"注册"，但不是一回事</b>：第 2 步注册的「回调对象」是
 *       <b>长期有效</b>的，连几次、收多少条消息都用它；第 3、5 步注册的
 *       「动作监听器」（{@link IMqttActionListener}）是<b>一次性的</b>，
 *       只汇报那一次操作的结果，用完就完。</li>
 *   <li><b>注册和调用不在同一个时刻，也不在同一个线程</b>：第 1~3 步是当前线程，
 *       第 4 步之后是 {@code MQTT Call: xxx}（看日志里那一列线程名）。
 *       这就是「回调在别人的线程上执行」的直接证据，也是回调里不能做重活的原因 ——
 *       卡住的是 Paho 的网络线程，心跳发不出去，连接就会被 broker 断掉。</li>
 *   <li><b>异步的 API 立刻返回，所以不能马上说"成功"</b>：{@code connect()} 和
 *       {@code subscribe()} 都是把请求丢出去就返回，结果要等回调。
 *       所以日志里第 3、5 步说的是「已发出」，第 4、6 步才说「成功」。</li>
 * </ul>
 *
 * <h2>为什么用 MqttAsyncClient 而不是 MqttClient</h2>
 * 同步版 {@code MqttClient} 的每个 API 都会阻塞等待 broker 应答。
 * 我们的场景里「连不上 broker」是完全可能的（本地没起 EMQX、网络抖动），
 * 用同步版就得自己开线程去调，否则会把 Spring 的启动流程卡住。
 * 异步版天生不阻塞，回调告诉你结果，更贴合这里的需要。
 * 注意「异步」指的是<b>连接管理</b>异步，收到消息后的回调仍然跑在 Paho 的网络线程上，
 * 所以回调里依然不能做重活。
 *
 * <h2>clientId 为什么要在配置的后面拼随机串</h2>
 * MQTT 协议规定 clientId 在整个 broker 上<b>必须唯一</b>。而 broker 遇到
 * 重复的 clientId 时，处理方式是<b>把先连的那个踢掉</b>（它认为新来的是重连，
 * 或者认为旧的已经死了）。后果是两个人同时本地开发、或者部署了两个实例时，
 * 两个进程会疯狂互踢 —— 日志里看到的是「连上、断开、连上、断开」的无限循环，
 * 而两边都认为自己没问题。这是新手最容易踩、也最难自己看出来的坑。
 *
 * <p>所以配置里的 {@code drone.mqtt.client-id} 只是<b>前缀</b>，
 * 真正的 ID 形如 {@code drone-server-a1b2c3}。代价是每次重启都是一个「新客户端」，
 * 连不上「服务端记住我没收到的消息」这种能力（那需要固定的 clientId +
 * cleanSession=false）—— 对一个消费实时遥测的服务来说这个代价可以接受，
 * 理由见下面 cleanSession 的注释。
 *
 * <h2>cleanSession = true 意味着什么（以及为什么可以接受）</h2>
 * cleanSession 为 true 时，broker 不会为我们保留「离线期间的消息」：
 * 断开期间设备发的 QoS 1 消息，broker 直接丢掉，我们重连后收不到。
 * 换来的好处是 broker 上不留任何会话状态 —— 不会出现
 * 「某个早就下线的实例在 broker 上堆了几十万条消息」这种事。
 *
 * <p>判断依据是<b>数据的时效性</b>：遥测是时间序列，
 * 十分钟前的位置信息对「这个任务现在有没有在飞」毫无价值，
 * 补收反而会污染证据表（一条 observed_at 是昨天的遥测，
 * 会把任务错误地判成「已证实」）。真正需要「一条都不丢」的
 * 是业务事件（派发、完成），而那些走的是数据库事务，不走 MQTT。
 *
 * <p>如果将来确实需要「断线期间不丢」，改法是：固定 clientId +
 * {@code cleanSession=false} + broker 侧给会话设过期时间（避免会话永久堆积），
 * 且必须同时改用<b>共享订阅</b>（{@code $share/组名/topic}）才能多实例部署，
 * 否则又会回到「多实例互踢」的问题上。
 *
 * <h2>自动重连怎么工作</h2>
 * {@code setAutomaticReconnect(true)} 让 Paho 自己负责「连上之后又断了」的情况：
 * 它会按退避策略反复重试。但<b>订阅关系不会自动恢复</b>（cleanSession=true 时
 * broker 那边也没有），所以必须实现 {@link MqttCallbackExtended} 的
 * {@code connectComplete} —— 每次（重）连成功都在那里重新订阅一遍。
 * 这是「用异步客户端」最常被漏掉的一步：连上了，但一条消息都收不到。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "drone.mqtt.enabled", havingValue = "true", matchIfMissing = true)
public class TelemetrySubscriber implements MqttCallbackExtended, SmartLifecycle {

    /** Topic 约定：{@code drone/{设备编号}/...}，设备编号固定在第 2 层（下标 1） */
    private static final int DEVICE_CODE_LEVEL = 1;

    /** 日志里打印原始报文时最多截断到这个长度，避免一条超大报文刷屏 */
    private static final int RAW_LOG_LIMIT = 200;

    /** 首次连不上 broker 时，每隔多久重试一次 */
    private static final long RETRY_SECONDS = 30;

    private final MqttProperties properties;
    private final ObjectMapper objectMapper;
    private final TelemetryIngestQueue ingestQueue;

    /** 保存最终 clientId（前缀 + 随机串），停机时要用它去 disconnect */
    private final String clientId;

    private MqttAsyncClient client;

    /** 只在「首次连接失败」时用到的重试器，单线程足够 */
    private ScheduledExecutorService retryExecutor;

    /** 是否正在运行。SmartLifecycle 要求 start/stop 幂等，靠它判断 */
    private volatile boolean running = false;

    /**
     * 「正在连接」标志。
     *
     * <p>Paho 只公开了 {@code isConnected()}，没有公开「正在连」的状态，
     * 而我们的重试逻辑必须知道这一点 —— 否则一次连接失败后，
     * 自己的重试和 Paho 的自动重连可能同时发起连接请求，
     * 日志里会刷一堆「连接正在进行中」的报错。
     */
    private final AtomicBoolean connectInFlight = new AtomicBoolean(false);

    public TelemetrySubscriber(MqttProperties properties, ObjectMapper objectMapper,
                               TelemetryIngestQueue ingestQueue) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.ingestQueue = ingestQueue;
        this.clientId = buildUniqueClientId(properties.getClientId());
    }

    // ------------------------------------------------------------------
    // 生命周期：由 Spring 在容器刷新完成前后调用
    // ------------------------------------------------------------------

    /**
     * 为什么用 {@link SmartLifecycle} 而不是 {@code @PostConstruct}。
     *
     * <p>两处差别，都跟我们有关：
     * <ul>
     *   <li>{@code @PostConstruct} 在该 Bean 初始化时执行，这时
     *       <b>其他 Bean 可能还没初始化完</b>；SmartLifecycle 的 {@code start()}
     *       在<b>整个容器刷新完成</b>之后才调用，依赖肯定都就绪了。</li>
     *   <li>SmartLifecycle 给了配套的 {@code stop()} 与阶段概念，
     *       停机的顺序可控 —— 对「先停订阅、再停消费」这种要求很重要。</li>
     * </ul>
     */
    @Override
    public void start() {
        if (running) {
            return;
        }
        running = true;
        log.info("【MQTT 0 启动】订阅器开始启动，下面按 1→2→3 的顺序做「注册」，然后等 Paho 回调");
        doConnect();
    }

    /**
     * 停机。流程里的第 <b>10</b> 步。
     *
     * <p>开头的 {@code running} 判断不只是幂等保护，也是为了避免日志重复：
     * {@code SmartLifecycle.stop()} 和 {@code @PreDestroy} 会各调一次这个方法，
     * 第一次已经把连接断掉了，第二次直接返回即可。
     */
    @Override
    public void stop() {
        if (!running) {
            // 已经停过了。第一次进来时已经做完断开与清理，这里不能再来一遍
            return;
        }
        running = false;
        log.info("【MQTT 10 停机】准备断开 MQTT 连接（先停订阅器，再让队列把已收到的消息消费完）");
        shutdownRetryExecutor();
        disconnectQuietly();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /**
     * 默认阶段（0）。不特意调大调小 ——
     * 这个订阅器启动后只往队列里塞东西，不依赖别的 Bean 先启动。
     */
    @Override
    public int getPhase() {
        return 0;
    }

    /**
     * 停机时做一步「尽量把队列清干净」再断开。
     *
     * <p>顺序上先让订阅器停手（不会再入队新消息），
     * 再让队列消费完手上的消息，最后才断开连接。
     */
    @PreDestroy
    public void onDestroy() {
        stop();
    }

    // ------------------------------------------------------------------
    // 连接与订阅
    // ------------------------------------------------------------------

    /**
     * 发起连接。<b>刻意不阻塞</b>：broker 没起也不该让后端起不来。
     *
     * <p>设备数据接入是「增值功能」，不是后端的启动前置条件。
     * 如果这里让启动失败，一个 broker 挂了就会连带整个管理后台用不了 ——
     * 这是典型的「为了次要功能牺牲主要功能」。
     *
     * <p>本方法承担流程里的第 <b>1、2、3</b> 步。其中 1、2 步只在第一次调用时做
     * （创建客户端、注册回调对象），后面重试时走不到那两行 ——
     * 因为客户端和回调对象是长期有效的，重建反而会丢掉原来的连接状态。
     */
    private void doConnect() {
        try {
            if (client == null) {
                // ---------------- 第 1 步：创建客户端 ----------------
                client = new MqttAsyncClient(properties.getBrokerUrl(), clientId, new MemoryPersistence());
                log.info("【MQTT 1 注册】创建 Paho 客户端：broker={}，clientId={}。"
                                + "（配置里的 client-id 只是前缀，后面拼了随机串 —— "
                                + "同一个 clientId 在 broker 上会互相踢下线）",
                        properties.getBrokerUrl(), clientId);

                // ---------------- 第 2 步：注册回调对象 ----------------
                // 整个类里只有这一处「注册回调」。就这一句，把本对象交出去了：
                // 之后 connectComplete / connectionLost / messageArrived / deliveryComplete
                // 四个方法都由 Paho 反过来调用，我们不再需要（也无法）主动触发它们
                client.setCallback(this);
                log.info("【MQTT 2 注册】setCallback(this) 完成 —— 本对象已注册为回调对象。"
                        + "之后这 4 件事会由 Paho 主动调用本类的方法："
                        + "connectComplete（连上了）、connectionLost（断了）、"
                        + "messageArrived（收到消息）、deliveryComplete（投递完成）");
            }
            if (client.isConnected() || !connectInFlight.compareAndSet(false, true)) {
                // 已连上，或上一次连接请求还没回来 —— 都不要重复发起
                return;
            }

            // ---------------- 第 3 步：发起连接 ----------------
            // 注意 connect() 的返回值：MqttAsyncClient 的 connect() 返回的是 IMqttToken
            // （一个「异步操作的凭据」），**不是 boolean** —— 所以写
            //   boolean ok = client.connect(options);
            // 是编不过的。异步 API 的设计就是「不给你立即的答案」。
            //
            // 真想在当前线程拿到结果，只有一条路：
            //   IMqttToken token = client.connect(buildOptions());
            //   token.waitForCompletion(10_000);   // 在这里阻塞等着
            // 但那等于把异步当同步用，就把启动线程卡住了（broker 慢就卡 10 秒）。
            // 我们选回调这条路：请求发出去就返回，结果由 connectListener() 接住。
            client.connect(buildOptions(), null, connectListener());
            log.info("【MQTT 3 发起】connect() 已发出，本方法立刻返回、不等结果。"
                    + "这次连接的结果会回调到 connectListener() —— 注意它和第 2 步不是同一回事："
                    + "那个是长期的回调对象，这个只管这一次连接（用完就完）");
        } catch (MqttException e) {
            connectInFlight.set(false);
            log.warn("【MQTT 3 发起】connect() 连请求都没发出去：url={}，原因={}。"
                            + "{} 秒后重试（不影响后端启动，设备数据接入只是少了一个功能）",
                    properties.getBrokerUrl(), e.getMessage(), RETRY_SECONDS);
            scheduleRetry();
        }
    }

    /**
     * 连接参数。
     *
     * <p>几个刻意的选择：
     * <ul>
     *   <li>{@code cleanSession=true}：见类注释，我们不需要 broker 帮我们攒离线消息。</li>
     *   <li>{@code automaticReconnect=true}：连上之后断线由 Paho 自己重试。</li>
     *   <li>keepAlive 60 秒 + 连接超时 10 秒：心跳周期内没数据就往 broker 发 ping，
     *       超过约 1.5 倍 keepAlive 收不到回应，broker 会判定我们掉线。</li>
     *   <li><b>不设置遗嘱（Will）</b>：遗嘱是给「别人订我」用的 ——
     *       我们掉了要让别人知道。而本类是个纯订阅方，没人订我们的消息，
     *       设了也是空写一条 Topic。设备端倒是应该设遗嘱（掉线时广播「我失联了」），
     *       但那是设备侧的事，见 docs/MQTT与设备接入.md。</li>
     * </ul>
     */
    private MqttConnectOptions buildOptions() {
        MqttConnectOptions options = new MqttConnectOptions();
        options.setCleanSession(true);
        options.setAutomaticReconnect(true);

        // 用户名/密码为空时就不设置 —— 设置成空串会被某些 broker 当成「提供了错误凭证」而拒绝
        if (properties.getUsername() != null && !properties.getUsername().isBlank()) {
            options.setUserName(properties.getUsername());
            options.setPassword(properties.getPassword() == null
                    ? new char[0] : properties.getPassword().toCharArray());
        }

        options.setConnectionTimeout(10);
        options.setKeepAliveInterval(60);
        return options;
    }

    /**
     * 第 3 步那次连接的结果回调（<b>一次性</b>，不是第 2 步那个回调对象）。
     *
     * <p>Paho 会把「这次 connect 的结果」单独汇报到这里：成功走 {@code onSuccess}，
     * 失败走 {@code onFailure}。它和 {@link #connectComplete} 都会在连接成功时被调用 ——
     * 区别是{@code connectComplete} 属于回调对象、每次（重）连都会来，
     * 而这个监听器只对着<b>那一次</b> {@code connect()} 调用。
     *
     * <p>所以「连上了要干嘛」只写在 {@code connectComplete} 里，
     * 这里成功时空着 —— 两处都写订阅，将来迟早会改歪一处。
     * 失败则必须在这里处理：{@code connectComplete} 不会被调用，重试得我们自己安排。
     */
    private IMqttActionListener connectListener() {
        return new IMqttActionListener() {
            @Override
            public void onSuccess(IMqttToken asyncActionToken) {
                // 刻意什么都不做：真正的「连上了该干嘛」在 connectComplete 里，只写一处
                log.debug("【MQTT 3 回调】connect() 的结果回调 onSuccess（后续动作在 connectComplete 里做，这里不重复）");
            }

            @Override
            public void onFailure(IMqttToken asyncActionToken, Throwable exception) {
                connectInFlight.set(false);
                log.warn("【MQTT 4 回调】连接失败（这是第 3 步那次 connect() 的结果回调，"
                                + "不是 connectComplete）：url={}，原因={}。{} 秒后重试",
                        properties.getBrokerUrl(), exception.getMessage(), RETRY_SECONDS);
                scheduleRetry();
            }
        };
    }

    /**
     * 每次（重）连成功后的第一件事：<b>重新订阅</b>。
     *
     * <p>必须在这里订阅，不能只在启动时订一次 ——
     * 重连之后 broker 那边已经把我们当成一个全新客户端（cleanSession=true），
     * 之前的订阅关系不存在了。只连不订的后果是「连接正常、日志安静、一条数据都没有」，
     * 非常难排查。
     *
     * @param reconnect true 表示这是一次重连（而不是首次连接）
     */
    @Override
    public void connectComplete(boolean reconnect, String serverURI) {
        connectInFlight.set(false);
        log.info("【MQTT 4 回调】connectComplete 被调用：{}，serverURI={}。"
                        + "这个方法就是第 2 步注册的 4 个回调之一，跑在 Paho 的网络线程上"
                        + "（看日志的线程名，已经不是启动时那个线程了）。"
                        + "连接刚建立，接下来必须重新订阅，否则会出现「连上了却一条数据都收不到」",
                reconnect ? "这是一次重连" : "这是首次连接成功", serverURI);
        subscribeAll();
    }

    /**
     * 按配置逐条订阅。流程里的第 <b>5</b> 步。
     *
     * <h2>为什么不能订完就打「已订阅」</h2>
     * 因为 {@code MqttAsyncClient.subscribe()} 和 {@code connect()} 一样是<b>异步</b>的：
     * 它把请求交给网络就返回了，broker 那边到底接没接受还不知道。
     * 紧跟着打一条「已订阅」是在撒谎 —— 万一 broker 因为 ACL 拒绝了，
     * 日志上却显示订阅成功，问题会被彻底掩盖。
     *
     * <p>所以正确做法是给它挂一个结果回调（{@link #subscribeListener}），
     * 成功之后才算真的订上了。这也顺手把「broker 实际授予的 QoS 是多少」拿到了 ——
     * 它可能比我们请求的低（比如 broker 不支持该 topic 的 QoS 1）。
     */
    private void subscribeAll() {
        if (properties.getTopics() == null || properties.getTopics().isEmpty()) {
            log.warn("没有配置任何 MQTT 订阅（drone.mqtt.topics 为空），将收不到任何设备数据");
            return;
        }

        for (MqttProperties.Topic config : properties.getTopics()) {
            if (config == null || config.getTopic() == null || config.getTopic().isBlank()) {
                log.warn("跳过一条空的 Topic 配置");
                continue;
            }
            try {
                client.subscribe(config.getTopic(), config.getQos(), null,
                        subscribeListener(config.getTopic(), config.getQos()));
                log.info("【MQTT 5 发起】subscribe() 已发出：topic={}，请求 QoS={}。"
                                + "和 connect() 一样立刻返回，结果回调到 subscribeListener()",
                        config.getTopic(), config.getQos());
            } catch (MqttException e) {
                // 某一条订不上不该影响别的 Topic，也不该让整个订阅器退出
                log.error("【MQTT 5 发起】subscribe() 连请求都没发出去：topic={}，原因={}，这条 Topic 将收不到数据",
                        config.getTopic(), e.getMessage());
            }
        }
    }

    /**
     * 第 5 步那次订阅的结果回调（一次性）。流程里的第 <b>6</b> 步。
     *
     * <p>这是整条链路上最该看到的一行：它出现了，才说明 broker 真的接受了订阅，
     * 后面才可能有消息进来。如果只有第 5 步没有第 6 步，问题在 broker 侧
     * （ACL 拒绝、Topic 写法不合法、用户没权限），不用怀疑自己的业务代码。
     */
    private IMqttActionListener subscribeListener(String topic, int requestedQos) {
        return new IMqttActionListener() {
            @Override
            public void onSuccess(IMqttToken token) {
                int[] granted = token.getGrantedQos();
                log.info("【MQTT 6 回调】订阅成功：topic={}，请求 QoS={}，broker 实际授予 QoS={}。"
                                + "到这里链路才算真正通了 —— 第 5 步那行只说明请求发出去了",
                        topic, requestedQos, (granted == null || granted.length == 0) ? "未知" : granted[0]);
            }

            @Override
            public void onFailure(IMqttToken token, Throwable exception) {
                log.error("【MQTT 6 回调】订阅被拒绝：topic={}，原因={}。"
                                + "常见原因是 broker 的 ACL 不允许这个客户端订阅，或 Topic 写法不合法"
                                + "（# 只能放末尾）—— 见 docs/MQTT与设备接入.md 4.8",
                        topic, exception.getMessage());
            }
        };
    }

    /** 首次连不上时的重试：每 {@link #RETRY_SECONDS} 秒试一次，直到连上为止 */
    private void scheduleRetry() {
        if (!running) {
            return;
        }
        if (client != null && client.isConnected()) {
            // 已经连上了（可能是 Paho 的自动重连抢先生效），不必再安排
            return;
        }
        if (retryExecutor == null || retryExecutor.isShutdown()) {
            synchronized (this) {
                if (retryExecutor == null || retryExecutor.isShutdown()) {
                    retryExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
                        Thread t = new Thread(r, "drone-mqtt-retry");
                        t.setDaemon(true);
                        return t;
                    });
                }
            }
        }
        try {
            retryExecutor.schedule(this::doConnect, RETRY_SECONDS, TimeUnit.SECONDS);
            log.warn("【MQTT 3 重试】已安排在 {} 秒后再走一次第 3 步（连接）。"
                    + "第 1、2 步不会重做 —— 客户端和回调对象是长期有效的，重建反而会丢状态", RETRY_SECONDS);
        } catch (Exception e) {
            // 停机过程中调度器可能刚被关掉，忽略即可
            log.debug("安排 MQTT 重试时被拒绝（多半正在停机）：{}", e.getMessage());
        }
    }

    private void shutdownRetryExecutor() {
        if (retryExecutor != null) {
            retryExecutor.shutdownNow();
        }
    }

    /**
     * 优雅断开。
     *
     * <p>为什么要显式 disconnect 而不是直接退进程：
     * 一是让 broker 立刻知道我们走了（否则要等 keepAlive 超时才判定掉线，中间这段时间
     * 它还以为我们在线）；二是 cleanSession=true 时，正常断开会让 broker
     * <b>立刻清掉会话状态</b>，不留垃圾。
     *
     * <p>超时给 3 秒：broker 没响应也不能把停机卡死。
     */
    private void disconnectQuietly() {
        if (client == null) {
            return;
        }
        try {
            if (client.isConnected()) {
                client.disconnect(3000);
                log.info("【MQTT 10 停机】disconnect() 已发出，连接已断开，clientId={}", clientId);
            } else {
                log.info("【MQTT 10 停机】本来就未连接，无需 disconnect");
            }
        } catch (MqttException e) {
            log.warn("【MQTT 10 停机】断开连接时出错（忽略，不影响停机）：{}", e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // 消息回调 —— 注意：这里跑在 Paho 的网络线程上，必须尽快返回
    // ------------------------------------------------------------------

    /**
     * 收到消息。流程里的第 <b>7</b> 步。
     *
     * <p>再说一遍这个方法是怎么被调用的：<b>不是业务代码调的，是 Paho 调的</b>。
     * Paho 从 broker 收到消息后，在它自己的网络线程上调用第 2 步注册的这个方法。
     * 所以这里必须尽快返回 —— 整段逻辑只有「解析 → 丢进队列」两件事，
     * 写数据库的活在消费线程里（见 {@link TelemetryIngestQueue} 的类注释）。
     *
     * <p>解析失败就地丢弃：坏报文重试多少次还是坏的，记清楚日志就行。
     */
    @Override
    public void messageArrived(String topic, MqttMessage message) {
        String raw = new String(message.getPayload(), StandardCharsets.UTF_8);
        log.debug("【MQTT 7 回调】messageArrived 被调用（第 2 步注册的回调对象里的方法，跑在 Paho 网络线程上）："
                        + "topic={}，报文长度={}，报文={}", topic, raw.length(), truncate(raw));
        try {
            TelemetryEnvelope envelope = objectMapper.readValue(raw, TelemetryEnvelope.class);
            String deviceCode = resolveDeviceCode(topic, envelope);

            if (deviceCode == null || deviceCode.isBlank()) {
                log.warn("【MQTT 7 回调】无法确定设备编号，消息丢弃，topic={}，报文={}", topic, truncate(raw));
                return;
            }

            // raw 原样带上 —— 解析只是为了取「要建索引的那几个字段」，
            // 归档进证据表的必须是设备真正发出来的那段字节，理由见 DeviceMessage 的注释
            //
            // 入队成功/被丢弃都由 TelemetryIngestQueue 打日志（第 7.1 步），
            // 这里不重复打 —— 同一件事两处记，将来改了一处就会出现两条对不上的日志
            ingestQueue.submit(new DeviceMessage(topic, deviceCode, LocalDateTime.now(), envelope, raw));
        } catch (Exception e) {
            log.warn("【MQTT 7 回调】设备报文解析失败，消息丢弃，topic={}，报文={}，原因={}",
                    topic, truncate(raw), e.getMessage());
        }
    }

    /**
     * 消息投递完成回调。流程里的第 <b>9</b> 步。
     *
     * <p>「投递」指的是<b>我们往外发</b>的消息被 broker 确认收到（QoS 1/2 才有）。
     * 本类只订阅、不发布，所以这个回调基本不会走到 —— 保留它是为了把接口实现完整，
     * 顺便让读代码的人知道：回调对象里的方法不必每个都写逻辑，用不到的就留个日志。
     */
    @Override
    public void deliveryComplete(IMqttDeliveryToken token) {
        log.debug("【MQTT 9 回调】deliveryComplete（QoS 1/2 的投递确认）。"
                + "我们只订阅不发布，正常不会走到这里");
    }

    /**
     * 连接意外断开。流程里的第 <b>8</b> 步。
     *
     * <p>开启了 automaticReconnect 后，这里<b>不是错误处理点</b>，只是一条通知 ——
     * 重连由 Paho 负责，重连成功后会再走一次第 4 步（{@code connectComplete}
     * 的 {@code reconnect} 参数会是 true），那里会重新订阅。
     * 所以看到这条日志<b>不要慌，也不要试图在这里手动重连</b>：
     * 那会和 Paho 自己的重连打架，两个客户端互相踢。
     *
     * <p>它的用处是排查「数据为什么断了」—— 连续出现说明网络或 broker 不稳定。
     */
    @Override
    public void connectionLost(Throwable cause) {
        log.warn("【MQTT 8 回调】connectionLost 被调用：连接断了，原因={}。"
                        + "重连交给 Paho（第 4 步会在重连成功后被再次调用），这里什么都不用做",
                cause == null ? "原因未知" : cause.getMessage());
    }

    // ------------------------------------------------------------------
    // 内部工具
    // ------------------------------------------------------------------

    /**
     * 最终认定这条消息属于哪台设备。
     *
     * <h2>为什么以 Topic 里的一段为准，而不是报文里的 deviceCode</h2>
     * 因为 <b>Topic 是经过 broker 鉴权的那一段</b>。
     * MQTT 的 ACL 规则是按 Topic 写的（例如「设备 UAV-001 只允许发布
     * {@code drone/UAV-001/#}」），broker 会保证这台设备没法往别的 Topic 发。
     * 而报文正文里的 {@code deviceCode} 字段没有任何东西能保证 ——
     * 设备（或者拿到设备凭证的坏人）可以随便填另一个编号，
     * 那就等于「别人的证据被记到我这台设备名下」。
     *
     * <p>所以规则是：<b>以 Topic 为准；报文里的值只用来对账</b>。
     * 两者不一致时记 warn —— 这要么是设备固件的 bug，要么是有人在做不该做的事，
     * 两种都值得被看见。
     *
     * <p>Topic 层数不够（拿不到第 2 层）时退回用报文里的编号，
     * 这样自定义 Topic 结构的人也不至于完全用不了。
     */
    private String resolveDeviceCode(String topic, TelemetryEnvelope envelope) {
        String fromTopic = null;
        if (topic != null) {
            String[] levels = topic.split("/");
            if (levels.length > DEVICE_CODE_LEVEL && !levels[DEVICE_CODE_LEVEL].isBlank()) {
                fromTopic = levels[DEVICE_CODE_LEVEL];
            }
        }

        String fromBody = envelope.getDeviceCode();
        if (fromTopic == null) {
            return fromBody;
        }
        if (fromBody != null && !fromBody.isBlank() && !fromTopic.equals(fromBody)) {
            log.warn("设备编号不一致：Topic 里是 {}，报文里是 {}，以 Topic 为准（topic={}）",
                    fromTopic, fromBody, topic);
        }
        return fromTopic;
    }

    /**
     * 生成全局唯一的 clientId：配置值 + 随机后缀。
     *
     * <p>用随机串而不是主机名/IP：同一台机器上跑两个实例时，
     * 基于主机名的后缀还是一样的，照样互踢。
     */
    private static String buildUniqueClientId(String prefix) {
        String base = (prefix == null || prefix.isBlank()) ? "drone-server" : prefix.trim();
        return base + "-" + Long.toHexString(System.nanoTime() & 0xFFFFFFL);
    }

    private static String truncate(String text) {
        if (text == null) {
            return null;
        }
        return text.length() <= RAW_LOG_LIMIT ? text : text.substring(0, RAW_LOG_LIMIT) + "...";
    }
}
