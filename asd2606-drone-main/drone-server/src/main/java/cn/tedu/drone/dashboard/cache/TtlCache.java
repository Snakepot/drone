package cn.tedu.drone.dashboard.cache;

import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * 一个极小的「写过就 N 秒过期」缓存。
 *
 * <h2>为什么手写而不引 Caffeine / Spring Cache</h2>
 * 我们需要的只有一件事：**同一个 key 在 N 秒内只真算一次**。
 * 而这件事的正确性关键在「<b>key 怎么拼</b>」（大屏那个必须带权限与用户，
 * 见 {@code DashboardServiceImpl}），那是调用方的责任 —— 换哪个库都一样。
 *
 * <p>等真需要「按大小淘汰 / 异步刷新」时再换成 Caffeine：
 * 那时它是「一个依赖换几个真需要的能力」，现在只是「多一个依赖」。
 * <b>命中率统计这件事</b>我们后来自己补上了（见下面第三条），因为它就是「上缓存值不值」的答案。
 *
 * <h2>缓存与业务怎么配合：一条未命中的日志说全了</h2>
 * 业务只提供两样东西：<b>key</b>（含身份，防越权）和 <b>真算一遍的方法</b>（{@link #getOrLoad} 的 loader）。
 * 剩下的时机与记账都在这里：
 * <ol>
 *   <li><b>命中</b> → 直接返回，业务一行库都没查（记 debug：大屏每 10 秒问一次，
 *       每条都记 INFO 会把日志淹掉）</li>
 *   <li><b>未命中 / 过期</b> → 先给业务计时、真算一遍，再把它连同<b>这次的耗时</b>存回去
 *       （记 INFO —— 这是「贵的那一个」，必须看得见）</li>
 *   <li><b>每 60 秒</b>一条小结：命中/未命中/过期各几次、命中率、<b>省下约多少毫秒</b>
 *       （记 INFO —— 这才是「缓存到底带来多少提升」的答案）</li>
 * </ol>
 *
 * <h2>「省下多少」是怎么算出来的</h2>
 * 每条记录里存着「上次真算它花了多久」（{@code costMillis}）。
 * 命中时把那个值累加进 {@code savedMillis} —— 语义是「这一次本来要花的钱没花」。
 * 它是**估算**（每次都真算一遍才知道准确值），但对「值不值得留着这个缓存」这个问题够用了。
 *
 * <h2>两个必须说明的边界</h2>
 * <ul>
 *   <li><b>TTL 必须 ≤ 该数据的刷新间隔</b>。大屏的统计 60 秒刷一次，
 *       缓存 10 秒是安全的；但如果缓存比刷新间隔还长，
 *       大屏就会「一直在刷、永远看到同一个值」—— 那比不做缓存更糟（看起来像卡住了）。
 *       {@code DashboardServiceImpl} 启动时会检查这一条并记 warn</li>
 *   <li><b>只缓存「不变一会儿也没关系」的数据</b>。所以它只用在大屏的统计与告警流上，
 *       不碰任何写操作、也不碰「告警中心列表」那种用户要立刻看到变化的接口</li>
 * </ul>
 *
 * <h2>为什么不是线程安全的强需求</h2>
 * 它不是强一致的东西：两个线程同时 miss、同时算一遍，只是多算一次，
 * 结果仍然正确（算的是同一份数据）。所以这里用最朴素的 {@link ConcurrentHashMap}，
 * 不加锁、不做 future 合并 —— 那些是「省那一次重复计算」的优化，
 * 而我们的重复计算代价只有一条聚合 SQL。
 *
 * <h2>过期与未命中要分开数</h2>
 * 两者都会触发「真算一遍」，但含义完全不同：<b>未命中</b>是「这轮第一次有人问」，
 * <b>过期</b>是「存过、但 TTL 到了」。过期次数一直很高，说明 TTL 比刷新间隔短太多 ——
 * 缓存等于白做（每次都过期，一次也没省下）。这也是它能被发现的唯一途径。
 */
@Slf4j
public class TtlCache<V> {

    /** 小结的间隔：把这段时间里的命中/未命中/省下的时间聚成一条 INFO */
    private static final long SUMMARY_INTERVAL_MILLIS = 60_000L;

    /** key → （值, 过期时刻, 上次真算它花了多久） */
    private record Entry<V>(V value, long expiresAt, long costMillis) {
    }

    /** 日志里的名字（总览 / 告警流各一个），也用来在启动时把配置说清楚 */
    private final String name;

    private final Map<String, Entry<V>> store = new ConcurrentHashMap<>();
    private final long ttlMillis;

    /**
     * key 数量的上限，兜底用。
     *
     * <p>key 是「用户 + 权限集合」的指纹，理论上很有限（角色就那么几个）。
     * 但万一将来有人把会变的东西拼进 key（比如时间戳），这里至少要保证
     * 它不会无限涨 —— 满了就整体清空，代价是下一轮全部重算一次。
     */
    private final int maxEntries;

    // ---- 只服务「缓存到底省了多少」这一个问题的计数 ----

    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();
    private final AtomicLong expirations = new AtomicLong();
    private final AtomicLong savedMillis = new AtomicLong();
    private final AtomicLong lastSummaryAt = new AtomicLong(System.currentTimeMillis());

    /** 小结间隔。做成字段只为让用例能用很短的间隔验证「到点记账并清零」，生产就取那个常量 */
    private final long summaryIntervalMillis;

    public TtlCache(String name, int ttlSeconds, int maxEntries) {
        this(name, ttlSeconds, maxEntries, SUMMARY_INTERVAL_MILLIS);
    }

    TtlCache(String name, int ttlSeconds, int maxEntries, long summaryIntervalMillis) {
        this.name = name;
        this.ttlMillis = Math.max(1, ttlSeconds) * 1000L;
        this.maxEntries = Math.max(1, maxEntries);
        this.summaryIntervalMillis = summaryIntervalMillis;
    }

    /**
     * 取缓存；没有或已过期就用 {@code loader} 真算一遍，并给它计时。
     *
     * @param key    缓存的键。<b>拼错它比不缓存更糟</b>：漏了身份就是越权（见类注释）
     * @param what   这一步在做什么，只用于日志（如「总览（按权限裁块）」）——
     *               由业务给词，因为只有它知道自己在算什么
     * @param loader 真算一遍的方法。它<b>只在未命中/过期时</b>被调用
     */
    public V getOrLoad(String key, String what, Supplier<V> loader) {
        long readStart = System.nanoTime();
        Entry<V> entry = store.get(key);

        if (entry != null && System.currentTimeMillis() <= entry.expiresAt()) {
            long saved = entry.costMillis();
            hits.incrementAndGet();
            savedMillis.addAndGet(saved);
            // 命中是「便宜的那一个」：大屏每 10 秒问一次、还可能同时挂着好几块屏，
            // 每条都记 INFO 会把日志淹掉。所以命中记 debug，收益交给每分钟那条小结
            log.debug("【{}】缓存命中｜{}｜key={}｜取值 {} μs｜省下约 {}（上次真算它花了这么久）",
                    name, what, key, (System.nanoTime() - readStart) / 1000, humanMillis(saved));
            summarizeIfDue();
            return entry.value();
        }

        boolean expired = entry != null;
        if (expired) {
            // 到点了：这不是「没存过」，是「存过但过期了」—— 两者要分开数（见类注释）
            store.remove(key);
            expirations.incrementAndGet();
        } else {
            misses.incrementAndGet();
        }

        long loadStart = System.nanoTime();
        V fresh = loader.get();
        long costMillis = Math.max(1, (System.nanoTime() - loadStart) / 1_000_000L);
        store(key, fresh, costMillis);
        summarizeIfDue();

        // 未命中是「贵的那一个」，必须看得见。这一条把「缓存与业务怎么配合」说全了：
        //   ① 谁来问（key 里带身份）② 业务真算一遍（花了多久）③ 存起来，接下来 N 秒内不再麻烦业务
        // 「未命中」与「已过期」分开写：前者是「这轮第一次有人问」，后者是「TTL 比刷新间隔短了」
        log.info("【{}】{}：{}｜key={}｜业务真算一遍花了 {}｜接下来 {} 秒内同一 key 直接取这份",
                name, expired ? "缓存已过期" : "缓存未命中", what, key,
                humanMillis(costMillis), ttlMillis / 1000);
        return fresh;
    }

    private void store(String key, V value, long costMillis) {
        if (store.size() >= maxEntries) {
            log.warn("【{}】缓存的 key 数到了上限 {}，整体清空重来。"
                            + "正常情况下 key 只有几条（身份就那么几种）——"
                            + "出现这一条，先看看是不是有会变的东西被拼进了 key",
                    name, maxEntries);
            store.clear();
        }
        store.put(key, new Entry<>(value, System.currentTimeMillis() + ttlMillis, costMillis));
    }

    /**
     * 每满 60 秒记一条小结 —— 「缓存带来多少提升」的答案就是这一条。
     *
     * <p>取值即清零：每条小结说的是**刚刚这段时间**，不是「自启动以来」，
     * 所以每条日志自己就是完整的，不用回头翻。
     */
    private void summarizeIfDue() {
        long now = System.currentTimeMillis();
        long last = lastSummaryAt.get();
        // 到点 + 抢到「报告权」才记：多线程同时到点时只让一个线程打这一条
        if (now - last < summaryIntervalMillis || !lastSummaryAt.compareAndSet(last, now)) {
            return;
        }

        long hit = hits.getAndSet(0);
        long miss = misses.getAndSet(0);
        long expired = expirations.getAndSet(0);
        long saved = savedMillis.getAndSet(0);
        long total = hit + miss + expired;
        if (total == 0) {
            return;
        }

        log.info("【{}】缓存小结（近 {} 秒）：命中 {} 次 / 未命中 {} 次 / 过期重算 {} 次，命中率 {}%，"
                        + "省下约 {} —— 这些请求都没有再去查一遍库；当前缓存 key 数 {}",
                name, summaryIntervalMillis / 1000, hit, miss, expired,
                Math.round(hit * 1000.0 / total) / 10.0, humanMillis(saved), store.size());
    }

    /** 把毫秒说成人话：几百毫秒就说毫秒，几秒就说秒，免得日志里出现「省下约 74213 ms」 */
    private String humanMillis(long millis) {
        if (millis < 1000) {
            return millis + " ms";
        }
        if (millis < 60_000) {
            return Math.round(millis / 100.0) / 10.0 + " 秒";
        }
        return Math.round(millis / 6000.0) / 10.0 + " 分";
    }

    /** 缓存条数（小结里会报：正常应该只有几条，几十条就该看看 key 是不是拼错了） */
    public int size() {
        return store.size();
    }

    /** 这个缓存的 TTL（秒），给启动时的配置检查用 */
    public long ttlSeconds() {
        return ttlMillis / 1000;
    }

    // ---- 下面几个只给用例用：钉住「日志里报的那些数」是真实的 ----

    long hits() {
        return hits.get();
    }

    long misses() {
        return misses.get();
    }

    long expirations() {
        return expirations.get();
    }

    long savedMillis() {
        return savedMillis.get();
    }
}
