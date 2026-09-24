package com.github.xingjianluo.openbankingapigatewayjava.web;

import com.github.xingjianluo.openbankingapigatewayjava.consent.ConsentRepository;
import com.github.xingjianluo.openbankingapigatewayjava.error.ApiException;
import com.github.xingjianluo.openbankingapigatewayjava.error.ErrorCode;
import com.github.xingjianluo.openbankingapigatewayjava.model.consent.Consent;
import com.github.xingjianluo.openbankingapigatewayjava.model.consent.ConsentScope;
import com.github.xingjianluo.openbankingapigatewayjava.model.consent.ConsentStatus;
import com.github.xingjianluo.openbankingapigatewayjava.web.dto.ConsentView;
import com.github.xingjianluo.openbankingapigatewayjava.web.dto.CreateConsentRequest;
import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 仅供本地验证使用的管理接口：创建/撤销/置过期同意。
 */
@RestController
@RequestMapping("/local/admin")
public class LocalAdminController {

    private static final Logger log = LoggerFactory.getLogger(LocalAdminController.class);

    private final ConsentRepository consentRepository;

    public LocalAdminController(ConsentRepository consentRepository) {
        this.consentRepository = consentRepository;
    }

    @PostMapping("/consents")
    public ResponseEntity<ConsentView> createConsent(@RequestBody CreateConsentRequest request) {
        if (request.partnerId() == null || request.scopes() == null || request.scopes().isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "partnerId 与 scopes 必填");
        }
        Set<ConsentScope> scopes = request.scopes().stream()
                .map(String::trim)
                .map(String::toUpperCase)
                .map(value -> {
                    try {
                        return ConsentScope.valueOf(value);
                    } catch (IllegalArgumentException e) {
                        throw new ApiException(ErrorCode.VALIDATION_FAILED, "未知 scope: " + value);
                    }
                })
                .collect(Collectors.toUnmodifiableSet());
        ConsentStatus status = request.status() == null || request.status().isBlank()
                ? ConsentStatus.AUTHORISED
                : ConsentStatus.valueOf(request.status().trim().toUpperCase());
        long ttlSeconds = request.ttlSeconds() == null ? 3600L : request.ttlSeconds();
        OffsetDateTime expiresAt = OffsetDateTime.now().plusSeconds(ttlSeconds);
        String consentId = "CONS-" + UUID.randomUUID().toString().substring(0, 8);
        Consent consent = new Consent(consentId, request.partnerId(), scopes, status, expiresAt);
        consentRepository.save(consent);
        log.info("本地管理: 创建同意 consentId={} partnerId={} scopes={} status={} ttl={}s",
                consentId, request.partnerId(), scopes, status, ttlSeconds);
        return ResponseEntity.ok(toView(consent));
    }

    @PostMapping("/consents/{consentId}/revoke")
    public ResponseEntity<ConsentView> revoke(@PathVariable String consentId) {
        Consent consent = requireConsent(consentId);
        consent.setStatus(ConsentStatus.REVOKED);
        log.info("本地管理: 撤销同意 consentId={}", consentId);
        return ResponseEntity.ok(toView(consent));
    }

    @PostMapping("/consents/{consentId}/expire")
    public ResponseEntity<ConsentView> expire(@PathVariable String consentId) {
        Consent consent = requireConsent(consentId);
        consent.setStatus(ConsentStatus.EXPIRED);
        log.info("本地管理: 置过期同意 consentId={}", consentId);
        return ResponseEntity.ok(toView(consent));
    }

    private Consent requireConsent(String consentId) {
        return consentRepository.findById(consentId)
                .orElseThrow(() -> new ApiException(ErrorCode.CONSENT_NOT_FOUND, "consentId=" + consentId));
    }

    private ConsentView toView(Consent consent) {
        Set<String> scopes = consent.getScopes().stream().map(Enum::name).collect(Collectors.toSet());
        return new ConsentView(consent.getConsentId(), consent.getPartnerId(), scopes,
                consent.getStatus().name(), consent.getExpiresAt());
    }
}
