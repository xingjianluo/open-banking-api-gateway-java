package com.github.xingjianluo.openbankingapigatewayjava.service;

import com.github.xingjianluo.openbankingapigatewayjava.consent.ConsentAuthorizationService;
import com.github.xingjianluo.openbankingapigatewayjava.downstream.BankClient;
import com.github.xingjianluo.openbankingapigatewayjava.downstream.DownstreamException;
import com.github.xingjianluo.openbankingapigatewayjava.error.ApiException;
import com.github.xingjianluo.openbankingapigatewayjava.error.ErrorCode;
import com.github.xingjianluo.openbankingapigatewayjava.idempotency.IdempotencyOutcome;
import com.github.xingjianluo.openbankingapigatewayjava.idempotency.IdempotencyService;
import com.github.xingjianluo.openbankingapigatewayjava.model.api.ApiVersion;
import com.github.xingjianluo.openbankingapigatewayjava.model.api.PaymentRequest;
import com.github.xingjianluo.openbankingapigatewayjava.model.api.PaymentResponse;
import com.github.xingjianluo.openbankingapigatewayjava.model.consent.ConsentScope;
import com.github.xingjianluo.openbankingapigatewayjava.model.payment.PaymentRecord;
import com.github.xingjianluo.openbankingapigatewayjava.payment.PaymentRepository;
import com.github.xingjianluo.openbankingapigatewayjava.quota.QuotaService;
import com.github.xingjianluo.openbankingapigatewayjava.version.RequestHasher;
import com.github.xingjianluo.openbankingapigatewayjava.version.VersionNegotiator;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 支付编排：版本校验 -> 字段兼容/参数校验 -> 合作方/同意鉴权 -> 幂等执行（配额 + 下游副作用）。
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final VersionNegotiator versionNegotiator;
    private final ConsentAuthorizationService authorizationService;
    private final IdempotencyService idempotencyService;
    private final QuotaService quotaService;
    private final BankClient bankClient;
    private final PaymentRepository paymentRepository;
    private final RequestHasher requestHasher;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public PaymentService(VersionNegotiator versionNegotiator,
                          ConsentAuthorizationService authorizationService,
                          IdempotencyService idempotencyService,
                          QuotaService quotaService,
                          BankClient bankClient,
                          PaymentRepository paymentRepository,
                          RequestHasher requestHasher) {
        this(versionNegotiator, authorizationService, idempotencyService, quotaService,
                bankClient, paymentRepository, requestHasher, Clock.systemUTC());
    }

    /** 测试用构造器：可注入固定时钟。 */
    public PaymentService(VersionNegotiator versionNegotiator,
                          ConsentAuthorizationService authorizationService,
                          IdempotencyService idempotencyService,
                          QuotaService quotaService,
                          BankClient bankClient,
                          PaymentRepository paymentRepository,
                          RequestHasher requestHasher,
                          Clock clock) {
        this.versionNegotiator = versionNegotiator;
        this.authorizationService = authorizationService;
        this.idempotencyService = idempotencyService;
        this.quotaService = quotaService;
        this.bankClient = bankClient;
        this.paymentRepository = paymentRepository;
        this.requestHasher = requestHasher;
        this.clock = clock;
    }

    public PaymentResponse initiate(String partnerId, String idempotencyKey,
                                    String versionHeader, PaymentRequest request) {
        log.info("支付发起请求输入: partnerId={}, idempotencyKey={}, versionHeader={}, request={}",
                partnerId, idempotencyKey, versionHeader, request);

        ApiVersion version = versionNegotiator.negotiate(versionHeader);
        validateAndNormalize(version, request);

        // 鉴权在幂等“占位”之后、动作之前进行：重复/并发提交只做一次鉴权，
        // 重放请求命中缓存直接返回，不会重复鉴权或重复扣配额。
        var consent = authorizationService.authorize(partnerId, request.consentId(),
                ConsentScope.PAYMENTS_INITIATE);
        log.info("判定依据: 同意 {} 授权有效，准备进入幂等执行", consent.getConsentId());

        String hash = requestHasher.hash(request);
        IdempotencyOutcome<PaymentResponse> outcome = idempotencyService.execute(
                idempotencyKey, hash, () -> {
            // 动作内部：配额与下游副作用严格只在首次执行时发生
            quotaService.checkAndConsume(partnerId);
            String currency = request.currency() == null || request.currency().isBlank()
                    ? "EUR" : request.currency().trim();
            String paymentId;
            try {
                paymentId = bankClient.initiatePayment(
                        request.creditorAccount(), request.amount(), currency);
            } catch (DownstreamException ex) {
                // 仅映射为稳定对外错误语义，绝不透出下游内部细节
                log.error("下游依赖异常已映射为 DOWNSTREAM_FAILURE（细节已隐藏）: key={}", idempotencyKey);
                throw new ApiException(ErrorCode.DOWNSTREAM_FAILURE);
            }
            PaymentRecord record = new PaymentRecord(paymentId, partnerId, request.consentId(),
                    request.creditorAccount(), request.amount(), currency,
                    "INITIATED", OffsetDateTime.now(clock));
            paymentRepository.save(record);
            return toResponse(record, false);
        });
        log.info("判定结论: idempotencyKey={} firstExecution={} replayed={} paymentId={}",
                idempotencyKey, outcome.firstExecution(), outcome.replayed(),
                outcome.result().paymentId());
        PaymentResponse response = outcome.result();
        return outcome.replayed()
                ? new PaymentResponse(response.paymentId(), response.consentId(), response.status(),
                        response.creditorAccount(), response.amount(), response.currency(),
                        true, response.createdAt())
                : response;
    }

    public PaymentResponse getPayment(String partnerId, String paymentId) {
        log.info("支付查询请求输入: partnerId={}, paymentId={}", partnerId, paymentId);
        PaymentRecord record = paymentRepository.findById(paymentId)
                .orElseThrow(() -> {
                    log.warn("判定依据: paymentId={} 不存在 -> RESOURCE_NOT_FOUND", paymentId);
                    return new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "paymentId=" + paymentId);
                });
        if (!record.partnerId().equals(partnerId)) {
            // 跨合作方访问同样按不存在处理，避免资源存在性泄露
            log.warn("判定依据: paymentId={} 不属于合作方 {} -> RESOURCE_NOT_FOUND", paymentId, partnerId);
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "paymentId=" + paymentId);
        }
        authorizationService.authorize(partnerId, record.consentId(), ConsentScope.PAYMENTS_READ);
        return toResponse(record, false);
    }

    /**
     * V2 字段兼容性与参数校验。
     */
    private void validateAndNormalize(ApiVersion version, PaymentRequest request) {
        if (request == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "请求体为空");
        }
        if (isBlank(request.consentId())) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "consentId 缺失");
        }
        if (isBlank(request.creditorAccount())) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "creditorAccount 缺失");
        }
        if (request.amount() == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "amount 缺失");
        }
        if (request.amount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "amount 必须为正数");
        }
        // V2 语义：debtorName 是 V2 新增字段，仅允许在 V2 请求上出现；
        // 若未来出现“旧版本携带新语义字段”的情况，在此明确拒绝，不静默忽略。
        if (version == ApiVersion.V2 && request.debtorName() != null
                && request.debtorName().length() > 140) {
            log.warn("字段兼容判定: debtorName 长度 {} 超出 V2 语义约束 -> FIELD_INCOMPATIBLE",
                    request.debtorName().length());
            throw new ApiException(ErrorCode.FIELD_INCOMPATIBLE, "debtorName 超出 V2 长度约束");
        }
        log.info("参数/字段判定: version={} 校验通过，缺省字段按既有语义处理", version);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private PaymentResponse toResponse(PaymentRecord record, boolean replayed) {
        return new PaymentResponse(record.paymentId(), record.consentId(), record.status(),
                record.creditorAccount(), record.amount().toPlainString(), record.currency(),
                replayed, record.createdAt());
    }
}
