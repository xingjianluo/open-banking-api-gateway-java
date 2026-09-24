package com.github.xingjianluo.openbankingapigatewayjava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.xingjianluo.openbankingapigatewayjava.consent.ConsentAuthorizationService;
import com.github.xingjianluo.openbankingapigatewayjava.consent.InMemoryConsentRepository;
import com.github.xingjianluo.openbankingapigatewayjava.downstream.BankClient;
import com.github.xingjianluo.openbankingapigatewayjava.error.ApiException;
import com.github.xingjianluo.openbankingapigatewayjava.error.ErrorCode;
import com.github.xingjianluo.openbankingapigatewayjava.idempotency.IdempotencyService;
import com.github.xingjianluo.openbankingapigatewayjava.model.api.PaymentRequest;
import com.github.xingjianluo.openbankingapigatewayjava.model.api.PaymentResponse;
import com.github.xingjianluo.openbankingapigatewayjava.model.consent.Consent;
import com.github.xingjianluo.openbankingapigatewayjava.model.consent.ConsentScope;
import com.github.xingjianluo.openbankingapigatewayjava.model.consent.ConsentStatus;
import com.github.xingjianluo.openbankingapigatewayjava.model.partner.Partner;
import com.github.xingjianluo.openbankingapigatewayjava.partner.InMemoryPartnerRepository;
import com.github.xingjianluo.openbankingapigatewayjava.payment.InMemoryPaymentRepository;
import com.github.xingjianluo.openbankingapigatewayjava.quota.QuotaService;
import com.github.xingjianluo.openbankingapigatewayjava.service.PaymentService;
import com.github.xingjianluo.openbankingapigatewayjava.version.RequestHasher;
import com.github.xingjianluo.openbankingapigatewayjava.version.VersionNegotiator;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * PaymentService 编排测试：覆盖成功、重放、幂等冲突、版本拒绝、范围不足、
 * 资源不存在、下游异常映射，且断言下游只被调用一次。
 */
class PaymentServiceTest {

    private static final Logger log = LoggerFactory.getLogger(PaymentServiceTest.class);

    private InMemoryPartnerRepository partners;
    private InMemoryConsentRepository consents;
    private InMemoryPaymentRepository payments;
    private BankClient bankClient;
    private PaymentService paymentService;

    @BeforeEach
    void setUp() {
        partners = new InMemoryPartnerRepository();
        consents = new InMemoryConsentRepository();
        payments = new InMemoryPaymentRepository();
        bankClient = new BankClient();
        partners.save(new Partner("p-full", "Full", Set.of(
                ConsentScope.PAYMENTS_INITIATE, ConsentScope.PAYMENTS_READ)));
        partners.save(new Partner("p-readonly", "RO", Set.of(ConsentScope.ACCOUNTS_READ)));

        ConsentAuthorizationService auth = new ConsentAuthorizationService(partners, consents);
        IdempotencyService idempotency = new IdempotencyService(Duration.ofHours(1));
        QuotaService quota = new QuotaService(100, Duration.ofMinutes(1));
        paymentService = new PaymentService(new VersionNegotiator(), auth, idempotency,
                quota, bankClient, payments, new RequestHasher());
    }

    private String seedConsent(String partnerId, Set<ConsentScope> scopes, ConsentStatus status) {
        String id = "CONS-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        consents.save(new Consent(id, partnerId, scopes, status, OffsetDateTime.now().plusHours(1)));
        return id;
    }

    private PaymentRequest request(String consentId, String amount, String currency, String debtorName) {
        return new PaymentRequest(consentId, "DEBT-1", "CRED-1",
                new BigDecimal(amount), currency, debtorName);
    }

    @Test
    @DisplayName("成功发起：V2、新增字段缺省保持既有语义，下游调用一次")
    void initiate_success() {
        String consentId = seedConsent("p-full",
                Set.of(ConsentScope.PAYMENTS_INITIATE), ConsentStatus.AUTHORISED);
        log.info("[成功] 输入 partner=p-full version=null key=key-ok consent={} debtorName=null", consentId);
        PaymentResponse resp = paymentService.initiate("p-full", "key-ok", null,
                request(consentId, "10.00", "EUR", null));
        assertEquals("INITIATED", resp.status());
        assertFalse(resp.replayed());
        assertEquals(1, bankClient.getInvocationCount());
        log.info("[成功] 判定依据: 版本缺省->V2; 授权通过; 首次执行 paymentId={} 下游调用={}",
                resp.paymentId(), bankClient.getInvocationCount());
    }

    @Test
    @DisplayName("重复提交：同键返回相同 paymentId 且 replayed=true，下游仍只调用一次")
    void duplicateSubmit_replaysSameResult() {
        String consentId = seedConsent("p-full",
                Set.of(ConsentScope.PAYMENTS_INITIATE), ConsentStatus.AUTHORISED);
        log.info("[重放] 输入 key=key-dup 连续两次相同请求");
        PaymentRequest req = request(consentId, "10.00", "EUR", null);
        PaymentResponse first = paymentService.initiate("p-full", "key-dup", "2", req);
        PaymentResponse second = paymentService.initiate("p-full", "key-dup", "2", req);
        assertEquals(first.paymentId(), second.paymentId());
        assertFalse(first.replayed());
        assertTrue(second.replayed());
        assertEquals(1, bankClient.getInvocationCount());
        log.info("[重放] 判定依据: 相同哈希命中缓存 paymentId={} replayed={} 下游调用={}",
                second.paymentId(), true, bankClient.getInvocationCount());
    }

    @Test
    @DisplayName("同键不同金额 -> IDEMPOTENCY_CONFLICT")
    void sameKeyDifferentAmount_conflict() {
        String consentId = seedConsent("p-full",
                Set.of(ConsentScope.PAYMENTS_INITIATE), ConsentStatus.AUTHORISED);
        log.info("[服务层冲突] 输入 key=key-c 金额 10 然后 20");
        paymentService.initiate("p-full", "key-c", "2", request(consentId, "10", "EUR", null));
        ApiException ex = assertThrows(ApiException.class, () -> paymentService.initiate(
                "p-full", "key-c", "2", request(consentId, "20", "EUR", null)));
        assertEquals(ErrorCode.IDEMPOTENCY_CONFLICT, ex.getErrorCode());
        assertEquals(1, bankClient.getInvocationCount());
        log.info("[服务层冲突] 判定依据: 哈希不一致 -> {}，下游调用={}",
                ex.getErrorCode().code(), bankClient.getInvocationCount());
    }

    @Test
    @DisplayName("V1 请求 -> VERSION_DEPRECATED，在鉴权与副作用之前拒绝")
    void v1Request_rejected() {
        String consentId = seedConsent("p-full",
                Set.of(ConsentScope.PAYMENTS_INITIATE), ConsentStatus.AUTHORISED);
        log.info("[版本拒绝] 输入 version=1 key=key-v1");
        ApiException ex = assertThrows(ApiException.class, () -> paymentService.initiate(
                "p-full", "key-v1", "1", request(consentId, "10", "EUR", null)));
        assertEquals(ErrorCode.VERSION_DEPRECATED, ex.getErrorCode());
        assertEquals(0, bankClient.getInvocationCount());
        log.info("[版本拒绝] 判定依据: 版本阶段即拒绝 -> {}，无下游调用", ex.getErrorCode().code());
    }

    @Test
    @DisplayName("未知版本 -> VERSION_UNSUPPORTED")
    void unknownVersion_rejected() {
        String consentId = seedConsent("p-full",
                Set.of(ConsentScope.PAYMENTS_INITIATE), ConsentStatus.AUTHORISED);
        log.info("[版本拒绝] 输入 version=42");
        ApiException ex = assertThrows(ApiException.class, () -> paymentService.initiate(
                "p-full", "key-v42", "42", request(consentId, "10", "EUR", null)));
        assertEquals(ErrorCode.VERSION_UNSUPPORTED, ex.getErrorCode());
    }

    @Test
    @DisplayName("只读合作方发起支付 -> SCOPE_INSUFFICIENT，且无副作用")
    void insufficientScope_rejected() {
        String consentId = seedConsent("p-readonly",
                Set.of(ConsentScope.PAYMENTS_INITIATE), ConsentStatus.AUTHORISED);
        log.info("[范围不足] 输入 partner=p-readonly(仅 ACCOUNTS_READ) key=key-scope");
        ApiException ex = assertThrows(ApiException.class, () -> paymentService.initiate(
                "p-readonly", "key-scope", "2", request(consentId, "10", "EUR", null)));
        assertEquals(ErrorCode.SCOPE_INSUFFICIENT, ex.getErrorCode());
        assertEquals(0, bankClient.getInvocationCount());
        log.info("[范围不足] 判定依据: 合作方授权集合不含 PAYMENTS_INITIATE -> {}",
                ex.getErrorCode().code());
    }

    @Test
    @DisplayName("查询不存在的支付 -> RESOURCE_NOT_FOUND")
    void getMissingPayment_notFound() {
        log.info("[资源不存在] 输入 partner=p-full paymentId=PAY-DOES-NOT-EXIST");
        ApiException ex = assertThrows(ApiException.class,
                () -> paymentService.getPayment("p-full", "PAY-DOES-NOT-EXIST"));
        assertEquals(ErrorCode.RESOURCE_NOT_FOUND, ex.getErrorCode());
        log.info("[资源不存在] 判定依据: 仓储无此支付 -> {}", ex.getErrorCode().code());
    }

    @Test
    @DisplayName("下游异常映射为稳定 DOWNSTREAM_FAILURE，且失败被幂等缓存")
    void downstreamFailure_mappedAndCached() {
        String consentId = seedConsent("p-full",
                Set.of(ConsentScope.PAYMENTS_INITIATE), ConsentStatus.AUTHORISED);
        log.info("[下游映射] 输入 currency=ERR key=key-err，连续两次");
        ApiException first = assertThrows(ApiException.class, () -> paymentService.initiate(
                "p-full", "key-err", "2", request(consentId, "10", "ERR", null)));
        ApiException second = assertThrows(ApiException.class, () -> paymentService.initiate(
                "p-full", "key-err", "2", request(consentId, "10", "ERR", null)));
        assertEquals(ErrorCode.DOWNSTREAM_FAILURE, first.getErrorCode());
        assertEquals(ErrorCode.DOWNSTREAM_FAILURE, second.getErrorCode());
        assertEquals(1, bankClient.getInvocationCount(), "重放失败不得再次调用下游");
        assertFalse(first.getMessage().contains("INTERNAL-7F3"), "对外异常不得携带内部细节");
        log.info("[下游映射] 判定依据: 两次均为 {}，下游仅调用 {} 次，内部引用码未外泄",
                ErrorCode.DOWNSTREAM_FAILURE.code(), bankClient.getInvocationCount());
    }

    @Test
    @DisplayName("参数校验：金额非正 -> VALIDATION_FAILED")
    void invalidAmount_rejected() {
        String consentId = seedConsent("p-full",
                Set.of(ConsentScope.PAYMENTS_INITIATE), ConsentStatus.AUTHORISED);
        log.info("[参数校验] 输入 amount=0");
        ApiException ex = assertThrows(ApiException.class, () -> paymentService.initiate(
                "p-full", "key-bad", "2", request(consentId, "0", "EUR", null)));
        assertEquals(ErrorCode.VALIDATION_FAILED, ex.getErrorCode());
        assertEquals(0, bankClient.getInvocationCount());
    }
}
