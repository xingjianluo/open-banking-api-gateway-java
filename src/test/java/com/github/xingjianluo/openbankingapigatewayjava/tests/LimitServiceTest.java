package com.github.xingjianluo.openbankingapigatewayjava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
 * 额度判定与累计用量台账的单元测试。
 * 每个用例在日志中打印请求输入与判定依据，便于从测试日志还原结论。
 */
class LimitServiceTest {

    private static final Logger log = LoggerFactory.getLogger(LimitServiceTest.class);

    private LimitService limitService;

    @BeforeEach
    void setUp() {
        limitService = new LimitService();
    }

    private Consent limitedConsent(String id, String perPayment, String total, String currency) {
        Instant now = Instant.now();
        return Consent.authorised(id, "partner-a",
                        Set.of(PermissionScope.PAYMENTS_INITIATE), now, now.plus(1, ChronoUnit.HOURS))
                .withLimits(new BigDecimal(perPayment), new BigDecimal(total), currency);
    }

    private Consent unlimitedConsent(String id) {
        Instant now = Instant.now();
        return Consent.authorised(id, "partner-a",
                Set.of(PermissionScope.PAYMENTS_INITIATE), now, now.plus(1, ChronoUnit.HOURS));
    }

    @Test
    @DisplayName("未配置额度的老同意：不做任何额度判定，返回 null 预占")
    void noLimitsSkipsChecks() {
        Consent consent = unlimitedConsent("c-nolimit");
        LimitReservation r = limitService.reserve(consent, new BigDecimal("999999.99"), "USD");
        log.info("[判定依据] 老同意无额度字段，任意金额/币种均放行 reservation={}", r);
        assertNull(r);
        assertEquals(0, limitService.usedAmount("c-nolimit").compareTo(BigDecimal.ZERO));
    }

    @Test
    @DisplayName("三种额度拒绝原因互相区分：币种不符/超单笔/超累计")
    void threeDistinctRejectReasons() {
        Consent consent = limitedConsent("c-distinct", "100.00", "250.00", "GBP");

        ApiException currency = assertThrows(ApiException.class,
                () -> limitService.reserve(consent, new BigDecimal("10.00"), "EUR"));
        log.info("[判定依据] 币种不符 code={} reason={}", currency.errorCode(), currency.reason());
        assertEquals(ErrorCode.CONSENT_CURRENCY_MISMATCH, currency.errorCode());

        ApiException perPayment = assertThrows(ApiException.class,
                () -> limitService.reserve(consent, new BigDecimal("100.01"), "GBP"));
        log.info("[判定依据] 超单笔上限 code={} reason={}", perPayment.errorCode(), perPayment.reason());
        assertEquals(ErrorCode.LIMIT_PER_PAYMENT_EXCEEDED, perPayment.errorCode());

        // 先占 200，再占 100 -> 300 > 250 超累计
        limitService.reserve(consent, new BigDecimal("100.00"), "GBP");
        limitService.reserve(consent, new BigDecimal("100.00"), "GBP");
        ApiException total = assertThrows(ApiException.class,
                () -> limitService.reserve(consent, new BigDecimal("100.00"), "GBP"));
        log.info("[判定依据] 超累计上限 code={} reason={} used={}", total.errorCode(), total.reason(),
                limitService.usedAmount("c-distinct"));
        assertEquals(ErrorCode.LIMIT_TOTAL_EXCEEDED, total.errorCode());
        assertEquals(0, limitService.usedAmount("c-distinct").compareTo(new BigDecimal("200.00")),
                "被拒绝的预占不得累计用量");
    }

    @Test
    @DisplayName("边界：金额等于单笔上限、累计恰好达到上限均放行")
    void boundaryInclusive() {
        Consent consent = limitedConsent("c-boundary", "100.00", "200.00", "GBP");
        limitService.reserve(consent, new BigDecimal("100.00"), "GBP");
        limitService.reserve(consent, new BigDecimal("100.00"), "GBP");
        log.info("[判定依据] 累计恰好到达上限 used={}", limitService.usedAmount("c-boundary"));
        assertEquals(0, limitService.usedAmount("c-boundary").compareTo(new BigDecimal("200.00")));
        ApiException over = assertThrows(ApiException.class,
                () -> limitService.reserve(consent, new BigDecimal("0.01"), "GBP"));
        assertEquals(ErrorCode.LIMIT_TOTAL_EXCEEDED, over.errorCode());
    }

    @Test
    @DisplayName("归还预占：副作用未发生时全额归还，用量回到原值")
    void releaseRestoresUsage() {
        Consent consent = limitedConsent("c-release", "100.00", "100.00", "GBP");
        LimitReservation r = limitService.reserve(consent, new BigDecimal("80.00"), "GBP");
        assertEquals(0, limitService.usedAmount("c-release").compareTo(new BigDecimal("80.00")));
        limitService.release(r);
        log.info("[判定依据] 归还后 used={}", limitService.usedAmount("c-release"));
        assertEquals(0, limitService.usedAmount("c-release").compareTo(BigDecimal.ZERO));
        // 归还后额度恢复，同额可再次预占
        limitService.reserve(consent, new BigDecimal("80.00"), "GBP");
    }

    @Test
    @DisplayName("并发预占不透支：总额度100、单笔30、10线程并发 -> 恰好3笔成功，用量=90")
    void concurrentReserveNeverOverdraws() throws Exception {
        Consent consent = limitedConsent("c-hot", "30.00", "100.00", "GBP");
        int threads = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startGun = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    startGun.await();
                    limitService.reserve(consent, new BigDecimal("30.00"), "GBP");
                    succeeded.incrementAndGet();
                    log.info("[并发预占] 线程{} 成功", idx);
                } catch (ApiException ex) {
                    rejected.incrementAndGet();
                    log.info("[并发预占] 线程{} 拒绝 code={}", idx, ex.errorCode());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        startGun.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS));
        pool.shutdown();

        log.info("[判定依据] 成功={} 拒绝={} 最终用量={}", succeeded.get(), rejected.get(),
                limitService.usedAmount("c-hot"));
        assertEquals(3, succeeded.get(), "100/30 只够 3 笔，并发也只能放行 3 笔");
        assertEquals(threads - 3, rejected.get());
        assertEquals(0, limitService.usedAmount("c-hot").compareTo(new BigDecimal("90.00")),
                "累计用量必须等于成功预占之和，绝不透支");
    }
}
