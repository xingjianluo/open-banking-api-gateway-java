package com.github.xingjianluo.openbankingapigatewayjava.idempotency;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.springframework.stereotype.Component;

/**
 * 幂等记录与分段锁的本地存储。
 *
 * <p>使用固定 256 个 stripe 锁（按 key 哈希取模）：同一幂等键永远映射到同一把锁，
 * 保证"检查-插入-完成"临界区对同键串行；不同键通常并行，即使哈希碰撞共用一把锁，
 * 正确性也由临界区内对 records 的二次检查保证，仅损失少量并行度。锁数组定长，
 * 不会因幂等键增长而泄漏内存。生产实现可替换为 Redis SET NX + TTL。</p>
 */
@Component
public class IdempotencyStore {

    private static final int STRIPE_COUNT = 256;

    private final ConcurrentMap<String, IdempotencyRecord> records = new ConcurrentHashMap<>();
    private final Object[] stripes = createStripes();

    private static Object[] createStripes() {
        Object[] locks = new Object[STRIPE_COUNT];
        for (int i = 0; i < locks.length; i++) {
            locks[i] = new Object();
        }
        return locks;
    }

    /** 获取指定键对应的分段锁。 */
    public Object lockFor(String key) {
        int index = (key.hashCode() & 0x7fffffff) % STRIPE_COUNT;
        return stripes[index];
    }

    public IdempotencyRecord get(String key) {
        return records.get(key);
    }

    public IdempotencyRecord putIfAbsent(String key, IdempotencyRecord record) {
        IdempotencyRecord existing = records.putIfAbsent(key, record);
        return existing;
    }

    public IdempotencyRecord remove(String key) {
        return records.remove(key);
    }
}
