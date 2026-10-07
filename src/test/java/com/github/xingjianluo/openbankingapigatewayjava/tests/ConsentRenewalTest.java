package com.github.xingjianluo.openbankingapigatewayjava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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
import com.github.xingjianluo.openbankingapigatewayjava.consent.ConsentRepository;
import com.github.xingjianluo.openbankingapigatewayjava.consent.ConsentService;
import com.github.xingjianluo.openbankingapigatewayjava.consent.PermissionScope;
import com.github.xingjianluo.openbankingapigatewayjava.limit.LimitService;
import com.github.xingjianluo.openbankingapigatewayjava.partner.Partner;

/**
 * 续期语义单元测试：只能延长不能缩短、拒绝时状态原样不变、过期可恢复、
 * 撤销不可回退、续期与授权并发只有确定结论。
 *
 * <p>每个用例都在日志中打印"请求输入 + 判定依据"，反复运行结论稳定。</p>
 */
class ConsentRenewalTest {

    private static final Logger log = LoggerFactory.getLogger(ConsentRenewalTest.class);

    private ConsentRepository repository;
    private ConsentService service;
    private LimitService limitService;
    private Partner partner;

    @BeforeEach
    void setUp() {
        repository = new ConsentRepository();
        service = new ConsentService(repository);
        limitService = new LimitService();
        partner = new Partner("partner-a", "A",
                Set.of(PermissionScope.PAYMENTS_INITIATE, PermissionScope.PAYMENTS_READ));
    }

    private Consent saveWithLimits(String consentId, Instant createdAt, Instant expiresAt) {
        Consent consent = Consent.authorisedWithLimits(consentId, "partner-a",
                Set.of(PermissionScope.PAYMENTS_INITIATE), createdAt, expiresAt,
                new BigDecimal("100.00"), new BigDecimal("250.00"), "GBP");
        repository.save(consent);
        return consent;
    }

    @Test
    @DisplayName("续期传入更短时长：拒绝 CONSENT_EXTENSION_INVALID，有效期/额度/用量原样不变")
    void shorterExtensionRejectedAndStateUntouched() {
        Instant now = Instant.now();
        Consent original = saveWithLimits("c-renew", now, now.plus(1, ChronoUnit.DAYS));
        // 先累计一笔用量，验证拒绝续期不会动用量
        limitService.reserve(original, new BigDecimal("40.00"), "GBP");

        Instant shorter = now.plus(1, ChronoUnit.HOURS); // 比当前截止(1天)短得多
        log.info("[用例输入] consentId=c-renew 当前expiresAt={} 续期目标={}（更短）", original.expiresAt(), shorter);
        ApiException ex = assertThrows(ApiException.class,
                () -> service.extendExpiry("c-renew", shorter));
        assertEquals(ErrorCode.CONSENT_EXTENSION_INVALID, ex.errorCode());
        log.info("[判定依据] errorCode={} reason={}", ex.errorCode(), ex.reason());

        Consent after = repository.findById("c-renew").orElseThrow();
        assertEquals(original.expiresAt(), after.expiresAt(), "被拒绝的续期不得改变有效期");
        assertEquals(original.perPaymentLimit(), after.perPaymentLimit(), "额度配置不得改变");
        assertEquals(original.cumulativeLimit(), after.cumulativeLimit(), "额度配置不得改变");
        assertEquals(original.limitCurrency(), after.limitCurrency(), "额度配置不得改变");
        assertEquals(0, new BigDecimal("40.00").compareTo(limitService.used("c-renew")),
                "已累计用量不得改变");
        log.info("[判定依据] 拒绝后 expiresAt={} 额度=({}/{}/{}) 已用={} 均与续期前一致",
                after.expiresAt(), after.perPaymentLimit(), after.cumulativeLimit(),
                after.limitCurrency(), limitService.used("c-renew"));
    }

    @Test
    @DisplayName("续期目标正好等于当前截止时间：同样拒绝 CONSENT_EXTENSION_INVALID")
    void equalExtensionRejected() {
        Instant now = Instant.now();
        Consent original = saveWithLimits("c-equal", now, now.plus(1, ChronoUnit.DAYS));
        log.info("[用例输入] consentId=c-equal 当前expiresAt={} 续期目标={}（相等）",
                original.expiresAt(), original.expiresAt());
        ApiException ex = assertThrows(ApiException.class,
                () -> service.extendExpiry("c-equal", original.expiresAt()));
        assertEquals(ErrorCode.CONSENT_EXTENSION_INVALID, ex.errorCode());
        assertEquals(original.expiresAt(), repository.findById("c-equal").orElseThrow().expiresAt());
        log.info("[判定依据] errorCode={} 有效期保持={}", ex.errorCode(), original.expiresAt());
    }

    @Test
    @DisplayName("续期拒绝原因与其它失败原因可区分")
    void rejectionReasonDistinct() {
        Instant now = Instant.now();
        saveWithLimits("c-distinct", now, now.plus(1, ChronoUnit.DAYS));
        ErrorCode shorter = assertThrows(ApiException.class,
                () -> service.extendExpiry("c-distinct", now.plus(1, ChronoUnit.MINUTES))).errorCode();
        ErrorCode missing = assertThrows(ApiException.class,
                () -> service.extendExpiry("c-absent", now.plus(2, ChronoUnit.DAYS))).errorCode();
        log.info("[判定依据] 缩短续期={} 不存在={}（必须不同）", shorter, missing);
        assertEquals(ErrorCode.CONSENT_EXTENSION_INVALID, shorter);
        assertEquals(ErrorCode.CONSENT_NOT_FOUND, missing);
        assertTrue(shorter != missing, "续期缩短必须与其它失败原因可区分");
    }

    @Test
    @DisplayName("已过期同意续期到未来：恢复正常使用，额度与用量不变")
    void expiredConsentRenewedRecovers() {
        Instant now = Instant.now();
        Consent expired = saveWithLimits("c-expired", now.minus(2, ChronoUnit.DAYS),
                now.minus(1, ChronoUnit.HOURS));
        limitService.reserve(expired, new BigDecimal("30.00"), "GBP");

        // 过期时授权拒绝
        ApiException before = assertThrows(ApiException.class,
                () -> service.authorize(partner, "c-expired", PermissionScope.PAYMENTS_INITIATE));
        assertEquals(ErrorCode.CONSENT_EXPIRED, before.errorCode());

        Instant future = now.plus(1, ChronoUnit.DAYS);
        log.info("[用例输入] consentId=c-expired 当前expiresAt={}（已过期） 续期目标={}",
                expired.expiresAt(), future);
        Consent renewed = service.extendExpiry("c-expired", future);
        assertEquals(future, renewed.expiresAt());

        // 续期后授权恢复通过
        Consent authorised = service.authorize(partner, "c-expired", PermissionScope.PAYMENTS_INITIATE);
        assertEquals(future, authorised.expiresAt());
        assertEquals(expired.perPaymentLimit(), renewed.perPaymentLimit(), "额度配置不变");
        assertEquals(expired.cumulativeLimit(), renewed.cumulativeLimit(), "额度配置不变");
        assertEquals(0, new BigDecimal("30.00").compareTo(limitService.used("c-expired")),
                "续期不得重置已累计用量");
        log.info("[判定依据] 续期后授权通过 expiresAt={} 额度=({}/{}/{}) 已用={}",
                renewed.expiresAt(), renewed.perPaymentLimit(), renewed.cumulativeLimit(),
                renewed.limitCurrency(), limitService.used("c-expired"));
    }

    @Test
    @DisplayName("已撤销同意续期：拒绝 CONSENT_REVOKED，撤销终态不可通过续期回退")
    void revokedConsentRenewalRejected() {
        Instant now = Instant.now();
        Consent revoked = saveWithLimits("c-revoked", now, now.plus(1, ChronoUnit.DAYS))
                .asRevoked(now.minusSeconds(5));
        repository.save(revoked);

        log.info("[用例输入] consentId=c-revoked revokedAt={} 续期目标={}",
                revoked.revokedAt(), now.plus(2, ChronoUnit.DAYS));
        ApiException ex = assertThrows(ApiException.class,
                () -> service.extendExpiry("c-revoked", now.plus(2, ChronoUnit.DAYS)));
        assertEquals(ErrorCode.CONSENT_REVOKED, ex.errorCode());

        Consent after = repository.findById("c-revoked").orElseThrow();
        assertEquals(revoked.expiresAt(), after.expiresAt(), "被拒绝的续期不得改变有效期");
        // 续期尝试后授权结论依然是 REVOKED，不会变回可用
        ApiException auth = assertThrows(ApiException.class,
                () -> service.authorize(partner, "c-revoked", PermissionScope.PAYMENTS_INITIATE));
        assertEquals(ErrorCode.CONSENT_REVOKED, auth.errorCode());
        log.info("[判定依据] 续期拒绝 errorCode={}，之后授权仍为 {}，状态未回退",
                ex.errorCode(), auth.errorCode());
    }

    @Test
    @DisplayName("续期与授权并发：授权结论只有 PASS 或 CONSENT_EXPIRED，无第三种结果")
    void concurrentRenewalAndAuthorize() throws Exception {
        Instant now = Instant.now();
        // 已过期同意：续期前授权必拒 EXPIRED，续期后必放行
        saveWithLimits("c-race", now.minus(2, ChronoUnit.DAYS), now.minus(1, ChronoUnit.MINUTES));

        int threads = 16;
        int perThread = 200;
        log.info("[用例输入] 并发：{} 个授权线程各 {} 次 + 1 个续期线程(延到未来)，同一 consent=c-race",
                threads, perThread);
        ExecutorService pool = Executors.newFixedThreadPool(threads + 1);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads + 1);
        ConcurrentHashMap<String, AtomicInteger> verdicts = new ConcurrentHashMap<>();
        verdicts.put("PASS", new AtomicInteger());
        verdicts.put("EXPIRED", new AtomicInteger());
        verdicts.put("OTHER", new AtomicInteger());

        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        try {
                            service.authorize(partner, "c-race", PermissionScope.PAYMENTS_INITIATE);
                            verdicts.get("PASS").incrementAndGet();
                        } catch (ApiException ex) {
                            String key = ex.errorCode() == ErrorCode.CONSENT_EXPIRED ? "EXPIRED" : "OTHER";
                            verdicts.get(key).incrementAndGet();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        pool.submit(() -> {
            try {
                start.await();
                // 等至少一笔授权按过期拒绝后再续期，保证"续期前拒绝/续期后放行"两种结论
                // 都必然出现，避免续期线程跑赢全部授权线程导致结论退化（反复运行不稳定）
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (verdicts.get("EXPIRED").get() == 0 && System.nanoTime() < deadline) {
                    Thread.yield();
                }
                service.extendExpiry("c-race", Instant.now().plus(1, ChronoUnit.DAYS));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                done.countDown();
            }
        });

        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS));
        pool.shutdown();

        int pass = verdicts.get("PASS").get();
        int expired = verdicts.get("EXPIRED").get();
        int other = verdicts.get("OTHER").get();
        log.info("[判定依据] PASS={} EXPIRED={} OTHER={} 总计={}",
                pass, expired, other, threads * perThread);
        assertEquals(0, other, "续期与授权并发期间不允许出现第三种结论");
        assertEquals(threads * perThread, pass + expired);
        assertTrue(pass > 0, "续期后的授权应成功");
        assertTrue(expired > 0, "续期前的授权应按过期拒绝");

        // 续期完成后：授权确定放行，有效期为续期后的值
        Consent finalConsent = service.authorize(partner, "c-race", PermissionScope.PAYMENTS_INITIATE);
        assertTrue(finalConsent.expiresAt().isAfter(Instant.now()));
        log.info("[判定依据] 并发收口后 expiresAt={}，授权确定通过", finalConsent.expiresAt());
    }
}
