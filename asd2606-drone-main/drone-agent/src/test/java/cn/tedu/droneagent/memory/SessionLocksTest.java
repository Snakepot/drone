package cn.tedu.droneagent.memory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SessionLocks} 的单元测试：同一会话串行、不同会话互不干扰、用完不留残留。
 */
class SessionLocksTest {

    private static final long USER = 7007L;

    private static final SessionKey KEY = new SessionKey(USER, "conv-a");

    private final SessionLocks locks = new SessionLocks();

    @Test
    @DisplayName("同一会话第二次拿不到，释放后才能再拿")
    void sameSessionIsSerialized() throws Exception {
        assertTrue(locks.tryAcquire(KEY, Duration.ofMillis(50)));

        assertFalse(locks.tryAcquire(KEY, Duration.ofMillis(50)), "同一会话不该被两条请求同时持有");

        locks.release(KEY);
        assertTrue(locks.tryAcquire(KEY, Duration.ofMillis(50)), "释放后应能再拿到");
        locks.release(KEY);
    }

    @Test
    @DisplayName("不同会话互不阻塞")
    void differentSessionsDoNotBlockEachOther() throws Exception {
        SessionKey other = new SessionKey(USER, "conv-b");

        assertTrue(locks.tryAcquire(KEY, Duration.ZERO));
        assertTrue(locks.tryAcquire(other, Duration.ofMillis(50)), "别的会话不该被挡住");

        locks.release(KEY);
        locks.release(other);
    }

    @Test
    @DisplayName("用完不留残留：跑很多会话之后表应为空")
    void entriesAreRemovedAfterRelease() throws Exception {
        for (int i = 0; i < 100; i++) {
            SessionKey key = new SessionKey(USER, "c" + i);
            assertTrue(locks.tryAcquire(key, Duration.ofMillis(50)));
            locks.release(key);
        }

        assertEquals(0, locks.trackedSessions(),
                "应把表项清掉，不能为每个历史会话长期留一个信号量");
    }

    @Test
    @DisplayName("还有人在用时表项不能被移除（否则两人会各拿到一个信号量，互斥失效）")
    void entrySurvivesWhileAnotherHolderUsesIt() throws Exception {
        assertTrue(locks.tryAcquire(KEY, Duration.ZERO));

        CountDownLatch acquired = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        Thread second = new Thread(() -> {
            try {
                if (locks.tryAcquire(KEY, Duration.ofSeconds(5))) {
                    acquired.countDown();
                    finished.await(5, TimeUnit.SECONDS);
                    locks.release(KEY);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        second.start();

        locks.release(KEY);
        assertTrue(acquired.await(5, TimeUnit.SECONDS), "等待者应拿到刚释放的许可");
        assertEquals(1, locks.trackedSessions(), "还有人在用，表项必须留着");

        finished.countDown();
        second.join(5_000);
        assertEquals(0, locks.trackedSessions(), "都释放之后才移除");
    }
}
