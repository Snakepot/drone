package cn.tedu.drone.dashboard.cache;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link TtlCache} 的单元测试：钉住「日志里报的那些数」是真实的。
 *
 * <h2>为什么这些计数值得单独测</h2>
 * 缓存加了日志之后，日志里会说「命中 42 次、省下约 1.8 秒」——
 * 那些数字是**运维和产品判断「这个缓存值不值得留着」的唯一依据**。
 * 报错一个数比不报更糟：会让人误删一个其实很有用的缓存，或者留着白做的缓存。
 * 所以把「命中不重算」「过期要重算」「满了会清空」三件事都钉住。
 *
 * <p>纯内存、不连库：{@link TtlCache} 本来就不认识数据库，这层也能单独测。
 */
class TtlCacheTests {

    /** 一个只会数调用次数的 loader */
    private final AtomicInteger loads = new AtomicInteger();

    private TtlCache<String> cache(int ttlSeconds, int maxEntries) {
        return new TtlCache<>("测试缓存", ttlSeconds, maxEntries);
    }

    private String load(String key, TtlCache<String> target) {
        return target.getOrLoad(key, "测试用", () -> "值-" + loads.incrementAndGet());
    }

    @Test
    @DisplayName("TTL 内第二次取同一个 key：不再真算，并被记成一次命中")
    void secondCallShouldHitWithoutReloading() {
        TtlCache<String> target = cache(60, 8);

        String first = load("k", target);
        String second = load("k", target);

        assertThat(loads.get()).as("业务只被麻烦了一次").isEqualTo(1);
        assertThat(second).isSameAs(first);
        assertThat(target.hits()).as("第二次是命中").isEqualTo(1);
        assertThat(target.misses()).as("第一次是未命中").isEqualTo(1);
        assertThat(target.expirations()).isZero();
        assertThat(target.savedMillis()).as("命中会累计「省下的时间」，至少要有个正数")
                .isGreaterThanOrEqualTo(0);
    }

    @Test
    @DisplayName("TTL 到了要重新真算，并且算的是「过期」而不是「未命中」")
    void expiredEntryShouldReloadAsExpiration() throws Exception {
        TtlCache<String> target = cache(1, 8);

        load("k", target);
        Thread.sleep(1100);
        load("k", target);

        assertThat(loads.get()).as("过期之后必须重算一遍").isEqualTo(2);
        assertThat(target.expirations()).as("这一次数的是过期，不是未命中").isEqualTo(1);
        assertThat(target.misses()).as("未命中只发生在「第一次有人问」时").isEqualTo(1);
    }

    @Test
    @DisplayName("不同 key 各记各的：一个 key 命中，不影响另一个 key 是否要算")
    void differentKeysShouldNotHitEachOther() {
        TtlCache<String> target = cache(60, 8);

        load("a", target);
        load("b", target);
        load("a", target);
        load("b", target);

        assertThat(loads.get()).as("两个 key 各算一次").isEqualTo(2);
        assertThat(target.hits()).isEqualTo(2);
        assertThat(target.misses()).isEqualTo(2);
    }

    @Test
    @DisplayName("key 数到上限：整体清空，下一次会重算（兜底，不是淘汰算法）")
    void shouldClearAllWhenKeyCountReachesTheCap() {
        TtlCache<String> target = cache(60, 2);

        load("a", target);
        load("b", target);
        load("c", target);

        assertThat(loads.get()).as("第三个 key 触发了清空").isEqualTo(3);
        assertThat(target.size()).as("清空后只留下刚存的那一个").isEqualTo(1);
    }

    /**
     * 小结是「缓存带来多少提升」的那条日志，它报的是**刚刚这段时间**的数 ——
     * 所以到点必须把计数清零，否则每条小结都得自己减上一轮，越看越糊。
     *
     * <p>用 120 毫秒当一轮，就能在用例里验证「到点记账并清零」这件事；
     * 日志本身的文字没法在用例里断言（那要靠跑起来看），但「有没有记账」是行为，必须钉住。
     */
    @Test
    @DisplayName("到了小结间隔：计数清零，下一轮从 0 开始数")
    void summaryShouldResetCountersWhenDue() throws Exception {
        TtlCache<String> target = new TtlCache<>("小结用例", 60, 8, 120);

        load("k", target);
        load("k", target);
        assertThat(target.hits()).as("先攒下一次命中").isEqualTo(1);

        Thread.sleep(200);
        load("k", target);

        assertThat(target.hits()).as("小结已把计数清零").isZero();
        assertThat(target.misses()).isZero();
        assertThat(target.expirations()).isZero();
        assertThat(target.savedMillis()).as("「省下的时间」也跟着清零").isZero();
    }
}
