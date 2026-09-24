package com.github.xingjianluo.openbankingapigatewayjava.payment;

import java.time.Instant;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.github.xingjianluo.openbankingapigatewayjava.common.ApiException;
import com.github.xingjianluo.openbankingapigatewayjava.common.ErrorCode;
import com.github.xingjianluo.openbankingapigatewayjava.common.ErrorResponse;
import com.github.xingjianluo.openbankingapigatewayjava.consent.ConsentService;
import com.github.xingjianluo.openbankingapigatewayjava.consent.PermissionScope;
import com.github.xingjianluo.openbankingapigatewayjava.idempotency.IdempotencyDecision;
import com.github.xingjianluo.openbankingapigatewayjava.idempotency.IdempotencyOutcome;
import com.github.xingjianluo.openbankingapigatewayjava.idempotency.IdempotencyRecord;
import com.github.xingjianluo.openbankingapigatewayjava.idempotency.IdempotencyService;
import com.github.xingjianluo.openbankingapigatewayjava.partner.Partner;
import com.github.xingjianluo.openbankingapigatewayjava.partner.PartnerRepository;
import com.github.xingjianluo.openbankingapigatewayjava.quota.QuotaService;
import com.github.xingjianluo.openbankingapigatewayjava.upstream.UpstreamException;
import com.github.xingjianluo.openbankingapigatewayjava.upstream.UpstreamService;
import com.github.xingjianluo.openbankingapigatewayjava.version.ApiVersion;

/**
 * 支付发起/查询的编排服务。
 *
 * <p>创建链路顺序固定：<em>版本协商 → 幂等判定 → 合作方解析 → 同意授权 → 配额 → 下游副作用</em>。
 * 只有拿到 PROCEED 的唯一请求会走到下游并产生一次副作用；任何环节的失败结论都会作为
 * 该幂等键的确定性快照缓存，后续同键同内容重放得到完全相同的结论，不重复执行。</p>
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final PartnerRepository partnerRepository;
    private final ConsentService consentService;
    private final IdempotencyService idempotencyService;
    private final QuotaService quotaService;
    private final UpstreamService upstreamService;
    private final PaymentRepository paymentRepository;

    public PaymentService(PartnerRepository partnerRepository,
                          ConsentService consentService,
                          IdempotencyService idempotencyService,
                          QuotaService quotaService,
                          UpstreamService upstreamService,
                          PaymentRepository paymentRepository) {
        this.partnerRepository = partnerRepository;
        this.consentService = consentService;
        this.idempotencyService = idempotencyService;
        this.quotaService = quotaService;
        this.upstreamService = upstreamService;
        this.paymentRepository = paymentRepository;
    }

    /** 编排结果：outcome 决定是否首次执行，httpStatus/body 为应对外的确定性结论。 */
    public record CreateResult(IdempotencyOutcome outcome, int httpStatus, Object body) {
    }

    /**
     * 发起支付。
     *
     * @param version      已协商版本
     * @param partnerId    合作方标识
     * @param consentId    同意标识
     * @param idemKey      幂等键
     * @param fingerprint  请求体指纹
     * @param request      已解析请求
     * @param failUpstream 是否人为制造下游异常（X-Simulate-Upstream-Failure）
     */
    public CreateResult create(ApiVersion version, String partnerId, String consentId,
                               String idemKey, String fingerprint, PaymentRequest request,
                               boolean failUpstream) {
        validate(idemKey, request);

        IdempotencyDecision decision = idempotencyService.begin(idemKey, partnerId, fingerprint);
        if (decision.outcome() == IdempotencyOutcome.REPLAY) {
            IdempotencyRecord r = decision.record();
            return new CreateResult(IdempotencyOutcome.REPLAY, r.responseStatus(), r.responseBody());
        }
        if (decision.outcome() != IdempotencyOutcome.PROCEED) {
            throw idempotencyService.rejectException(decision);
        }

        IdempotencyRecord record = decision.record();
        try {
            Partner partner = partnerRepository.findById(partnerId)
                    .orElseThrow(() -> new ApiException(ErrorCode.PARTNER_UNKNOWN,
                            "partnerId=" + partnerId));
            consentService.authorize(partner, consentId, PermissionScope.PAYMENTS_INITIATE);
            quotaService.acquire(partnerId);

            String paymentId = "PAY-" + UUID.randomUUID();
            // 唯一一次副作用：调用下游核心并落资源
            String ledgerNo = upstreamService.postPaymentInstruction(paymentId, failUpstream);
            PaymentResponse response = new PaymentResponse(
                    paymentId,
                    request.paymentRef(),
                    "INITIATED",
                    ledgerNo,
                    request.effectivePriority(),
                    version.name(),
                    partnerId,
                    consentId,
                    Instant.now());
            paymentRepository.save(response);

            idempotencyService.complete(record, 201, response);
            log.info("支付创建成功 请求输入 partnerId={} consentId={} idemKey={} paymentRef={} 判定依据=全部检查通过 paymentId={} ledgerNo={}",
                    partnerId, consentId, idemKey, request.paymentRef(), paymentId, ledgerNo);
            return new CreateResult(IdempotencyOutcome.PROCEED, 201, response);
        } catch (ApiException ex) {
            // 业务拒绝：缓存稳定错误语义作为该键的确定结论
            ErrorResponse body = ErrorResponse.of(ex.errorCode(), ex.reason());
            idempotencyService.complete(record, ex.errorCode().httpStatus().value(), body);
            throw ex;
        } catch (UpstreamException ex) {
            // 内部异常细节只记日志，对外映射为稳定语义并缓存
            log.error("下游依赖异常，映射 UPSTREAM_UNAVAILABLE key={} detail={}",
                    idemKey, ex.internalDetail());
            ErrorCode code = ErrorCode.UPSTREAM_UNAVAILABLE;
            ErrorResponse body = ErrorResponse.of(code, "支付指令处理失败，请稍后重试");
            idempotencyService.complete(record, code.httpStatus().value(), body);
            throw new ApiException(code, "支付指令处理失败，请稍后重试");
        }
    }

    /** 查询支付资源：授权通过后再判存在性与归属。 */
    public PaymentResponse get(String partnerId, String consentId, String paymentId) {
        Partner partner = partnerRepository.findById(partnerId)
                .orElseThrow(() -> new ApiException(ErrorCode.PARTNER_UNKNOWN, "partnerId=" + partnerId));
        consentService.authorize(partner, consentId, PermissionScope.PAYMENTS_READ);

        PaymentResponse payment = paymentRepository.findById(paymentId).orElse(null);
        if (payment == null || !payment.ownerPartnerId().equals(partnerId)) {
            log.info("资源拒绝 请求输入 partnerId={} paymentId={} 判定依据=资源不存在或不属于该合作方(RESOURCE_NOT_FOUND)",
                    partnerId, paymentId);
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND,
                    "paymentId=" + paymentId + " 不存在");
        }
        return payment;
    }

    private void validate(String idemKey, PaymentRequest request) {
        if (idemKey == null || idemKey.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "缺少请求头 Idempotency-Key");
        }
        if (request == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "请求体为空");
        }
        if (isBlank(request.paymentRef()) || request.amount() == null
                || request.amount().signum() <= 0 || isBlank(request.currency())
                || isBlank(request.debtorAccountId()) || isBlank(request.creditorAccountId())) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "paymentRef/amount(>0)/currency/debtorAccountId/creditorAccountId 必须完整提供");
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
