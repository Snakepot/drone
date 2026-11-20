package cn.tedu.droneagent.common;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 一个极简的带过期时间的内存缓存，键是 Long（用户 ID）。
 *
 * <p>只用在这里给 RBAC 数据做缓存，所以没做成通用缓存框架：
 * 没有淘汰策略、没有统计，够用就好。
 * 容量上限的作用是防止内存无界增长（用户数理论上有限，但别赌这个）。
 *
 * @param <V> 缓存的值类型
 */
public class TtlCache<V> {

    private record Entry<V>(V value, long expireAt) {
    }

    private final long ttlMillis;

    private final int maxSize;

    private final Map<Long, Entry<V>> store = new ConcurrentHashMap<>();

    public TtlCache(long ttlMillis, int maxSize) {
        this.ttlMillis = ttlMillis;
        this.maxSize = maxSize;
    }

    /** 取未过期的值；没有或已过期返回 null */
    public V get(Long key) {
        if (ttlMillis <= 0) {
            return null;
        }
        Entry<V> entry = store.get(key);
        if (entry == null) {
            return null;
        }
        if (System.currentTimeMillis() > entry.expireAt()) {
            store.remove(key, entry);
            return null;
        }
        return entry.value();
    }

    public void put(Long key, V value) {
        if (ttlMillis <= 0) {
            return;
        }
        if (store.size() >= maxSize) {
            evictExpired();
        }
        store.put(key, new Entry<>(value, System.currentTimeMillis() + ttlMillis));
    }

    private void evictExpired() {
        long now = System.currentTimeMillis();
        store.entrySet().removeIf(e -> now > e.getValue().expireAt());
    }
}
