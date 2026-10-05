package com.github.xingjianluo.openbankingapigatewayjava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.xingjianluo.openbankingapigatewayjava.common.ApiException;
import com.github.xingjianluo.openbankingapigatewayjava.common.ErrorCode;
import com.github.xingjianluo.openbankingapigatewayjava.consent.Consent;
import com.github.xingjianluo.openbankingapigatewayjava.consent.PermissionScope;
import com.github.xingjianluo.openbankingapigatewayjava.limit.LimitReservation;
import com.github.xingjianluo.openbankingapigatewayjava.limit.LimitService;

/**
 * 额度判定与累计用量的单元测试。
 *
 * <p>并发用例用发令枪对齐起跑、用确定性断言（成功数、最终余额）收口，
 * 反复运行结论一致；每次判定的输入与依据由 LimitService 日志打印。</p>
 */
class LimitServiceTest {

    private static final Logger log = LoggerFactory.getLogger(LimitServiceTest.class);

    private LimitService limitService;

    @BeforeEach
    void setUp() {
        limitService = new LimitService();
    }

    private Consent capped(String id, String perPayment, String cumulative, String currency) {
        Instant now = Instant.now();
        return Consent.authorisedWithLimits(id, "partner-a",
                Set.of(PermissionScope.PAYMENTS_INITIATE), now, now.plus(1, ChronoUnit.HOURS),
                perPayment == null ? null : new BigDecimal(perPayment),
                cumulative == null ? null : new BigDecimal(cumulative),
                currency);
    }

    private Consent unlimited(String id) {
        Instant now = Instant.now();
        return Consent.authorised(id, "partner-a",
                Set.of(PermissionScope.PAYMENTS_INITIATE), now, now.plus(1, ChronoUnit.HOURS));
    }

    @Test
    @DisplayName("未配置额度的同意：任意金额/币种都放行，不跟踪用量（与旧行为一致）")
    void noLimitsKeepsLegacyBehaviour() {
        Consent c = unlimited("c-nolimit");
        LimitReservation r = limitService.reserve(c, new BigDecimal("999999.99"), "USD");
        assertTrue(!r.isTracked(), "未配置累计上限不应跟踪用量");
        assertEquals(0, BigDecimal.ZERO.compareTo(limitService.used("c-nolimit")));
        log.info("[判定依据] 无额度约束的同意不进入用量账本 used={}", limitService.used("c-nolimit"));
    }

    @Test
    @DisplayName("三种额度拒绝原因互相区分：币种不符/单笔超限/累计超限")
    void threeDistinctRejections() {
        Consent c = capped("c-distinct", "100.00", "250.00", "GBP");

        ApiException currency = assertThrows(ApiException.class,
                () -> limitService.reserve(c, new BigDecimal("10"), "EUR"));
        assertEquals(ErrorCode.CURRENCY_MISMATCH, currency.errorCode());

        ApiException perPayment = assertThrows(ApiException.class,
                () -> limitService.reserve(c, new BigDecimal("100.01"), "GBP"));
        assertEquals(ErrorCode.PAYMENT_LIMIT_EXCEEDED, perPayment.errorCode());

        limitService.reserve(c, new BigDecimal("100"), "GBP");
        limitService.reserve(c, new BigDecimal("100"), "GBP");
        ApiException cumulative = assertThrows(ApiException.class,
                () -> limitService.reserve(c, new BigDecimal("60"), "GBP"));
        assertEquals(ErrorCode.CUMULATIVE_LIMIT_EXCEEDED, cumulative.errorCode());

        // 被拒绝的请求不消耗额度：已用仍是 200
        assertEquals(0, new BigDecimal("200").compareTo(limitService.used("c-distinct")));
        log.info("[判定依据] 三种拒绝码={}/{}/{}，拒绝后已用={}",
                currency.errorCode(), perPayment.errorCode(), cumulative.errorCode(),
                limitService.used("c-distinct"));
    }

    @Test
    @DisplayName("边界值：金额恰等于单笔上限/累计上限时放行")
    void boundaryAmountsPass() {
        Consent c = capped("c-boundary", "100.00", "250.00", "GBP");
        limitService.reserve(c, new BigDecimal("100.00"), "GBP");   // 恰等于单笔上限
        limitService.reserve(c, new BigDecimal("100.00"), "GBP");
        limitService.reserve(c, new BigDecimal("50.00"), "GBP");    // 恰好用满累计上限
        assertEquals(0, new BigDecimal("250.00").compareTo(limitService.used("c-boundary")));
        ApiException over = assertThrows(ApiException.class,
                () -> limitService.reserve(c, new BigDecimal("0.01"), "GBP"));
        assertEquals(ErrorCode.CUMULATIVE_LIMIT_EXCEEDED, over.errorCode());
        log.info("[判定依据] 边界放行后用满 250，再占 0.01 被拒");
    }

    @Test
    @DisplayName("预占退回：release 幂等，重复/并发释放只退一次")
    void releaseIsIdempotent() throws Exception {
        Consent c = capped("c-release", "100.00", "250.00", "GBP");
        LimitReservation r = limitService.reserve(c, new BigDecimal("80"), "GBP");
        assertEquals(0, new BigDecimal("80").compareTo(limitService.used("c-release")));

        int n = 8;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch gun = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(n);
        for (int i = 0; i < n; i++) {
            pool.submit(() -> {
                try {
                    gun.await();
                    r.release();  // 并发重复释放同一笔预占
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        gun.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS));
        pool.shutdown();

        assertEquals(0, BigDecimal.ZERO.compareTo(limitService.used("c-release")),
                "并发重复释放只应退回一次，余额归零");
        log.info("[判定依据] 8 线程并发 release 后 used={}", limitService.used("c-release"));
    }

    @Test
    @DisplayName("并发不透支：剩余额度只够 3 笔时，20 并发恰好放行 3 笔，反复运行结论一致")
    void concurrentReserveNeverOverdraws() throws Exception {
        for (int round = 0; round < 10; round++) {
            String consentId = "c-race-" + round;
            Consent c = capped(consentId, "100.00", "250.00", "GBP");
            // 先占 160（两笔），剩余 90，只够 3 笔 30
            limitService.reserve(c, new BigDecimal("100"), "GBP");
            limitService.reserve(c, new BigDecimal("60"), "GBP");

            int n = 20;
            BigDecimal amount = new BigDecimal("30");
            ExecutorService pool = Executors.newFixedThreadPool(n);
            CountDownLatch gun = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(n);
            AtomicInteger accepted = new AtomicInteger();
            AtomicInteger rejected = new AtomicInteger();
            for (int i = 0; i < n; i++) {
                pool.submit(() -> {
                    try {
                        gun.await();
                        limitService.reserve(c, amount, "GBP");
                        accepted.incrementAndGet();
                    } catch (ApiException ex) {
                        assertEquals(ErrorCode.CUMULATIVE_LIMIT_EXCEEDED, ex.errorCode());
                        rejected.incrementAndGet();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            gun.countDown();
            assertTrue(done.await(30, TimeUnit.SECONDS));
            pool.shutdown();

            // 剩余 90，恰好放行 3 笔 30，其余 17 笔全部被拒，余额恰为 250，绝不透支
            log.info("[并发额度] round={} 放行={} 拒绝={} 最终已用={}",
                    round, accepted.get(), rejected.get(), limitService.used(consentId));
            assertEquals(3, accepted.get(), "剩余 90 只应放行 3 笔 30");
            assertEquals(17, rejected.get());
            assertEquals(0, new BigDecimal("250.00").compareTo(limitService.used(consentId)),
                    "累计用量绝不能超过累计上限");
        }
    }

    @Test
    @DisplayName("调额并发：支付结论相对其读到的快照确定；下调后新请求按新上限拒绝")
    void adjustLimitConcurrentWithPayments() throws Exception {
        String consentId = "c-adjust";
        Consent v1 = capped(consentId, "100.00", "100.00", "GBP");

        // 基于旧快照 v1 的判定：占用 100 成功（即使此刻管理侧已下调，结论相对 v1 确定）
        LimitReservation r = limitService.reserve(v1, new BigDecimal("100"), "GBP");
        assertTrue(r.isTracked());

        // 管理侧下调累计上限到 50（低于已用 100）
        Consent v2 = v1.withLimits(new BigDecimal("100.00"), new BigDecimal("50.00"), "GBP");

        // 新请求基于新快照 v2：已用 100 > 新上限 50，任何金额都被拒
        ApiException ex = assertThrows(ApiException.class,
                () -> limitService.reserve(v2, new BigDecimal("1"), "GBP"));
        assertEquals(ErrorCode.CUMULATIVE_LIMIT_EXCEEDED, ex.errorCode());

        // 管理侧再上调到 200：新请求基于新快照可继续占用
        Consent v3 = v2.withLimits(new BigDecimal("100.00"), new BigDecimal("200.00"), "GBP");
        limitService.reserve(v3, new BigDecimal("50"), "GBP");
        assertEquals(0, new BigDecimal("150").compareTo(limitService.used(consentId)));
        log.info("[判定依据] 旧快照占用100 -> 下调至50后新请求被拒 -> 上调至200后占用50，已用={}",
                limitService.used(consentId));
    }

    @Test
    @DisplayName("续期与额度互不影响：withExpiry 快照保留额度字段")
    void extendExpiryKeepsLimits() {
        Consent c = capped("c-expiry", "100.00", "250.00", "GBP");
        Consent extended = c.withExpiry(Instant.now().plus(2, ChronoUnit.HOURS));
        assertEquals(c.perPaymentLimit(), extended.perPaymentLimit());
        assertEquals(c.cumulativeLimit(), extended.cumulativeLimit());
        assertEquals(c.limitCurrency(), extended.limitCurrency());
        assertTrue(extended.expiresAt().isAfter(c.expiresAt()));
        log.info("[判定依据] 续期后额度字段不变 perPayment={} cumulative={} currency={}",
                extended.perPaymentLimit(), extended.cumulativeLimit(), extended.limitCurrency());
    }
}
