package com.github.xingjianluo.openbankingapigatewayjava.idempotency;

import com.github.xingjianluo.openbankingapigatewayjava.error.ApiException;
import com.github.xingjianluo.openbankingapigatewayjava.error.ErrorCode;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 幂等提交与重放防护（本地内存实现）。
 *
 * <ul>
 *   <li>相同幂等键 + 相同请求哈希：只产生一次副作用，并发/重复提交均返回同一确定结果；</li>
 *   <li>相同键 + 不同请求哈希：明确拒绝 IDEMPOTENCY_CONFLICT，不静默覆盖；</li>
 *   <li>幂等键超过 TTL：明确拒绝 IDEMPOTENCY_EXPIRED，禁止复用（不允许在旧键上再执行）；</li>
 *   <li>并发同键：{@code ConcurrentHashMap.compute} 原子认领，仅一个线程执行动作，
 *       其余线程在同一 CompletableFuture 上等待，得到相同结果或相同失败。</li>
 * </ul>
 *
 * <p>终态（成功或失败）结果都会被缓存：首次失败后同键重放返回相同的稳定错误，
 * 不会再次执行副作用。
 */
@Service
public class IdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);

    private final Duration ttl;
    private final ConcurrentHashMap<String, Entry<?>> store = new ConcurrentHashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    public IdempotencyService(@Value("${obg.idempotency.ttl-hours:24}") long ttlHours) {
        this.ttl = Duration.ofHours(ttlHours);
    }

    /** 测试/演示用构造器。 */
    public IdempotencyService(Duration ttl) {
        this.ttl = ttl;
    }

    public <T> IdempotencyOutcome<T> execute(String idempotencyKey, String requestHash, Supplier<T> action) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "缺少 Idempotency-Key 请求头");
        }

        // owner 标记在 compute 内确定，通过线程局部变量从原子块内带出：
        // 只有本线程新建条目时才为 true。
        java.util.concurrent.atomic.AtomicBoolean ownerFlag =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        Entry<?> entry = store.compute(idempotencyKey, (key, existing) -> {
            if (existing != null && existing.isExpired(now())) {
                // 过期键：不是覆盖执行，而是拒绝复用
                throw new ApiException(ErrorCode.IDEMPOTENCY_EXPIRED, "key=" + key);
            }
            if (existing != null && !existing.requestHash().equals(requestHash)) {
                log.warn("幂等判定: key={} 请求哈希不一致 stored={} incoming={} -> IDEMPOTENCY_CONFLICT",
                        key, existing.requestHash(), requestHash);
                throw new ApiException(ErrorCode.IDEMPOTENCY_CONFLICT, "key=" + key);
            }
            if (existing != null) {
                log.info("幂等判定: key={} 命中{}记录，等待同一结果",
                        key, existing.state() == State.COMPLETED ? "终态" : "处理中");
                return existing;
            }
            ownerFlag.set(true);
            log.info("幂等判定: key={} 首次出现，原子认领并准备执行动作 hash={}", key, requestHash);
            return new Entry<>(requestHash, now() + ttl.toMillis(), new CompletableFuture<>());
        });
        boolean owner = ownerFlag.get();

        @SuppressWarnings("unchecked")
        CompletableFuture<T> future = (CompletableFuture<T>) entry.future();
        // 注意：compute 内仅“认领”，动作在 compute 之外执行，避免在 Map 锁内做业务 I/O
        if (owner) {
            try {
                T result = action.get();
                future.complete(result);
                log.info("幂等动作完成: key={} 结果已缓存（唯一一次副作用）", idempotencyKey);
            } catch (RuntimeException ex) {
                future.completeExceptionally(ex);
                log.warn("幂等动作失败: key={} 失败已缓存，重放将得到相同错误", idempotencyKey);
            }
        }
        T result = joinResult(idempotencyKey, future);
        return owner ? IdempotencyOutcome.first(result) : IdempotencyOutcome.replay(result);
    }

    private <T> T joinResult(String key, CompletableFuture<T> future) {
        try {
            return future.get(60, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) {
                if (cause instanceof ApiException api) {
                    log.info("幂等重放传播: key={} 返回首次执行的稳定错误 {}", key, api.getErrorCode().code());
                }
                throw re;
            }
            throw new ApiException(ErrorCode.INTERNAL_ERROR);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(ErrorCode.INTERNAL_ERROR);
        } catch (TimeoutException e) {
            throw new ApiException(ErrorCode.IDEMPOTENCY_IN_PROGRESS, "key=" + key);
        }
    }

    private static long now() {
        return System.currentTimeMillis();
    }

    /** 仅供测试：查看键记录是否存在（含已过期但尚未复用的键）。 */
    public boolean isKeyPresent(String key) {
        return store.containsKey(key);
    }

    private enum State { IN_PROGRESS, COMPLETED }

    private static final class Entry<T> {
        private final String requestHash;
        private final long deadlineMillis;
        private final CompletableFuture<T> future;

        private Entry(String requestHash, long deadlineMillis, CompletableFuture<T> future) {
            this.requestHash = requestHash;
            this.deadlineMillis = deadlineMillis;
            this.future = future;
        }

        String requestHash() {
            return requestHash;
        }

        CompletableFuture<T> future() {
            return future;
        }

        State state() {
            return future.isDone() ? State.COMPLETED : State.IN_PROGRESS;
        }

        boolean isExpired(long nowMillis) {
            return nowMillis >= deadlineMillis;
        }
    }
}
