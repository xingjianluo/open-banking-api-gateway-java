package com.github.xingjianluo.openbankingapigatewayjava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.github.xingjianluo.openbankingapigatewayjava.error.ApiException;
import com.github.xingjianluo.openbankingapigatewayjava.error.ErrorCode;
import com.github.xingjianluo.openbankingapigatewayjava.idempotency.IdempotencyOutcome;
import com.github.xingjianluo.openbankingapigatewayjava.idempotency.IdempotencyService;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 幂等提交与重放防护测试：
 * 重复/并发同键只产生一次副作用；内容冲突与超期复用被明确拒绝，原因可区分。
 */
class IdempotencyServiceTest {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyServiceTest.class);

    @Test
    @DisplayName("顺序重放：同键同内容，动作只执行一次，第二次标记 replayed=true")
    void sequentialReplay_runsActionOnce() {
        IdempotencyService service = new IdempotencyService(Duration.ofHours(1));
        AtomicInteger sideEffects = new AtomicInteger();

        log.info("[顺序重放] 输入 key=k1 hash=hA，连续提交两次");
        IdempotencyOutcome<String> first = service.execute("k1", "hA", () -> {
            sideEffects.incrementAndGet();
            return "R1";
        });
        IdempotencyOutcome<String> replay = service.execute("k1", "hA", () -> {
            sideEffects.incrementAndGet();
            return "R1-NEW";
        });

        assertTrue(first.firstExecution() && !first.replayed());
        assertTrue(replay.replayed());
        assertEquals("R1", replay.result(), "重放必须返回首次的确定结果");
        assertEquals(1, sideEffects.get(), "副作用只允许发生一次");
        log.info("[顺序重放] 判定依据: 首次 firstExecution=true；第二次命中终态缓存 replayed=true，副作用计数={}",
                sideEffects.get());
    }

    @Test
    @DisplayName("并发同键同内容：仅一个线程执行副作用，所有线程得到相同结果")
    void concurrentSameKey_singleSideEffect() throws Exception {
        IdempotencyService service = new IdempotencyService(Duration.ofMinutes(5));
        AtomicInteger sideEffects = new AtomicInteger();
        int threads = 24;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<IdempotencyOutcome<String>>> futures = new ArrayList<>();

        log.info("[并发重复提交] 输入 key=k-concurrent hash=hA，线程数={}", threads);
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit((java.util.concurrent.Callable<IdempotencyOutcome<String>>) () -> {
                ready.countDown();
                start.await();
                return service.execute("k-concurrent", "hA", () -> {
                    sideEffects.incrementAndGet();
                    try {
                        Thread.sleep(50); // 放大并发窗口
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException(ie);
                    }
                    return "SAME-RESULT";
                });
            }));
        }
        ready.await();
        start.countDown();

        Set<String> results = ConcurrentHashMap.newKeySet();
        long firstCount = 0;
        long replayCount = 0;
        for (Future<IdempotencyOutcome<String>> f : futures) {
            IdempotencyOutcome<String> o = f.get(10, TimeUnit.SECONDS);
            results.add(o.result());
            if (o.firstExecution()) {
                firstCount++;
            }
            if (o.replayed()) {
                replayCount++;
            }
        }
        pool.shutdown();

        assertEquals(Set.of("SAME-RESULT"), results, "所有并发线程必须得到同一确定结果");
        assertEquals(1, sideEffects.get(), "并发下副作用严格只允许一次");
        assertEquals(1, firstCount, "恰好一个线程为首执行");
        assertEquals(threads - 1, replayCount, "其余线程全部为重放");
        log.info("[并发重复提交] 判定依据: 副作用=1, first=1, replay={}, 结果集合大小={}",
                replayCount, results.size());
    }

    @Test
    @DisplayName("同键不同内容 -> IDEMPOTENCY_CONFLICT，不覆盖、不重复执行")
    void sameKeyDifferentPayload_conflict() {
        IdempotencyService service = new IdempotencyService(Duration.ofHours(1));
        AtomicInteger sideEffects = new AtomicInteger();
        log.info("[冲突] 输入 key=k2 首次 hash=hA，随后 hash=hB");
        service.execute("k2", "hA", () -> {
            sideEffects.incrementAndGet();
            return "R2";
        });
        ApiException ex = assertThrows(ApiException.class,
                () -> service.execute("k2", "hB", () -> {
                    sideEffects.incrementAndGet();
                    return "R2-CHANGED";
                }));
        assertEquals(ErrorCode.IDEMPOTENCY_CONFLICT, ex.getErrorCode());
        assertEquals(1, sideEffects.get(), "冲突请求不得产生额外副作用");
        log.info("[冲突] 判定依据: 存储哈希 hA != 入参哈希 hB -> {}", ex.getErrorCode().code());
    }

    @Test
    @DisplayName("幂等键超期复用 -> IDEMPOTENCY_EXPIRED")
    void expiredKey_rejected() throws Exception {
        IdempotencyService service = new IdempotencyService(Duration.ofMillis(60));
        AtomicInteger sideEffects = new AtomicInteger();
        log.info("[超期] 输入 key=k3 hash=hA，TTL=60ms，先成功提交");
        service.execute("k3", "hA", () -> {
            sideEffects.incrementAndGet();
            return "R3";
        });
        Thread.sleep(80);
        log.info("[超期] 超期后复用 key=k3 hash=hA（内容一致也必须拒绝）");
        ApiException ex = assertThrows(ApiException.class,
                () -> service.execute("k3", "hA", () -> {
                    sideEffects.incrementAndGet();
                    return "R3-REUSE";
                }));
        assertEquals(ErrorCode.IDEMPOTENCY_EXPIRED, ex.getErrorCode());
        assertEquals(1, sideEffects.get(), "超期复用不得再次执行");
        log.info("[超期] 判定依据: 当前时间 >= 键截止时间 -> {}", ex.getErrorCode().code());
    }

    @Test
    @DisplayName("首次动作失败：失败被缓存，同键重放返回相同稳定错误且不再执行")
    void failureCached_replayGivesSameError() {
        IdempotencyService service = new IdempotencyService(Duration.ofHours(1));
        AtomicInteger attempts = new AtomicInteger();
        log.info("[失败缓存] 输入 key=k4 hash=hA，动作首次抛出 DOWNSTREAM_FAILURE");
        Runnable invocation = () -> service.execute("k4", "hA", () -> {
            attempts.incrementAndGet();
            throw new ApiException(ErrorCode.DOWNSTREAM_FAILURE);
        });
        ApiException first = assertThrows(ApiException.class, invocation::run);
        ApiException second = assertThrows(ApiException.class, invocation::run);
        assertEquals(ErrorCode.DOWNSTREAM_FAILURE, first.getErrorCode());
        assertEquals(ErrorCode.DOWNSTREAM_FAILURE, second.getErrorCode());
        assertEquals(1, attempts.get(), "重放失败不得再次触发动作");
        log.info("[失败缓存] 判定依据: 两次均为 DOWNSTREAM_FAILURE，动作仅执行 {} 次", attempts.get());
    }

    @Test
    @DisplayName("不同幂等键互不影响，各自执行一次")
    void differentKeys_isolated() {
        IdempotencyService service = new IdempotencyService(Duration.ofHours(1));
        AtomicInteger sideEffects = new AtomicInteger();
        log.info("[键隔离] 输入 key=ka/kb hash=hA");
        service.execute("ka", "hA", () -> {
            sideEffects.incrementAndGet();
            return "A";
        });
        service.execute("kb", "hA", () -> {
            sideEffects.incrementAndGet();
            return "B";
        });
        assertEquals(2, sideEffects.get());
        assertFalse(service.isKeyPresent("kc"));
        assertTrue(service.isKeyPresent("ka"));
        log.info("[键隔离] 判定依据: 不同键各自一次，副作用计数=2");
    }
}
