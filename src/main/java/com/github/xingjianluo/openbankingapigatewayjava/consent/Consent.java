package com.github.xingjianluo.openbankingapigatewayjava.consent;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;

/**
 * 同意（consent）不可变值对象。
 *
 * <p><b>并发一致性设计：</b>Consent 一经发布即为不可变快照；撤销、调额、续期都不会就地
 * 修改字段，而是由仓储用 {@code compareAndSet} 原子替换为一个新快照。任何线程通过
 * 仓储读到的永远是"完整的旧快照"或"完整的新快照"，授权/额度判定中途不会看到
 * 半更新状态。</p>
 *
 * <p><b>额度字段（可空）：</b>{@code perPaymentLimit} / {@code cumulativeLimit} /
 * {@code limitCurrency} 三者为 {@code null} 表示"未配置该项约束"，判定行为与引入额度
 * 能力之前完全一致（向后兼容：老调用方、老同意零改造）。</p>
 *
 * @param consentId        同意标识
 * @param partnerId        归属合作方
 * @param scopes           客户授权的权限范围（不可变）
 * @param status           生命周期状态
 * @param createdAt        创建时间
 * @param expiresAt        有效期截止时间（UTC），判定"过期"的唯一依据
 * @param revokedAt        撤销时间；非撤销状态为 null
 * @param perPaymentLimit  单笔支付金额上限；null 表示不限制
 * @param cumulativeLimit  累计成功支付金额上限；null 表示不限制
 * @param limitCurrency    额度约定币种（ISO 4217）；null 表示不限制币种
 */
public record Consent(
        String consentId,
        String partnerId,
        Set<PermissionScope> scopes,
        ConsentStatus status,
        Instant createdAt,
        Instant expiresAt,
        Instant revokedAt,
        BigDecimal perPaymentLimit,
        BigDecimal cumulativeLimit,
        String limitCurrency) {

    public Consent {
        scopes = scopes == null ? Set.of() : Set.copyOf(scopes);
    }

    /** 工厂：创建一个不带额度约束的 AUTHORISED 同意（兼容既有调用方）。 */
    public static Consent authorised(String consentId, String partnerId,
                                     Set<PermissionScope> scopes, Instant createdAt, Instant expiresAt) {
        return new Consent(consentId, partnerId, scopes, ConsentStatus.AUTHORISED,
                createdAt, expiresAt, null, null, null, null);
    }

    /** 工厂：创建一个带额度约束的 AUTHORISED 同意。 */
    public static Consent authorisedWithLimits(String consentId, String partnerId,
                                               Set<PermissionScope> scopes,
                                               Instant createdAt, Instant expiresAt,
                                               BigDecimal perPaymentLimit, BigDecimal cumulativeLimit,
                                               String limitCurrency) {
        return new Consent(consentId, partnerId, scopes, ConsentStatus.AUTHORISED,
                createdAt, expiresAt, null, perPaymentLimit, cumulativeLimit, limitCurrency);
    }

    /** 返回该同意在指定时刻是否仍有效（未撤销、未过期）。本方法不改变对象。 */
    public boolean effectiveAt(Instant now) {
        return status == ConsentStatus.AUTHORISED && now.isBefore(expiresAt);
    }

    /** 是否配置了任何额度约束。 */
    public boolean hasLimits() {
        return perPaymentLimit != null || cumulativeLimit != null || limitCurrency != null;
    }

    /** 返回一个全新的 REVOKED 快照；原对象保持不变。 */
    public Consent asRevoked(Instant revokedAt) {
        return new Consent(consentId, partnerId, scopes, ConsentStatus.REVOKED,
                createdAt, expiresAt, revokedAt, perPaymentLimit, cumulativeLimit, limitCurrency);
    }

    /** 返回一个仅调整额度字段的新快照；其余字段（含累计用量所在的语义）保持不变。 */
    public Consent withLimits(BigDecimal newPerPaymentLimit, BigDecimal newCumulativeLimit,
                              String newLimitCurrency) {
        return new Consent(consentId, partnerId, scopes, status,
                createdAt, expiresAt, revokedAt, newPerPaymentLimit, newCumulativeLimit,
                newLimitCurrency);
    }

    /** 返回一个仅延长/调整有效期的新快照；其余字段保持不变。 */
    public Consent withExpiry(Instant newExpiresAt) {
        return new Consent(consentId, partnerId, scopes, status,
                createdAt, newExpiresAt, revokedAt, perPaymentLimit, cumulativeLimit, limitCurrency);
    }
}
