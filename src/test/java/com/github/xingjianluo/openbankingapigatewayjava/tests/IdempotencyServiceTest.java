package com.github.xingjianluo.openbankingapigatewayjava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.xingjianluo.openbankingapigatewayjava.common.ApiException;
import com.github.xingjianluo.openbankingapigatewayjava.common.ErrorCode;
import com.github.xingjianluo.openbankingapigatewayjava.idempotency.IdempotencyDecision;
import com.github.xingjianluo.openbankingapigatewayjava.idempotency.IdempotencyOutcome;
import com.github.xingjianluo.openbankingapigatewayjava.idempotency.IdempotencyService;
import com.github.xingjianluo.openbankingapigatewayjava.idempotency.IdempotencyStatus;
import com.github.xingjianluo.openbankingapigatewayjava.idempotency.IdempotencyStore;

/**
 * 幂等服务单元测试。请求输入（键/合作方/指纹）与判定依据均打印到日志。
 */
class IdempotencyServiceTest {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyServiceTest.class);

    private IdempotencyService newService(long retentionSeconds) {
        return new IdempotencyService(new IdempotencyStore(), retentionSeconds);
    }

    @Test
    @DisplayName("首次提交 PROCEED；同键同内容第二次 REPLAY，响应快照确定")
    void firstProceedThenReplay() {
        IdempotencyService svc = newService(3600);
        log.info("[用例输入] key=k1 partner=p1 bodyFingerprint=sha-a，连续两次 begin");
        IdempotencyDecision first = svc.begin("k1", "p1", "sha-a");
        assertEquals(IdempotencyOutcome.PROCEED, first.outcome());
        svc.complete(first.record(), 201, "RESP-1");

        IdempotencyDecision second = svc.begin("k1", "p1", "sha-a");
        log.info("[判定依据] 第二次结论={} 缓存状态={} 缓存状态码={} 缓存体={}",
                second.outcome(), second.record().status(),
                second.record().responseStatus(), second.record().responseBody());
        assertEquals(IdempotencyOutcome.REPLAY, second.outcome());
        assertEquals(201, second.record().responseStatus());
        assertEquals("RESP-1", second.record().responseBody());
        assertEquals(IdempotencyStatus.COMPLETED, second.record().status());
    }

    @Test
    @DisplayName("同键但请求内容不一致：IDEMPOTENCY_CONFLICT，原记录不被覆盖")
    void sameKeyDifferentBodyConflicts() {
        IdempotencyService svc = newService(3600);
        log.info("[用例输入] key=k2 partner=p1 首次指纹=sha-a，冲突指纹=sha-b");
        IdempotencyDecision first = svc.begin("k2", "p1", "sha-a");
        svc.complete(first.record(), 201, "ORIGINAL");

        IdempotencyDecision conflict = svc.begin("k2", "p1", "sha-b");
        assertEquals(IdempotencyOutcome.CONFLICT, conflict.outcome());
        ApiException ex = svc.rejectException(conflict);
        assertEquals(ErrorCode.IDEMPOTENCY_CONFLICT, ex.errorCode());
        log.info("[判定依据] outcome={} errorCode={} reason={}", conflict.outcome(), ex.errorCode(), ex.reason());

        // 原结果仍然可被正确重放，未被静默覆盖
        IdempotencyDecision replay = svc.begin("k2", "p1", "sha-a");
        assertEquals("ORIGINAL", replay.record().responseBody());
    }

    @Test
    @DisplayName("同键被另一合作方使用：IDEMPOTENCY_CONFLICT")
    void sameKeyAcrossPartnersConflicts() {
        IdempotencyService svc = newService(3600);
        log.info("[用例输入] key=k3 首次partner=p1，重放partner=p2，指纹相同");
        svc.begin("k3", "p1", "sha-a");
        IdempotencyDecision d = svc.begin("k3", "p2", "sha-a");
        assertEquals(IdempotencyOutcome.CONFLICT, d.outcome());
        ApiException ex = svc.rejectException(d);
        assertEquals(ErrorCode.IDEMPOTENCY_CONFLICT, ex.errorCode());
        log.info("[判定依据] errorCode={} 不允许跨合作方复用键", ex.errorCode());
    }

    @Test
    @DisplayName("首个请求仍在处理中的并发同键提交：IDEMPOTENCY_IN_FLIGHT")
    void concurrentDuplicateWhileInFlight() {
        IdempotencyService svc = newService(3600);
        log.info("[用例输入] key=k4 partner=p1 指纹=sha-a；首请求不 complete 直接二次提交");
        svc.begin("k4", "p1", "sha-a");
        IdempotencyDecision d = svc.begin("k4", "p1", "sha-a");
        assertEquals(IdempotencyOutcome.IN_FLIGHT, d.outcome());
        ApiException ex = svc.rejectException(d);
        assertEquals(ErrorCode.IDEMPOTENCY_IN_FLIGHT, ex.errorCode());
        log.info("[判定依据] errorCode={} reason={}", ex.errorCode(), ex.reason());
    }

    @Test
    @DisplayName("幂等键超期复用：IDEMPOTENCY_KEY_EXPIRED，拒绝静默覆盖")
    void expiredKeyReuseRejected() throws InterruptedException {
        IdempotencyService svc = newService(1);
        log.info("[用例输入] key=k5 partner=p1 保留期=1s；首次完成后睡眠等待超期再复用");
        IdempotencyDecision first = svc.begin("k5", "p1", "sha-a");
        svc.complete(first.record(), 201, "OLD");
        Thread.sleep(1200);

        IdempotencyDecision d = svc.begin("k5", "p1", "sha-a");
        assertEquals(IdempotencyOutcome.KEY_EXPIRED, d.outcome());
        ApiException ex = svc.rejectException(d);
        assertEquals(ErrorCode.IDEMPOTENCY_KEY_EXPIRED, ex.errorCode());
        log.info("[判定依据] errorCode={} reason={}", ex.errorCode(), ex.reason());
    }

    @Test
    @DisplayName("超期后即使内容相同也不能覆盖；不同内容同样拒绝")
    void expiredKeyDifferentBodyAlsoRejected() throws InterruptedException {
        IdempotencyService svc = newService(1);
        svc.begin("k6", "p1", "sha-a");
        Thread.sleep(1200);
        IdempotencyDecision d = svc.begin("k6", "p1", "sha-b");
        assertEquals(IdempotencyOutcome.KEY_EXPIRED, d.outcome());
        ApiException ex = svc.rejectException(d);
        assertEquals(ErrorCode.IDEMPOTENCY_KEY_EXPIRED, ex.errorCode());
        log.info("[判定依据] errorCode={}（超期复用一律拒绝，不区分内容）", ex.errorCode());
    }

    @Test
    @DisplayName("并发 N 个相同提交：恰好 1 个 PROCEED，其余全部 IN_FLIGHT，无 CONFLICT/REPLAY")
    void onlyOneProceedUnderConcurrency() throws InterruptedException {
        IdempotencyService svc = newService(3600);
        int n = 32;
        log.info("[用例输入] key=k-hot partner=p1 指纹=sha-a，并发线程={}，全部在首请求完成前到达", n);
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(n);
        ConcurrentHashMap<IdempotencyOutcome, AtomicInteger> counts = new ConcurrentHashMap<>();
        for (IdempotencyOutcome o : IdempotencyOutcome.values()) {
            counts.put(o, new AtomicInteger());
        }

        for (int i = 0; i < n; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    IdempotencyDecision d = svc.begin("k-hot", "p1", "sha-a");
                    counts.get(d.outcome()).incrementAndGet();
                } catch (ApiException ex) {
                    // 拒绝结论也来自 decision，这里把异常映射回结论统计
                    IdempotencyOutcome mapped = switch (ex.errorCode()) {
                        case IDEMPOTENCY_IN_FLIGHT -> IdempotencyOutcome.IN_FLIGHT;
                        case IDEMPOTENCY_CONFLICT -> IdempotencyOutcome.CONFLICT;
                        case IDEMPOTENCY_KEY_EXPIRED -> IdempotencyOutcome.KEY_EXPIRED;
                        default -> throw new IllegalStateException(ex);
                    };
                    counts.get(mapped).incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        done.await(30, TimeUnit.SECONDS);
        pool.shutdown();

        log.info("[判定依据] PROCEED={} IN_FLIGHT={} REPLAY={} CONFLICT={} KEY_EXPIRED={}",
                counts.get(IdempotencyOutcome.PROCEED).get(),
                counts.get(IdempotencyOutcome.IN_FLIGHT).get(),
                counts.get(IdempotencyOutcome.REPLAY).get(),
                counts.get(IdempotencyOutcome.CONFLICT).get(),
                counts.get(IdempotencyOutcome.KEY_EXPIRED).get());
        assertEquals(1, counts.get(IdempotencyOutcome.PROCEED).get());
        assertEquals(n - 1, counts.get(IdempotencyOutcome.IN_FLIGHT).get());
        assertEquals(0, counts.get(IdempotencyOutcome.REPLAY).get());
        assertEquals(0, counts.get(IdempotencyOutcome.CONFLICT).get());
    }

    @Test
    @DisplayName("三种幂等拒绝原因彼此可区分")
    void rejectionReasonsDistinct() {
        IdempotencyService svc = newService(3600);
        svc.begin("d1", "p1", "a");
        ErrorCode inFlight = svc.rejectException(svc.begin("d1", "p1", "a")).errorCode();

        svc.begin("d2", "p1", "a");
        ErrorCode conflict = svc.rejectException(svc.begin("d2", "p1", "b")).errorCode();

        log.info("[判定依据] IN_FLIGHT={} CONFLICT={} KEY_EXPIRED 由短保留期用例验证", inFlight, conflict);
        assertEquals(ErrorCode.IDEMPOTENCY_IN_FLIGHT, inFlight);
        assertEquals(ErrorCode.IDEMPOTENCY_CONFLICT, conflict);
        assertNotEquals(inFlight, conflict);
    }
}
