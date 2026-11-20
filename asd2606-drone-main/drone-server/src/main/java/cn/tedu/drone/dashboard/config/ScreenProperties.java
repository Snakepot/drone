package cn.tedu.drone.dashboard.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 数字大屏自己的配置，绑定 {@code drone.screen.*}。
 *
 * <h2>为什么配置放后端、不在前端写死</h2>
 * 这几项（每屏停多久、刷新多快、轮不轮播）**是运维会想改的东西**，
 * 而不是代码的一部分：值班室那台电视上「每 15 秒换一屏」可能太慢，
 * 会议室那块屏可能想快一点。让它们跟着 {@code application.yml} 走，
 * 改完重启即可，不用改代码、不用重新构建前端。
 *
 * <h2>将来要做配置页时，只改这里</h2>
 * 现在这些值来自 yml；将来加一张 {@code screen_config} 表 +
 * 一个 {@code POST /dashboard/config} 即可 —— **前端读的接口不变**
 * （它一直只认 {@code GET /dashboard/config}）。所以前端一行都不用改。
 *
 * <h2>⚠️ 别把这些值做成「前端可传」的参数</h2>
 * 一旦前端能传刷新间隔，就等于任何调用者都能让服务端按他的节奏跑查询 ——
 * 大屏这类「长期挂着、反复轮询」的场景，刷新节奏必须由服务端说了算。
 */
@Data
@Component
@ConfigurationProperties(prefix = "drone.screen")
public class ScreenProperties {

    // ------------------------------------------------------------------
    // 轮播
    // ------------------------------------------------------------------

    /** 是否自动轮播。关掉就是一屏到底（只看总览） */
    private boolean carouselEnabled = true;

    /**
     * 轮播哪几屏、按什么顺序。
     *
     * <p>取值只有三个：{@code overview}（总览数字）/ {@code map}（大地图）/
     * {@code alerts}（告警墙）。写错的取值会被**忽略并记一条 warn** ——
     * 而不是抛异常让大屏起不来：一块屏显示不全，总比整块屏白屏强。
     */
    private List<String> carouselPages = List.of("overview", "map", "alerts");

    /** 每屏停留秒数 */
    private int carouselSeconds = 15;

    /**
     * 有**未处理的 critical 告警**时，是否停在告警屏不往下轮。
     *
     * <p>默认关：它会让大屏「卡住」，而卡住的原因在屏幕上不一定看得出来。
     * 打开时一定要配合 {@link #carouselPinMaxSeconds} —— 见那个字段的说明。
     */
    private boolean carouselPinOnCritical = false;

    /**
     * 就算被 critical 钉住，最长也停这么多秒，然后强制继续轮播。
     *
     * <p><b>这个兜底不能省</b>：没有它，一条没人处理的 critical 告警会让大屏
     * <b>永远</b>停在告警屏 —— 而值班室那块屏正是「没人管」的时候才需要它自己转。
     */
    private int carouselPinMaxSeconds = 300;

    // ------------------------------------------------------------------
    // 刷新（三档，按数据变化的快慢分）
    // ------------------------------------------------------------------

    /** 地图上的飞机位置：秒级变化，刷得最勤 */
    private int refreshMapSeconds = 3;

    /** 告警流、执行中任务数：秒级 */
    private int refreshLiveSeconds = 10;

    /** 各类总数、分布图、累计值：分钟级都不变 */
    private int refreshStatSeconds = 60;

    /**
     * 页面不可见（切到别的标签页、屏保）时停刷。
     *
     * <p>默认开。大屏通常全天开着，但浏览器被切走的时间不短 ——
     * 那段时间的请求全是白花的。
     */
    private boolean pauseWhenHidden = true;

    // ------------------------------------------------------------------
    // 缓存（见 业务分析方案.md 5.2 ②）
    //   ⚠️ 这两项**必须 ≤ 对应的刷新间隔**：缓存比刷新间隔还长，
    //      大屏就会「一直在刷、永远看到同一个值」—— 那比不做缓存更糟（看起来像卡住）。
    //      DashboardServiceImpl 启动时会检查并记 warn
    // ------------------------------------------------------------------

    /** 总览那一次聚合（十几条 COUNT）的缓存秒数；统计档是 60 秒刷一次，缓存 10 秒很安全 */
    private int summaryCacheSeconds = 10;

    /** 告警滚动流的缓存秒数（动态档 10 秒刷一次） */
    private int recentCacheSeconds = 5;

    // ------------------------------------------------------------------
    // 夜间
    // ------------------------------------------------------------------

    /**
     * 夜间是否停刷。**默认关（不停刷）** —— 这是 2026-09-29 拍板的。
     *
     * <p>开关留着：将来如果觉得夜里没人看还一直在刷太费，把它打开即可，
     * 不用改代码。打开后受 {@link #nightPauseFrom} / {@link #nightPauseTo} 控制。
     */
    private boolean nightPauseEnabled = false;

    /** 夜间停刷的起点（含），格式 {@code HH:mm} */
    private String nightPauseFrom = "02:00";

    /** 夜间停刷的终点（不含），格式 {@code HH:mm}。跨零点也支持（如 22:00 ~ 06:00） */
    private String nightPauseTo = "06:00";
}
