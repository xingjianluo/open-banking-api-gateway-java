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
            if (consentRepository.compareAndReplace(current, revoked)) {
                log.info("撤销成功 consentId={} revokedAt={}", consentId, revoked.revokedAt());
                return true;
            }
            // CAS 失败说明有并发修改，重读重试
        }
    }

    /**
     * 调整额度：CAS 循环把当前快照替换为仅额度字段不同的新快照。可上调也可下调；
     * 传 {@code null} 表示取消该项约束。并发进行中的支付请求只会读到调整前或调整后的
     * 完整快照之一，其结论相对所读快照是确定的，不会看到改到一半的中间状态。
     *
     * @return 调整生效后的新快照
     */
    public Consent updateLimits(String consentId, java.math.BigDecimal perPaymentLimit,
                                java.math.BigDecimal cumulativeLimit, String limitCurrency) {
        while (true) {
            Consent current = consentRepository.findById(consentId).orElse(null);
            if (current == null) {
                throw new ApiException(ErrorCode.CONSENT_NOT_FOUND, "consentId=" + consentId);
            }
            Consent updated = current.withLimits(perPaymentLimit, cumulativeLimit, limitCurrency);
            if (consentRepository.compareAndReplace(current, updated)) {
                log.info("额度调整 consentId={} 旧额度=({}/{}/{}) 新额度=({}/{}/{})",
                        consentId, current.perPaymentLimit(), current.cumulativeLimit(),
                        current.limitCurrency(), perPaymentLimit, cumulativeLimit, limitCurrency);
                return updated;
            }
            // CAS 失败说明有并发修改，重读重试
        }
    }

    /**
     * 续期：CAS 循环把有效期替换为新截止时间。<b>只能延长，不能缩短或持平</b>：
     * 新截止时间必须严格晚于当前快照的截止时间，否则以
     * {@code CONSENT_EXTENSION_INVALID} 拒绝（与其它失败原因可区分），同意保持原样。
     *
     * <p>校验在 CAS 循环内针对每次读到的当前快照进行：若校验通过后 CAS 失败
     * （说明有并发修改），重读新快照并重新校验，杜绝"按旧快照验过、却覆盖了更新的
     * 截止时间"的竞态。被拒绝或 CAS 竞争放弃时，存储中的快照一个字节都不会被改动，
     * 有效期、额度配置与累计用量（独立账本，续期本就不触碰）全部保持原样。</p>
     *
     * <p>生命周期结论：</p>
     * <ul>
     *   <li>已撤销（REVOKED 终态）→ {@code CONSENT_REVOKED}，续期不能让撤销的同意复活；</li>
     *   <li>已过期但未撤销 → 允许续到未来，续期后恢复正常使用；</li>
     *   <li>与支付判定并发时，支付方只会基于旧 expiresAt 或新 expiresAt 之一的
     *       完整快照得出结论，不会读到中间态。</li>
     * </ul>
     *
     * @return 续期生效后的新快照
     */
    public Consent extendExpiry(String consentId, Instant newExpiresAt) {
        while (true) {
            Consent current = consentRepository.findById(consentId).orElse(null);
            if (current == null) {
                throw new ApiException(ErrorCode.CONSENT_NOT_FOUND, "consentId=" + consentId);
            }
            if (current.status() == ConsentStatus.REVOKED) {
                log.info("续期拒绝 请求输入 consentId={} newExpiresAt={} 判定依据=同意已撤销(终态不可复活) revokedAt={}",
                        consentId, newExpiresAt, current.revokedAt());
                throw new ApiException(ErrorCode.CONSENT_REVOKED,
                        "consentId=" + consentId + " 已于 " + current.revokedAt() + " 撤销，不可续期");
            }
            if (!newExpiresAt.isAfter(current.expiresAt())) {
                log.info("续期拒绝 请求输入 consentId={} newExpiresAt={} 判定依据=新截止时间未晚于当前截止时间 当前expiresAt={}",
                        consentId, newExpiresAt, current.expiresAt());
                throw new ApiException(ErrorCode.CONSENT_EXTENSION_INVALID,
                        "consentId=" + consentId + " 当前有效期截止 " + current.expiresAt()
                                + "，续期截止时间 " + newExpiresAt + " 必须更晚");
            }
            Consent updated = current.withExpiry(newExpiresAt);
            if (consentRepository.compareAndReplace(current, updated)) {
                log.info("同意续期 consentId={} 旧expiresAt={} 新expiresAt={}",
                        consentId, current.expiresAt(), newExpiresAt);
                return updated;
            }
            // CAS 失败说明有并发修改，重读新快照并重新校验后再试
        }
    }
}
