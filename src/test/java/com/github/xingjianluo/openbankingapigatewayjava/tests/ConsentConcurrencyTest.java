package com.github.xingjianluo.openbankingapigatewayjava.tests;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.xingjianluo.openbankingapigatewayjava.consent.ConsentAuthorizationService;
import com.github.xingjianluo.openbankingapigatewayjava.consent.InMemoryConsentRepository;
import com.github.xingjianluo.openbankingapigatewayjava.error.ApiException;
import com.github.xingjianluo.openbankingapigatewayjava.error.ErrorCode;
import com.github.xingjianluo.openbankingapigatewayjava.model.consent.Consent;
import com.github.xingjianluo.openbankingapigatewayjava.model.consent.ConsentScope;
import com.github.xingjianluo.openbankingapigatewayjava.model.consent.ConsentStatus;
import com.github.xingjianluo.openbankingapigatewayjava.model.partner.Partner;
import com.github.xingjianluo.openbankingapigatewayjava.partner.InMemoryPartnerRepository;
import java.time.OffsetDateTime;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
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

/**
 * 并发一致性测试：在大量访问线程进行授权判定的同时，另一线程反复切换同意状态。
 * 每个访问线程的结论都必须是“可解释的确定结果”：
 * 要么放行成功，要么以 REVOKED/EXPIRED/SCOPE 等明确原因拒绝，
 * 不允许出现空指针/未知错误等中途不一致。
 */
class ConsentConcurrencyTest {

    private static final Logger log = LoggerFactory.getLogger(ConsentConcurrencyTest.class);

    @Test
    @DisplayName("并发撤销/恢复期间，所有授权结论均一致且可解释，无半成品结果")
    void concurrentAccess_duringStatusFlip_allOutcomesInterpretable() throws Exception {
        InMemoryPartnerRepository partners = new InMemoryPartnerRepository();
        InMemoryConsentRepository consents = new InMemoryConsentRepository();
        partners.save(new Partner("p", "P", Set.of(ConsentScope.PAYMENTS_INITIATE)));
        Consent consent = new Consent("c-flip", "p", Set.of(ConsentScope.PAYMENTS_INITIATE),
                ConsentStatus.AUTHORISED, OffsetDateTime.now().plusHours(1));
        consents.save(consent);
        ConsentAuthorizationService auth = new ConsentAuthorizationService(partners, consents);

        int readers = 16;
        int iterations = 200;
        ExecutorService pool = Executors.newFixedThreadPool(readers + 1);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger allowed = new AtomicInteger();
        Map<ErrorCode, AtomicInteger> rejected = new ConcurrentHashMap<>();
        AtomicInteger weird = new AtomicInteger();
        AtomicInteger interrupted = new AtomicInteger();

        // 一个线程持续在 AUTHORISED / REVOKED 之间翻转
        pool.submit(() -> {
            try {
                start.await();
                for (int i = 0; i < iterations; i++) {
                    consent.setStatus(i % 2 == 0 ? ConsentStatus.AUTHORISED : ConsentStatus.REVOKED);
                    // 短暂让出，确保 AUTHORISED 与 REVOKED 两种状态都与读线程充分交错
                    Thread.sleep(1);
                }
                consent.setStatus(ConsentStatus.REVOKED);
            } catch (InterruptedException e) {
                interrupted.incrementAndGet();
                Thread.currentThread().interrupt();
            }
        });
        // 多个访问线程并发判定
        for (int r = 0; r < readers; r++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < iterations; i++) {
                        try {
                            auth.authorize("p", "c-flip", ConsentScope.PAYMENTS_INITIATE);
                            allowed.incrementAndGet();
                        } catch (ApiException ex) {
                            // 唯一合法的拒绝原因是 REVOKED（翻转只触及 status）
                            rejected.computeIfAbsent(ex.getErrorCode(),
                                    k -> new AtomicInteger()).incrementAndGet();
                        } catch (RuntimeException other) {
                            weird.incrementAndGet();
                        }
                    }
                } catch (InterruptedException e) {
                    interrupted.incrementAndGet();
                    Thread.currentThread().interrupt();
                }
            });
        }

        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        int totalRejections = rejected.values().stream().mapToInt(AtomicInteger::get).sum();
        log.info("[并发一致性] 输入: {} 读线程 x {} 次判定，状态并发翻转 AUTHORISED<->REVOKED", readers, iterations);
        log.info("[并发一致性] 判定分布: 放行={}, 拒绝明细={}, 未预期错误={}, 中断={}",
                allowed.get(), rejectedCounts(rejected), weird.get(), interrupted.get());

        assertTrue(weird.get() == 0, "不得出现非 ApiException 的未预期结果");
        assertTrue(interrupted.get() == 0, "线程不应被中断");
        assertTrue(allowed.get() + totalRejections == readers * iterations,
                "每次判定都必须有确定结论");
        // 拒绝只允许来自 REVOKED；不得出现 NOT_FOUND / SCOPE_INSUFFICIENT 等不可能原因
        rejected.keySet().forEach(code ->
                assertTrue(code == ErrorCode.CONSENT_REVOKED,
                        "并发翻转期间只可能出现 CONSENT_REVOKED，实际出现 " + code));
        assertTrue(allowed.get() > 0 && totalRejections > 0,
                "翻转场景下应同时观察到放行与拒绝两种确定结论");
    }

    private String rejectedCounts(Map<ErrorCode, AtomicInteger> rejected) {
        Map<ErrorCode, AtomicInteger> copy = new EnumMap<>(ErrorCode.class);
        rejected.forEach((k, v) -> copy.put(k, v));
        return copy.toString();
    }
}
