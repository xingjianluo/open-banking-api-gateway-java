package com.github.xingjianluo.openbankingapigatewayjava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 同意授权边界测试：不存在 / 过期 / 已撤销 / 范围不足 四种拒绝原因必须互不相同，
 * 另覆盖合作方未知与越权归属场景。日志打印请求输入与判定依据。
 */
class ConsentAuthorizationServiceTest {

    private static final Logger log = LoggerFactory.getLogger(ConsentAuthorizationServiceTest.class);

    private InMemoryPartnerRepository partnerRepository;
    private InMemoryConsentRepository consentRepository;
    private ConsentAuthorizationService authService;

    @BeforeEach
    void setUp() {
        partnerRepository = new InMemoryPartnerRepository();
        consentRepository = new InMemoryConsentRepository();
        authService = new ConsentAuthorizationService(partnerRepository, consentRepository);
        partnerRepository.save(new Partner("p-full", "Full TPP",
                Set.of(ConsentScope.PAYMENTS_INITIATE, ConsentScope.PAYMENTS_READ)));
        partnerRepository.save(new Partner("p-readonly", "ReadOnly TPP",
                Set.of(ConsentScope.ACCOUNTS_READ)));
    }

    private void seedConsent(String id, String partnerId, Set<ConsentScope> scopes,
                             ConsentStatus status, OffsetDateTime expiresAt) {
        consentRepository.save(new Consent(id, partnerId, scopes, status, expiresAt));
    }

    private void assertRejected(String caseName, String partnerId, String consentId,
                                ConsentScope required, ErrorCode expected) {
        log.info("[{}] 输入: partnerId={}, consentId={}, requiredScope={}；预期判定: {}",
                caseName, partnerId, consentId, required, expected.code());
        ApiException ex = assertThrows(ApiException.class,
                () -> authService.authorize(partnerId, consentId, required), caseName);
        assertEquals(expected, ex.getErrorCode(),
                () -> caseName + " 应得到 " + expected.code() + " 实际 " + ex.getErrorCode().code());
        log.info("[{}] 判定依据确认 -> {}", caseName, ex.getErrorCode().code());
    }

    @Test
    @DisplayName("授权通过：合作方与同意均含所需范围且在有效期内")
    void authorised_whenAllConditionsMet() {
        log.info("[通过用例] 输入 partnerId=p-full consent=c-ok scope=PAYMENTS_INITIATE");
        seedConsent("c-ok", "p-full", Set.of(ConsentScope.PAYMENTS_INITIATE),
                ConsentStatus.AUTHORISED, OffsetDateTime.now().plusHours(1));
        Consent consent = authService.authorize("p-full", "c-ok", ConsentScope.PAYMENTS_INITIATE);
        assertEquals("c-ok", consent.getConsentId());
        log.info("[通过用例] 判定依据：状态 AUTHORISED + 未过期 + 双方均含范围 -> 放行");
    }

    @Test
    @DisplayName("拒绝原因 1：同意不存在 -> CONSENT_NOT_FOUND")
    void reject_whenConsentMissing() {
        assertRejected("同意不存在", "p-full", "c-missing",
                ConsentScope.PAYMENTS_INITIATE, ErrorCode.CONSENT_NOT_FOUND);
    }

    @Test
    @DisplayName("拒绝原因 1b：同意属于其他合作方 -> CONSENT_NOT_FOUND（不泄露存在性）")
    void reject_whenConsentOwnedByOtherPartner() {
        seedConsent("c-other", "p-readonly", Set.of(ConsentScope.PAYMENTS_INITIATE),
                ConsentStatus.AUTHORISED, OffsetDateTime.now().plusHours(1));
        assertRejected("同意归属他人", "p-full", "c-other",
                ConsentScope.PAYMENTS_INITIATE, ErrorCode.CONSENT_NOT_FOUND);
    }

    @Test
    @DisplayName("拒绝原因 2：同意已过期（按到期时间）-> CONSENT_EXPIRED")
    void reject_whenTimeExpired() {
        seedConsent("c-expired-time", "p-full", Set.of(ConsentScope.PAYMENTS_INITIATE),
                ConsentStatus.AUTHORISED, OffsetDateTime.now().minusMinutes(1));
        assertRejected("到期时间已过", "p-full", "c-expired-time",
                ConsentScope.PAYMENTS_INITIATE, ErrorCode.CONSENT_EXPIRED);
    }

    @Test
    @DisplayName("拒绝原因 2b：同意状态为 EXPIRED -> CONSENT_EXPIRED")
    void reject_whenStatusExpired() {
        seedConsent("c-expired-status", "p-full", Set.of(ConsentScope.PAYMENTS_INITIATE),
                ConsentStatus.EXPIRED, OffsetDateTime.now().plusHours(1));
        assertRejected("状态 EXPIRED", "p-full", "c-expired-status",
                ConsentScope.PAYMENTS_INITIATE, ErrorCode.CONSENT_EXPIRED);
    }

    @Test
    @DisplayName("拒绝原因 3：同意已撤销 -> CONSENT_REVOKED")
    void reject_whenRevoked() {
        seedConsent("c-revoked", "p-full", Set.of(ConsentScope.PAYMENTS_INITIATE),
                ConsentStatus.REVOKED, OffsetDateTime.now().plusHours(1));
        assertRejected("已撤销", "p-full", "c-revoked",
                ConsentScope.PAYMENTS_INITIATE, ErrorCode.CONSENT_REVOKED);
    }

    @Test
    @DisplayName("拒绝原因 4a：合作方被授予权限不含所需范围 -> SCOPE_INSUFFICIENT")
    void reject_whenPartnerGrantInsufficient() {
        seedConsent("c-pay", "p-readonly", Set.of(ConsentScope.PAYMENTS_INITIATE),
                ConsentStatus.AUTHORISED, OffsetDateTime.now().plusHours(1));
        assertRejected("合作方授权不足", "p-readonly", "c-pay",
                ConsentScope.PAYMENTS_INITIATE, ErrorCode.SCOPE_INSUFFICIENT);
    }

    @Test
    @DisplayName("拒绝原因 4b：同意范围不含所需范围 -> SCOPE_INSUFFICIENT")
    void reject_whenConsentScopeInsufficient() {
        seedConsent("c-readonly-scope", "p-full", Set.of(ConsentScope.ACCOUNTS_READ),
                ConsentStatus.AUTHORISED, OffsetDateTime.now().plusHours(1));
        assertRejected("同意范围不足", "p-full", "c-readonly-scope",
                ConsentScope.PAYMENTS_INITIATE, ErrorCode.SCOPE_INSUFFICIENT);
    }

    @Test
    @DisplayName("拒绝原因 0：合作方未知 -> PARTNER_UNKNOWN")
    void reject_whenPartnerUnknown() {
        assertRejected("合作方未知", "p-ghost", "c-whatever",
                ConsentScope.PAYMENTS_INITIATE, ErrorCode.PARTNER_UNKNOWN);
    }
}
