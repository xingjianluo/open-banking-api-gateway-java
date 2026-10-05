package com.github.xingjianluo.openbankingapigatewayjava.consent;

import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.github.xingjianluo.openbankingapigatewayjava.common.ApiException;
import com.github.xingjianluo.openbankingapigatewayjava.common.ErrorCode;
import com.github.xingjianluo.openbankingapigatewayjava.partner.Partner;

/**
 * 同意授权与访问控制。
 *
 * <p>判定顺序固定且每种拒绝原因互不相同：</p>
 * <ol>
 *   <li>同意不存在（或不属于该合作方）→ {@code CONSENT_NOT_FOUND}（不泄露存在性）；</li>
 *   <li>显式撤销的终态 → {@code CONSENT_REVOKED}（撤销是主动行为，优先于过期判定）；</li>
 *   <li>当前时间 ≥ expiresAt → {@code CONSENT_EXPIRED}；</li>
 *   <li>合作方授权 scope 与同意 scope 任一不含所需 scope → {@code SCOPE_INSUFFICIENT}；</li>
 *   <li>资源层面的存在性由业务服务另行判定 → {@code RESOURCE_NOT_FOUND}。</li>
 * </ol>
 *
 * <p><b>并发一致性：</b>进入方法后只对不可变快照做判定——{@link Partner} 与 {@link Consent}
 * 都是不可变对象，一次授权调用内所有检查基于同一组引用，不存在"先看到已撤销、后读到旧有效期"
 * 之类的中途可见不一致。</p>
 */
@Service
public class ConsentService {

    private static final Logger log = LoggerFactory.getLogger(ConsentService.class);

    private final ConsentRepository consentRepository;

    public ConsentService(ConsentRepository consentRepository) {
        this.consentRepository = consentRepository;
    }

    /**
     * 授权入口：失败抛 {@link ApiException}，成功返回本次判定所依据的同意快照。
     *
     * @param partner       已解析的合作方（其 grantedScopes 不可变）
     * @param consentId     请求声明的同意标识
     * @param requiredScope 本次操作所需权限
     * @return 授权通过所依据的不可变同意快照
     */
    public Consent authorize(Partner partner, String consentId, PermissionScope requiredScope) {
        Instant now = Instant.now();

        Consent consent = consentRepository.findById(consentId).orElse(null);
        // 不属于该合作方的同意按"不存在"处理，避免跨租户探测
        if (consent == null || !consent.partnerId().equals(partner.partnerId())) {
            log.info("授权拒绝 请求输入 partnerId={} consentId={} requiredScope={} 判定依据=同意不存在或不属于该合作方",
                    partner.partnerId(), consentId, requiredScope);
            throw new ApiException(ErrorCode.CONSENT_NOT_FOUND,
                    "consentId=" + consentId + " 对合作方不可见");
        }

        if (consent.status() == ConsentStatus.REVOKED) {
            log.info("授权拒绝 请求输入 partnerId={} consentId={} requiredScope={} 判定依据=同意已撤销 revokedAt={}",
                    partner.partnerId(), consentId, requiredScope, consent.revokedAt());
            throw new ApiException(ErrorCode.CONSENT_REVOKED,
                    "consentId=" + consentId + " 已于 " + consent.revokedAt() + " 撤销");
        }

        if (!now.isBefore(consent.expiresAt())) {
            log.info("授权拒绝 请求输入 partnerId={} consentId={} requiredScope={} 判定依据=同意已过期 now={} expiresAt={}",
                    partner.partnerId(), consentId, requiredScope, now, consent.expiresAt());
            throw new ApiException(ErrorCode.CONSENT_EXPIRED,
                    "consentId=" + consentId + " 有效期截止 " + consent.expiresAt());
        }

        boolean partnerHas = partner.grantedScopes().contains(requiredScope);
        boolean consentHas = consent.scopes().contains(requiredScope);
        if (!partnerHas || !consentHas) {
            log.info("授权拒绝 请求输入 partnerId={} consentId={} requiredScope={} 判定依据=范围不足 partnerGranted={} consentScopes={}",
                    partner.partnerId(), consentId, requiredScope,
                    partner.grantedScopes(), consent.scopes());
            throw new ApiException(ErrorCode.SCOPE_INSUFFICIENT,
                    "缺少权限 " + requiredScope.claim()
                            + "（合作方授予:" + partnerHas + "，同意包含:" + consentHas + "）");
        }

        log.info("授权通过 请求输入 partnerId={} consentId={} requiredScope={} 判定依据=状态AUTHORISED且未过期且双端scope满足 expiresAt={}",
                partner.partnerId(), consentId, requiredScope, consent.expiresAt());
        return consent;
    }

    /**
     * 显式撤销：CAS 循环替换快照。并发撤销只有一个线程成功，且替换完成后所有新进入的
     * 授权请求立即只能读到 REVOKED 快照，不存在中间窗口。
     *
     * @return 本次调用是否完成了撤销（已处于撤销态时返回 false）
     */
    public boolean revoke(String consentId) {
        while (true) {
            Consent current = consentRepository.findById(consentId).orElse(null);
            if (current == null) {
                throw new ApiException(ErrorCode.CONSENT_NOT_FOUND, "consentId=" + consentId);
            }
            if (current.status() == ConsentStatus.REVOKED) {
                log.info("撤销请求 consentId={} 判定依据=同意已是REVOKED终态", consentId);
                return false;
            }
            Consent revoked = current.asRevoked(Instant.now());
            if (consentRepository.compareAndRevoke(current, revoked)) {
                log.info("撤销成功 consentId={} revokedAt={}", consentId, revoked.revokedAt());
                return true;
            }
            // CAS 失败说明有并发修改，重读重试
        }
    }

    /**
     * 调整额度（本地管理侧）：可上调或下调单笔/累计上限并约定币种，三者整体替换。
     *
     * <p>并发语义与撤销相同：CAS 原子替换整个不可变快照。正在进行的支付请求持有的是
     * 进入时读到的完整旧快照或完整新快照，判定结论对那次请求是确定的——不会观察到
     * "新单笔上限 + 旧累计上限"之类的中间组合。REVOKED 终态不允许再调整。</p>
     *
     * @return 调整后的新快照
     */
    public Consent adjustLimits(String consentId, java.math.BigDecimal perPaymentLimit,
                                java.math.BigDecimal totalLimit, String currency) {
        if (perPaymentLimit == null || perPaymentLimit.signum() <= 0
                || totalLimit == null || totalLimit.signum() <= 0
                || currency == null || currency.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "perPaymentLimit/totalLimit 必须为正数，currency 必须提供");
        }
        String normalizedCurrency = currency.trim().toUpperCase();
        while (true) {
            Consent current = consentRepository.findById(consentId).orElse(null);
            if (current == null) {
                throw new ApiException(ErrorCode.CONSENT_NOT_FOUND, "consentId=" + consentId);
            }
            if (current.status() == ConsentStatus.REVOKED) {
                log.info("额度调整拒绝 consentId={} 判定依据=同意已是REVOKED终态", consentId);
                throw new ApiException(ErrorCode.CONSENT_REVOKED,
                        "consentId=" + consentId + " 已撤销，不允许调整额度");
            }
            Consent updated = current.withLimits(perPaymentLimit, totalLimit, normalizedCurrency);
            if (consentRepository.compareAndRevoke(current, updated)) {
                log.info("额度调整成功 consentId={} perPaymentLimit={} totalLimit={} currency={} (旧值 per={} total={} ccy={})",
                        consentId, perPaymentLimit, totalLimit, normalizedCurrency,
                        current.perPaymentLimit(), current.totalLimit(), current.limitCurrency());
                return updated;
            }
            // CAS 失败说明有并发修改，重读重试
        }
    }

    /**
     * 延长有效期（续期）：新截止时间必须晚于当前截止时间，只能延长不能缩短。
     * 已过期的同意可通过续期恢复有效；REVOKED 终态不允许续期。
     * 并发语义同 {@link #adjustLimits}：整体快照 CAS 替换，无中间态。
     *
     * @return 续期后的新快照
     */
    public Consent renew(String consentId, Instant newExpiresAt) {
        if (newExpiresAt == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "newExpiresAt 必须提供");
        }
        while (true) {
            Consent current = consentRepository.findById(consentId).orElse(null);
            if (current == null) {
                throw new ApiException(ErrorCode.CONSENT_NOT_FOUND, "consentId=" + consentId);
            }
            if (current.status() == ConsentStatus.REVOKED) {
                log.info("续期拒绝 consentId={} 判定依据=同意已是REVOKED终态", consentId);
                throw new ApiException(ErrorCode.CONSENT_REVOKED,
                        "consentId=" + consentId + " 已撤销，不允许续期");
            }
            if (!newExpiresAt.isAfter(current.expiresAt())) {
                log.info("续期拒绝 consentId={} 判定依据=新截止时间{}不晚于当前{}，仅允许延长",
                        consentId, newExpiresAt, current.expiresAt());
                throw new ApiException(ErrorCode.VALIDATION_FAILED,
                        "新截止时间 " + newExpiresAt + " 必须晚于当前截止 " + current.expiresAt()
                                + "（仅支持延长有效期）");
            }
            Consent renewed = current.withExpiresAt(newExpiresAt);
            if (consentRepository.compareAndRevoke(current, renewed)) {
                log.info("续期成功 consentId={} expiresAt {} -> {}", consentId,
                        current.expiresAt(), newExpiresAt);
                return renewed;
            }
            // CAS 失败说明有并发修改，重读重试
        }
    }
}
