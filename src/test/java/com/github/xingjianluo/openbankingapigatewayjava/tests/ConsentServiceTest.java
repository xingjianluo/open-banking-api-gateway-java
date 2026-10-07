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
                // 等至少一笔授权成功后再撤销，保证"撤销前放行/撤销后拒绝"两种结论都必然出现，
                // 避免撤销线程跑赢全部授权线程导致结论退化为只有一种（反复运行不稳定）
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (verdicts.get("PASS").get() == 0 && System.nanoTime() < deadline) {
                    Thread.yield();
                }
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
        assertTrue(revoked > 0, "应存在撤销后的拒绝");
        assertTrue(pass > 0, "撤销前的授权应全部成功");
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
}
