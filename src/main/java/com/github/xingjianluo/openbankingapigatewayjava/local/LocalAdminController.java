package com.github.xingjianluo.openbankingapigatewayjava.local;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
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

import com.github.xingjianluo.openbankingapigatewayjava.consent.Consent;
import com.github.xingjianluo.openbankingapigatewayjava.partner.Partner;
import com.github.xingjianluo.openbankingapigatewayjava.partner.PartnerRepository;
import com.github.xingjianluo.openbankingapigatewayjava.consent.ConsentRepository;
import com.github.xingjianluo.openbankingapigatewayjava.consent.ConsentService;
import com.github.xingjianluo.openbankingapigatewayjava.consent.PermissionScope;
import com.github.xingjianluo.openbankingapigatewayjava.payment.PaymentRepository;
import com.github.xingjianluo.openbankingapigatewayjava.quota.QuotaService;

/**
 * 仅用于本地集成演练的管理接口（不是开放银行对外接口）：
 * 显式撤销同意、按需构造同意、重置配额、查看资源数量。
 */
@RestController
@RequestMapping("/local")
public class LocalAdminController {

    /** 构造同意的请求体。ttlSeconds 传负数可直接构造"已过期"同意。 */
    public record CreateConsentRequest(String consentId, String partnerId,
                                      List<String> scopes, long ttlSeconds) {
    }

    public record ActionResult(boolean changed, String detail) {
    }

    private final ConsentRepository consentRepository;
    private final ConsentService consentService;
    private final QuotaService quotaService;
    private final PaymentRepository paymentRepository;
    private final PartnerRepository partnerRepository;

    public LocalAdminController(ConsentRepository consentRepository,
                                ConsentService consentService,
                                QuotaService quotaService,
                                PaymentRepository paymentRepository,
                                PartnerRepository partnerRepository) {
        this.consentRepository = consentRepository;
        this.consentService = consentService;
        this.quotaService = quotaService;
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
        consentRepository.save(consent);
        return Map.of("consentId", consent.consentId(), "status", consent.status().name(),
                "expiresAt", consent.expiresAt(), "scopes", consent.scopes());
    }

    @PostMapping("/consents/{consentId}/revoke")
    public ActionResult revoke(@PathVariable String consentId) {
        boolean changed = consentService.revoke(consentId);
        return new ActionResult(changed, changed ? "同意已撤销" : "同意此前已处于撤销态");
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
