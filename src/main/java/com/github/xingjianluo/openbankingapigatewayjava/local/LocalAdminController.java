package com.github.xingjianluo.openbankingapigatewayjava.local;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.github.xingjianluo.openbankingapigatewayjava.common.ApiException;
import com.github.xingjianluo.openbankingapigatewayjava.common.ErrorCode;
import com.github.xingjianluo.openbankingapigatewayjava.consent.Consent;
import com.github.xingjianluo.openbankingapigatewayjava.partner.Partner;
import com.github.xingjianluo.openbankingapigatewayjava.partner.PartnerRepository;
import com.github.xingjianluo.openbankingapigatewayjava.consent.ConsentRepository;
import com.github.xingjianluo.openbankingapigatewayjava.consent.ConsentService;
import com.github.xingjianluo.openbankingapigatewayjava.consent.PermissionScope;
import com.github.xingjianluo.openbankingapigatewayjava.limit.LimitService;
import com.github.xingjianluo.openbankingapigatewayjava.payment.PaymentRepository;
import com.github.xingjianluo.openbankingapigatewayjava.quota.QuotaService;

/**
 * 仅用于本地集成演练的管理接口（不是开放银行对外接口）：
 * 显式撤销同意、按需构造同意、调整同意额度、续期、重置配额、查看用量与资源数量。
 */
@RestController
@RequestMapping("/local")
public class LocalAdminController {

    /**
     * 构造同意的请求体。ttlSeconds 传负数可直接构造"已过期"同意。
     * perPaymentLimit/totalLimit/currency 三个额度字段可选：全部缺省时创建的是
     * 无额度老同意（行为同引入额度能力之前）；只给一部分属于非法输入。
     */
    public record CreateConsentRequest(String consentId, String partnerId,
                                      List<String> scopes, long ttlSeconds,
                                      BigDecimal perPaymentLimit, BigDecimal totalLimit,
                                      String currency) {
    }

    /** 调整额度的请求体：三字段整体替换，必须全部提供。 */
    public record AdjustLimitsRequest(BigDecimal perPaymentLimit, BigDecimal totalLimit,
                                      String currency) {
    }

    public record ActionResult(boolean changed, String detail) {
    }

    private final ConsentRepository consentRepository;
    private final ConsentService consentService;
    private final QuotaService quotaService;
    private final LimitService limitService;
    private final PaymentRepository paymentRepository;
    private final PartnerRepository partnerRepository;

    public LocalAdminController(ConsentRepository consentRepository,
                                ConsentService consentService,
                                QuotaService quotaService,
                                LimitService limitService,
                                PaymentRepository paymentRepository,
                                PartnerRepository partnerRepository) {
        this.consentRepository = consentRepository;
        this.consentService = consentService;
        this.quotaService = quotaService;
        this.limitService = limitService;
        this.paymentRepository = paymentRepository;
        this.partnerRepository = partnerRepository;
    }

    /** 构造合作方的请求体。 */
    public record CreatePartnerRequest(String partnerId, String name, List<String> scopes) {
    }

    @PostMapping("/partners")
    public Map<String, Object> createPartner(@RequestBody CreatePartnerRequest req) {
        Set<PermissionScope> scopes = req.scopes() == null ? Set.of()
                : req.scopes().stream().map(String::trim).map(String::toUpperCase)
                .map(PermissionScope::valueOf).collect(Collectors.toSet());
        Partner partner = new Partner(req.partnerId(), req.name() == null ? req.partnerId() : req.name(),
                scopes);
        partnerRepository.save(partner);
        return Map.of("partnerId", partner.partnerId(), "grantedScopes", partner.grantedScopes());
    }

    @PostMapping("/consents")
    public Map<String, Object> createConsent(@RequestBody CreateConsentRequest req) {
        Instant now = Instant.now();
        Set<PermissionScope> scopes = req.scopes() == null ? Set.of()
                : req.scopes().stream().map(String::trim).map(String::toUpperCase)
                .map(PermissionScope::valueOf).collect(Collectors.toSet());
        Consent consent = Consent.authorised(req.consentId(), req.partnerId(), scopes,
                now, now.plus(req.ttlSeconds(), ChronoUnit.SECONDS));

        boolean anyLimit = req.perPaymentLimit() != null || req.totalLimit() != null
                || req.currency() != null;
        boolean allLimits = req.perPaymentLimit() != null && req.totalLimit() != null
                && req.currency() != null && !req.currency().isBlank();
        if (anyLimit && !allLimits) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "perPaymentLimit/totalLimit/currency 必须同时提供或同时缺省");
        }
        if (allLimits) {
            consent = consent.withLimits(req.perPaymentLimit(), req.totalLimit(),
                    req.currency().trim().toUpperCase());
        }
        consentRepository.save(consent);

        Map<String, Object> view = new HashMap<>();
        view.put("consentId", consent.consentId());
        view.put("status", consent.status().name());
        view.put("expiresAt", consent.expiresAt());
        view.put("scopes", consent.scopes());
        view.put("perPaymentLimit", consent.perPaymentLimit());
        view.put("totalLimit", consent.totalLimit());
        view.put("limitCurrency", consent.limitCurrency());
        return view;
    }

    @PostMapping("/consents/{consentId}/revoke")
    public ActionResult revoke(@PathVariable String consentId) {
        boolean changed = consentService.revoke(consentId);
        return new ActionResult(changed, changed ? "同意已撤销" : "同意此前已处于撤销态");
    }

    /** 调整额度：可上调/下调单笔与累计上限并约定币种，整体快照替换。 */
    @PostMapping("/consents/{consentId}/limits")
    public Map<String, Object> adjustLimits(@PathVariable String consentId,
                                            @RequestBody AdjustLimitsRequest req) {
        Consent updated = consentService.adjustLimits(consentId, req.perPaymentLimit(),
                req.totalLimit(), req.currency());
        Map<String, Object> view = new HashMap<>();
        view.put("consentId", updated.consentId());
        view.put("perPaymentLimit", updated.perPaymentLimit());
        view.put("totalLimit", updated.totalLimit());
        view.put("limitCurrency", updated.limitCurrency());
        return view;
    }

    /** 续期：新截止时间 = 当前时刻 + ttlSeconds，且必须晚于原截止时间（只延长）。 */
    @PostMapping("/consents/{consentId}/renew")
    public Map<String, Object> renew(@PathVariable String consentId,
                                     @RequestParam long ttlSeconds) {
        Consent renewed = consentService.renew(consentId,
                Instant.now().plus(ttlSeconds, ChronoUnit.SECONDS));
        return Map.of("consentId", renewed.consentId(), "expiresAt", renewed.expiresAt());
    }

    /** 查看某同意的额度与当前累计用量。 */
    @GetMapping("/consents/{consentId}/usage")
    public Map<String, Object> usage(@PathVariable String consentId) {
        Consent consent = consentRepository.findById(consentId)
                .orElseThrow(() -> new ApiException(ErrorCode.CONSENT_NOT_FOUND,
                        "consentId=" + consentId));
        Map<String, Object> view = new HashMap<>();
        view.put("consentId", consentId);
        view.put("usedAmount", limitService.usedAmount(consentId));
        view.put("perPaymentLimit", consent.perPaymentLimit());
        view.put("totalLimit", consent.totalLimit());
        view.put("limitCurrency", consent.limitCurrency());
        return view;
    }

    @PostMapping("/quotas/{partnerId}/reset")
    public ActionResult resetQuota(@PathVariable String partnerId) {
        quotaService.resetUsed(partnerId);
        return new ActionResult(true, "已用计数已重置");
    }

    @PostMapping("/quotas/{partnerId}/limit")
    public ActionResult configureLimit(@PathVariable String partnerId,
                                       @RequestParam int limit) {
        quotaService.configureLimit(partnerId, limit);
        return new ActionResult(true, "配额上限已设置为 " + limit);
    }

    @GetMapping("/quotas")
    public Map<String, int[]> quotas() {
        return quotaService.snapshot();
    }

    @GetMapping("/payments/count")
    public Map<String, Long> paymentCount() {
        return Map.of("paymentCount", paymentRepository.count());
    }
}
