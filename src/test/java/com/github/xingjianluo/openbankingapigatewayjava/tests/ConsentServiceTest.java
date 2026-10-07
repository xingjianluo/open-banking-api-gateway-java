package com.github.xingjianluo.openbankingapigatewayjava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
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
import com.github.xingjianluo.openbankingapigatewayjava.consent.ConsentStatus;
import com.github.xingjianluo.openbankingapigatewayjava.consent.PermissionScope;
import com.github.xingjianluo.openbankingapigatewayjava.partner.Partner;

/**
 * 同意与访问控制单元测试。
 *
 * <p>每个用例都在日志中打印"请求输入 + 判定依据"；四种拒绝（不存在/过期/撤销/范围不足）
 * 必须得到互不相同的错误码。</p>
 */
class ConsentServiceTest {

    private static final Logger log = LoggerFactory.getLogger(ConsentServiceTest.class);

    private ConsentRepository repository;
    private ConsentService service;
    private Partner partnerA;

    @BeforeEach
    void setUp() {
        repository = new ConsentRepository();
        service = new ConsentService(repository);
        partnerA = new Partner("partner-a", "A",
                Set.of(PermissionScope.PAYMENTS_INITIATE, PermissionScope.PAYMENTS_READ));
        Instant now = Instant.now();
        repository.save(Consent.authorised("c-ok", "partner-a",
                Set.of(PermissionScope.PAYMENTS_INITIATE, PermissionScope.PAYMENTS_READ),
                now, now.plus(1, ChronoUnit.DAYS)));
        repository.save(Consent.authorised("c-expired", "partner-a",
                Set.of(PermissionScope.PAYMENTS_INITIATE),
                now.minus(2, ChronoUnit.DAYS), now.minus(1, ChronoUnit.HOURS)));
        repository.save(Consent.authorised("c-revoked", "partner-a",
                Set.of(PermissionScope.PAYMENTS_INITIATE), now, now.plus(1, ChronoUnit.DAYS))
                .asRevoked(now.minusSeconds(10)));
        repository.save(Consent.authorised("c-readonly-consent", "partner-a",
                Set.of(PermissionScope.PAYMENTS_READ), now, now.plus(1, ChronoUnit.DAYS)));
        repository.save(Consent.authorised("c-other-owner", "partner-b",
                Set.of(PermissionScope.PAYMENTS_INITIATE), now, now.plus(1, ChronoUnit.DAYS)));
    }

    @Test
    @DisplayName("有效同意 + scope 双重满足：放行")
    void validConsentPasses() {
        log.info("[用例输入] partner=partner-a consent=c-ok required=PAYMENTS_INITIATE");
        Consent consent = service.authorize(partnerA, "c-ok", PermissionScope.PAYMENTS_INITIATE);
        assertEquals("c-ok", consent.consentId());
        log.info("[判定依据] 返回同一不可变快照，判定通过");
    }

    @Test
    @DisplayName("同意不存在：CONSENT_NOT_FOUND")
    void missingConsent() {
        log.info("[用例输入] partner=partner-a consent=c-missing required=PAYMENTS_INITIATE");
        ApiException ex = assertThrows(ApiException.class,
                () -> service.authorize(partnerA, "c-missing", PermissionScope.PAYMENTS_INITIATE));
        assertEquals(ErrorCode.CONSENT_NOT_FOUND, ex.errorCode());
        log.info("[判定依据] errorCode={} reason={}", ex.errorCode(), ex.reason());
    }

    @Test
    @DisplayName("同意属于其他合作方：按 CONSENT_NOT_FOUND 处理，不泄露存在性")
    void crossOwnerConsent() {
        log.info("[用例输入] partner=partner-a consent=c-other-owner(归属partner-b) required=PAYMENTS_INITIATE");
        ApiException ex = assertThrows(ApiException.class,
                () -> service.authorize(partnerA, "c-other-owner", PermissionScope.PAYMENTS_INITIATE));
        assertEquals(ErrorCode.CONSENT_NOT_FOUND, ex.errorCode());
        log.info("[判定依据] errorCode={}（跨租户不可见）", ex.errorCode());
    }

    @Test
    @DisplayName("已撤销同意：CONSENT_REVOKED")
    void revokedConsent() {
        log.info("[用例输入] partner=partner-a consent=c-revoked required=PAYMENTS_INITIATE");
        ApiException ex = assertThrows(ApiException.class,
                () -> service.authorize(partnerA, "c-revoked", PermissionScope.PAYMENTS_INITIATE));
        assertEquals(ErrorCode.CONSENT_REVOKED, ex.errorCode());
        log.info("[判定依据] errorCode={} reason={}", ex.errorCode(), ex.reason());
    }

    @Test
    @DisplayName("过期同意：CONSENT_EXPIRED")
    void expiredConsent() {
        log.info("[用例输入] partner=partner-a consent=c-expired required=PAYMENTS_INITIATE");
        ApiException ex = assertThrows(ApiException.class,
                () -> service.authorize(partnerA, "c-expired", PermissionScope.PAYMENTS_INITIATE));
        assertEquals(ErrorCode.CONSENT_EXPIRED, ex.errorCode());
        log.info("[判定依据] errorCode={} reason={}", ex.errorCode(), ex.reason());
    }

    @Test
    @DisplayName("同意 scope 不足：SCOPE_INSUFFICIENT")
    void consentScopeInsufficient() {
        log.info("[用例输入] partner=partner-a(granted=initiate,read) consent=c-readonly-consent(scopes=read) required=PAYMENTS_INITIATE");
        ApiException ex = assertThrows(ApiException.class,
                () -> service.authorize(partnerA, "c-readonly-consent", PermissionScope.PAYMENTS_INITIATE));
        assertEquals(ErrorCode.SCOPE_INSUFFICIENT, ex.errorCode());
        log.info("[判定依据] errorCode={} reason={}", ex.errorCode(), ex.reason());
    }

    @Test
    @DisplayName("合作方授权 scope 不足：同样 SCOPE_INSUFFICIENT")
    void partnerScopeInsufficient() {
        Instant now = Instant.now();
        repository.save(Consent.authorised("c-ro-owned", "partner-ro",
                Set.of(PermissionScope.PAYMENTS_INITIATE, PermissionScope.PAYMENTS_READ),
                now, now.plus(1, ChronoUnit.DAYS)));
        Partner readOnlyPartner = new Partner("partner-ro", "RO", Set.of(PermissionScope.PAYMENTS_READ));
        log.info("[用例输入] partner=partner-ro(granted=read) consent=c-ro-owned(含initiate,read) required=PAYMENTS_INITIATE");
        ApiException ex = assertThrows(ApiException.class,
                () -> service.authorize(readOnlyPartner, "c-ro-owned", PermissionScope.PAYMENTS_INITIATE));
        assertEquals(ErrorCode.SCOPE_INSUFFICIENT, ex.errorCode());
        log.info("[判定依据] errorCode={} reason={}", ex.errorCode(), ex.reason());
    }

    @Test
    @DisplayName("四种拒绝原因互不相同")
    void rejectionReasonsAreDistinct() {
        ErrorCode missing = assertThrows(ApiException.class,
                () -> service.authorize(partnerA, "nope", PermissionScope.PAYMENTS_INITIATE)).errorCode();
        ErrorCode expired = assertThrows(ApiException.class,
                () -> service.authorize(partnerA, "c-expired", PermissionScope.PAYMENTS_INITIATE)).errorCode();
        ErrorCode revoked = assertThrows(ApiException.class,
                () -> service.authorize(partnerA, "c-revoked", PermissionScope.PAYMENTS_INITIATE)).errorCode();
        ErrorCode scope = assertThrows(ApiException.class,
                () -> service.authorize(partnerA, "c-readonly-consent", PermissionScope.PAYMENTS_INITIATE)).errorCode();
        long distinct = Set.of(missing, expired, revoked, scope).size();
        log.info("[判定依据] 拒绝码集合={} 去重后数量={}", Set.of(missing, expired, revoked, scope), distinct);
        assertEquals(4, distinct);
    }

    @Test
    @DisplayName("并发访问 + 并发撤销：授权结论只有 AUTHORISED 或 REVOKED，无中途不一致")
    void concurrentAuthorizeAndRevoke() throws Exception {
        int threads = 16;
        int perThread = 200;
        log.info("[用例输入] 并发：{} 个授权线程各 {} 次 + 1 个撤销线程，同一 consent=c-ok", threads, perThread);

        // 撤销前的确定结论：放行（不依赖线程调度）
        service.authorize(partnerA, "c-ok", PermissionScope.PAYMENTS_INITIATE);

        ExecutorService pool = Executors.newFixedThreadPool(threads + 1);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads + 1);
        ConcurrentHashMap<String, AtomicInteger> verdicts = new ConcurrentHashMap<>();
        verdicts.put("PASS", new AtomicInteger());
        verdicts.put("REVOKED", new AtomicInteger());
        verdicts.put("OTHER", new AtomicInteger());

        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        try {
                            service.authorize(partnerA, "c-ok", PermissionScope.PAYMENTS_INITIATE);
                            verdicts.get("PASS").incrementAndGet();
                        } catch (ApiException ex) {
                            String key = ex.errorCode() == ErrorCode.CONSENT_REVOKED ? "REVOKED" : "OTHER";
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
                service.revoke("c-ok");
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
        int revoked = verdicts.get("REVOKED").get();
        int other = verdicts.get("OTHER").get();
        log.info("[判定依据] PASS={} REVOKED={} OTHER={} 总计={}",
                pass, revoked, other, threads * perThread + 1);
        assertEquals(0, other, "撤销与授权并发期间不允许出现第三种结论");
        assertEquals(threads * perThread, pass + revoked);
        // 注意：PASS/REVOKED 各自的具体计数取决于线程调度，不做断言；
        // 两种结论本身已由并发区前后的顺序调用确定性地各验证一次。

        // 撤销后的确定结论：一律 REVOKED（不依赖线程调度）
        ApiException after = assertThrows(ApiException.class,
                () -> service.authorize(partnerA, "c-ok", PermissionScope.PAYMENTS_INITIATE));
        assertEquals(ErrorCode.CONSENT_REVOKED, after.errorCode());
        log.info("[判定依据] 撤销完成后授权确定拒绝 errorCode={}", after.errorCode());
    }

    @Test
    @DisplayName("撤销操作幂等：重复撤销只有一次生效")
    void revokeIsIdempotent() {
        log.info("[用例输入] 对同一 consent=c-ok 连续撤销两次");
        boolean first = service.revoke("c-ok");
        boolean second = service.revoke("c-ok");
        log.info("[判定依据] first={} second={}（终态不可重复迁移）", first, second);
        assertTrue(first);
        org.junit.jupiter.api.Assertions.assertFalse(second);
        List<Consent> check = List.of(repository.findById("c-ok").orElseThrow());
        assertEquals(1, check.size());
    }

    @Test
    @DisplayName("续期只能延长：更短/持平的新截止时间一律拒绝，且有效期、额度配置全部原样不变")
    void extendShorterOrEqualRejectedAndUnchanged() {
        Instant now = Instant.now();
        Instant originalExpiry = now.plus(1, ChronoUnit.DAYS);
        repository.save(Consent.authorisedWithLimits("c-ext", "partner-a",
                Set.of(PermissionScope.PAYMENTS_INITIATE), now, originalExpiry,
                new java.math.BigDecimal("100.00"), new java.math.BigDecimal("250.00"), "GBP"));

        // 更短：now+1h 远早于当前截止时间 now+1d
        Instant shorter = now.plus(1, ChronoUnit.HOURS);
        log.info("[用例输入] 续期 consent=c-ext newExpiresAt={}（当前 expiresAt={}，更短）", shorter, originalExpiry);
        ApiException shorterEx = assertThrows(ApiException.class,
                () -> service.extendExpiry("c-ext", shorter));
        assertEquals(ErrorCode.CONSENT_EXTENSION_INVALID, shorterEx.errorCode());
        log.info("[判定依据] 更短被拒 errorCode={} reason={}", shorterEx.errorCode(), shorterEx.reason());

        // 持平：恰等于当前截止时间同样拒绝
        log.info("[用例输入] 续期 consent=c-ext newExpiresAt={}（与当前持平）", originalExpiry);
        ApiException equalEx = assertThrows(ApiException.class,
                () -> service.extendExpiry("c-ext", originalExpiry));
        assertEquals(ErrorCode.CONSENT_EXTENSION_INVALID, equalEx.errorCode());
        log.info("[判定依据] 持平被拒 errorCode={} reason={}", equalEx.errorCode(), equalEx.reason());

        // 拒绝后状态原样：有效期、额度配置、状态均未变
        Consent after = repository.findById("c-ext").orElseThrow();
        assertEquals(originalExpiry, after.expiresAt(), "被拒绝的续期不得改动有效期");
        assertEquals(new java.math.BigDecimal("100.00"), after.perPaymentLimit());
        assertEquals(new java.math.BigDecimal("250.00"), after.cumulativeLimit());
        assertEquals("GBP", after.limitCurrency());
        assertEquals(ConsentStatus.AUTHORISED, after.status());
        log.info("[判定依据] 拒绝后快照原样 expiresAt={} limits=({}/{}/{})",
                after.expiresAt(), after.perPaymentLimit(), after.cumulativeLimit(), after.limitCurrency());

        // 拒绝原因与既有失败原因可区分
        assertTrue(shorterEx.errorCode() != ErrorCode.CONSENT_EXPIRED
                && shorterEx.errorCode() != ErrorCode.CONSENT_REVOKED
                && shorterEx.errorCode() != ErrorCode.CONSENT_NOT_FOUND);
    }

    @Test
    @DisplayName("续期只能延长：更晚的截止时间生效，其余字段不变")
    void extendLongerSucceeds() {
        Consent before = repository.findById("c-ok").orElseThrow();
        Instant later = before.expiresAt().plus(2, ChronoUnit.DAYS);
        log.info("[用例输入] 续期 consent=c-ok newExpiresAt={}（当前 expiresAt={}，更晚）", later, before.expiresAt());
        Consent updated = service.extendExpiry("c-ok", later);
        assertEquals(later, updated.expiresAt());
        assertEquals(before.scopes(), updated.scopes());
        assertEquals(before.status(), updated.status());
        assertEquals(later, repository.findById("c-ok").orElseThrow().expiresAt());
        log.info("[判定依据] 续期生效 expiresAt {} -> {}", before.expiresAt(), later);
    }

    @Test
    @DisplayName("已过期同意续期到未来：恢复正常使用，授权重新放行")
    void extendExpiredConsentRestoresUsage() {
        log.info("[用例输入] consent=c-expired 已过期，先验证授权拒绝再续期到未来");
        ApiException expired = assertThrows(ApiException.class,
                () -> service.authorize(partnerA, "c-expired", PermissionScope.PAYMENTS_INITIATE));
        assertEquals(ErrorCode.CONSENT_EXPIRED, expired.errorCode());

        Instant future = Instant.now().plus(1, ChronoUnit.DAYS);
        Consent updated = service.extendExpiry("c-expired", future);
        assertEquals(future, updated.expiresAt());

        Consent authorised = service.authorize(partnerA, "c-expired", PermissionScope.PAYMENTS_INITIATE);
        assertEquals("c-expired", authorised.consentId());
        log.info("[判定依据] 过期同意续期到 {} 后授权恢复放行", future);
    }

    @Test
    @DisplayName("已撤销同意续期：一律 CONSENT_REVOKED，不复活、有效期不变")
    void extendRevokedConsentRejected() {
        Consent before = repository.findById("c-revoked").orElseThrow();
        Instant future = Instant.now().plus(30, ChronoUnit.DAYS);
        log.info("[用例输入] 续期 consent=c-revoked(已撤销) newExpiresAt={}", future);
        ApiException ex = assertThrows(ApiException.class,
                () -> service.extendExpiry("c-revoked", future));
        assertEquals(ErrorCode.CONSENT_REVOKED, ex.errorCode());

        Consent after = repository.findById("c-revoked").orElseThrow();
        assertEquals(before.expiresAt(), after.expiresAt(), "撤销态续期被拒，有效期不得改变");
        assertEquals(ConsentStatus.REVOKED, after.status());
        // 续期尝试后授权结论不变：仍是 REVOKED 而不是恢复可用
        ApiException auth = assertThrows(ApiException.class,
                () -> service.authorize(partnerA, "c-revoked", PermissionScope.PAYMENTS_INITIATE));
        assertEquals(ErrorCode.CONSENT_REVOKED, auth.errorCode());
        log.info("[判定依据] 撤销终态不可被续期复活 errorCode={} expiresAt={} 保持不变",
                ex.errorCode(), after.expiresAt());
    }

    @Test
    @DisplayName("续期不存在的同意：CONSENT_NOT_FOUND")
    void extendMissingConsent() {
        log.info("[用例输入] 续期 consent=c-missing");
        ApiException ex = assertThrows(ApiException.class,
                () -> service.extendExpiry("c-missing", Instant.now().plus(1, ChronoUnit.DAYS)));
        assertEquals(ErrorCode.CONSENT_NOT_FOUND, ex.errorCode());
        log.info("[判定依据] errorCode={}", ex.errorCode());
    }

    @Test
    @DisplayName("并发续期与并发授权：授权结论只有 PASS 或 EXPIRED，续期后快照完整")
    void concurrentExtendAndAuthorize() throws Exception {
        // 构造一个已过期同意：授权线程只会得到 EXPIRED（续期生效前）或 PASS（续期生效后）
        Instant now = Instant.now();
        repository.save(Consent.authorised("c-ext-race", "partner-a",
                Set.of(PermissionScope.PAYMENTS_INITIATE),
                now.minus(2, ChronoUnit.DAYS), now.minusSeconds(5)));

        int threads = 16;
        int perThread = 200;
        log.info("[用例输入] 并发：{} 个授权线程各 {} 次 + 1 个续期线程（续到未来），同一 consent=c-ext-race",
                threads, perThread);

        // 续期前的确定结论：EXPIRED（不依赖线程调度）
        ApiException before = assertThrows(ApiException.class,
                () -> service.authorize(partnerA, "c-ext-race", PermissionScope.PAYMENTS_INITIATE));
        assertEquals(ErrorCode.CONSENT_EXPIRED, before.errorCode());

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
                            service.authorize(partnerA, "c-ext-race", PermissionScope.PAYMENTS_INITIATE);
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
                service.extendExpiry("c-ext-race", Instant.now().plus(1, ChronoUnit.DAYS));
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
        // 注意：PASS/EXPIRED 各自的具体计数取决于线程调度，不做断言；
        // 两种结论本身已由并发区前后的顺序调用确定性地各验证一次。

        // 续期完成后：快照完整且确定放行
        Consent after = repository.findById("c-ext-race").orElseThrow();
        assertEquals(ConsentStatus.AUTHORISED, after.status());
        assertTrue(after.expiresAt().isAfter(Instant.now()));
        service.authorize(partnerA, "c-ext-race", PermissionScope.PAYMENTS_INITIATE);
        log.info("[判定依据] 续期后快照 expiresAt={}，授权确定放行", after.expiresAt());
    }
}
