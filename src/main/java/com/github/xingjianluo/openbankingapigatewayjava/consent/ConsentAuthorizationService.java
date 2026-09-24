package com.github.xingjianluo.openbankingapigatewayjava.consent;

import com.github.xingjianluo.openbankingapigatewayjava.error.ApiException;
import com.github.xingjianluo.openbankingapigatewayjava.error.ErrorCode;
import com.github.xingjianluo.openbankingapigatewayjava.model.consent.Consent;
import com.github.xingjianluo.openbankingapigatewayjava.model.consent.ConsentScope;
import com.github.xingjianluo.openbankingapigatewayjava.model.partner.Partner;
import com.github.xingjianluo.openbankingapigatewayjava.partner.PartnerRepository;
import java.time.OffsetDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 同意授权判定服务：对一次资源访问做一致的授权判定。
 * 四种拒绝原因互不相同：
 * CONSENT_NOT_FOUND / CONSENT_EXPIRED / CONSENT_REVOKED / SCOPE_INSUFFICIENT。
 *
 * <p>一致性保证：判定所依据的状态在方法内只做一次快照读取（status 为 volatile，
 * scopes/expiresAt 不可变），因此并发撤销/置过期期间，任何一次访问要么按
 * AUTHORISED 完整放行、要么按终态完整拒绝，不会出现“中途可见”的混合结论。
 */
@Service
public class ConsentAuthorizationService {

    private static final Logger log = LoggerFactory.getLogger(ConsentAuthorizationService.class);

    private final PartnerRepository partnerRepository;
    private final ConsentRepository consentRepository;

    public ConsentAuthorizationService(PartnerRepository partnerRepository,
                                       ConsentRepository consentRepository) {
        this.partnerRepository = partnerRepository;
        this.consentRepository = consentRepository;
    }

    /**
     * 对 (partnerId, consentId, requiredScope) 做授权判定，成功返回同意快照对象。
     */
    public Consent authorize(String partnerId, String consentId, ConsentScope requiredScope) {
        log.info("授权判定开始: partnerId={}, consentId={}, requiredScope={}", partnerId, consentId, requiredScope);

        Partner partner = partnerRepository.findById(partnerId)
                .orElseThrow(() -> {
                    log.warn("授权判定依据: 合作方 {} 不存在 -> PARTNER_UNKNOWN", partnerId);
                    return new ApiException(ErrorCode.PARTNER_UNKNOWN, "partnerId=" + partnerId);
                });

        Consent consent = consentRepository.findById(consentId)
                // 不同意归属他人时同样按“不存在”处理，避免资源存在性泄露
                .filter(c -> c.getPartnerId().equals(partnerId))
                .orElseThrow(() -> {
                    log.warn("授权判定依据: consentId={} 不存在或不属于合作方 {} -> CONSENT_NOT_FOUND",
                            consentId, partnerId);
                    return new ApiException(ErrorCode.CONSENT_NOT_FOUND, "consentId=" + consentId);
                });

        // 一次性快照：后续判定全部基于局部变量，不重复读取可变状态
        ConsentStatusSnapshot snapshot = new ConsentStatusSnapshot(
                consent.getStatus(), consent.getExpiresAt(), consent.getScopes());

        if (snapshot.expiredAt(OffsetDateTime.now())) {
            log.warn("授权判定依据: consentId={} 已过期（status={}, expiresAt={}）-> CONSENT_EXPIRED",
                    consentId, snapshot.status(), snapshot.expiresAt());
            throw new ApiException(ErrorCode.CONSENT_EXPIRED, "consentId=" + consentId);
        }
        if (snapshot.status() == com.github.xingjianluo.openbankingapigatewayjava.model.consent.ConsentStatus.REVOKED) {
            log.warn("授权判定依据: consentId={} 已被撤销 -> CONSENT_REVOKED", consentId);
            throw new ApiException(ErrorCode.CONSENT_REVOKED, "consentId=" + consentId);
        }
        if (!partner.grantedScopes().contains(requiredScope)) {
            log.warn("授权判定依据: 合作方 {} 被授予权限 {} 不含 {} -> SCOPE_INSUFFICIENT",
                    partnerId, partner.grantedScopes(), requiredScope);
            throw new ApiException(ErrorCode.SCOPE_INSUFFICIENT,
                    "partner grant 缺少 " + requiredScope);
        }
        if (!snapshot.scopes().contains(requiredScope)) {
            log.warn("授权判定依据: 同意 {} 范围 {} 不含 {} -> SCOPE_INSUFFICIENT",
                    consentId, snapshot.scopes(), requiredScope);
            throw new ApiException(ErrorCode.SCOPE_INSUFFICIENT,
                    "consent scope 缺少 " + requiredScope);
        }

        log.info("授权判定通过: partnerId={}, consentId={}, scope={}", partnerId, consentId, requiredScope);
        return consent;
    }

    /** 判定所用的不可变状态快照。 */
    private record ConsentStatusSnapshot(
            com.github.xingjianluo.openbankingapigatewayjava.model.consent.ConsentStatus status,
            OffsetDateTime expiresAt,
            java.util.Set<ConsentScope> scopes) {

        boolean expiredAt(OffsetDateTime now) {
            return status == com.github.xingjianluo.openbankingapigatewayjava.model.consent.ConsentStatus.EXPIRED
                    || (expiresAt != null && !expiresAt.isAfter(now));
        }
    }
}
