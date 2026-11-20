package cn.tedu.drone.task.mqtt;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * MQTT 接入配置，绑定 application.yml 里的 {@code drone.mqtt.*}。
 *
 * <h2>为什么要单独建一个类，而不是到处写 @Value</h2>
 * 因为「订阅哪些 Topic」本身是个<b>列表</b>，{@code @Value} 表达不了。
 * 而且配置集中在一个类里，改字段名时 IDE 能一次全找出来，
 * 不会出现「yml 改了、代码里某处 @Value 还是老名字」这种只在运行时才炸的问题。
 *
 * <h2>@Component + @ConfigurationProperties 是怎么生效的</h2>
 * Spring Boot 启动时会扫描到 {@code @ConfigurationProperties} 注解，
 * 把 yml 里 {@code drone.mqtt} 下的键按名字赋给这个类的字段
 * （{@code broker-url} → {@code brokerUrl}，这叫「松散绑定」，横杠等同驼峰）。
 * 加 {@code @Component} 是为了让它被组件扫描发现 ——
 * 不加的话得额外在某个 {@code @Configuration} 上写
 * {@code @EnableConfigurationProperties(MqttProperties.class)}，多一步没必要。
 *
 * <h2>字段上为什么给默认值</h2>
 * 给默认值 = 「yml 里不写也能跑起来」。这既让本地起步更容易，
 * 也让单元测试不必为了启动上下文而准备一份完整配置。
 */
@Data
@Component
@ConfigurationProperties(prefix = "drone.mqtt")
public class MqttProperties {

    /** 总开关。关掉后订阅器这个 Bean 根本不会创建（见 {@link TelemetrySubscriber}） */
    private boolean enabled = true;

    /** broker 地址，形如 {@code tcp://localhost:1883}（明文）或 {@code ssl://host:8883}（TLS） */
    private String brokerUrl = "tcp://localhost:1883";

    /**
     * 客户端 ID 的<b>前缀</b>，不是最终 ID。
     *
     * <p>真正的 clientId 由 {@link TelemetrySubscriber} 在前缀后拼一段随机串生成。
     * 原因见那边的方法注释：clientId 在 broker 上必须唯一，
     * 两个进程用同一个 ID 会互相把对方踢下线。
     */
    private String clientId = "drone-server";

    /** 用户名。为空表示匿名连接 —— 本地开发方便，生产必须配上 */
    private String username = "";

    /** 密码。生产环境请用环境变量注入，不要写进 yml 提交到仓库 */
    private String password = "";

    /** 要订阅的 Topic 清单，按书写顺序依次订阅 */
    private List<Topic> topics = new ArrayList<>();

    /** 回调线程与消费线程之间那道有界队列的容量，满了就丢并告警 */
    private int queueCapacity = 10000;

    /** 消费线程数。写库是 IO 型活儿，默认 2 个 */
    private int workerThreads = 2;

    /**
     * 证据新鲜度阈值（秒）：飞手声明「开始执行」后，多久收不到该设备的遥测，
     * 就把它标成 {@code stale}。只影响 evidence_state，不动任务状态。
     */
    private long evidenceStaleSeconds = 120;

    /**
     * 一条订阅。之所以做成对象而不是直接写字符串，
     * 是因为 QoS 是<b>每条订阅各自</b>的事：遥测可以 QoS 0，事件必须 QoS 1。
     */
    @Data
    public static class Topic {

        /** Topic 过滤器，可含通配符 {@code +}（单层）与 {@code #}（多层，只能放末尾） */
        private String topic;

        /**
         * 服务质量：0 最多一次 / 1 至少一次 / 2 恰好一次。
         *
         * <p>取值最终以「订户订阅时的 QoS」和「发布时的 QoS」中<b>较小的那个</b>生效 ——
         * 设备端发 QoS 0，我们订 QoS 2 也拿不到重传保障。这一点新手常搞反。
         */
        private int qos = 0;
    }
}
