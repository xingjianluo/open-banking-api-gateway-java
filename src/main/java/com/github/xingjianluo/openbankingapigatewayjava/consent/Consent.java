package com.github.xingjianluo.openbankingapigatewayjava.consent;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;

/**
 * 同意（consent）不可变值对象。
 *
 * <p><b>并发一致性设计：</b>Consent 一经发布即为不可变快照；撤销、额度调整、有效期续期
 * 都不会就地修改字段，而是由仓储用 {@code compareAndSet} 原子替换为一个新快照。任何线程
 * 通过仓储读到的永远是"完整的旧快照"或"完整的新快照"，授权与额度判定中途不会看到
 * 半更新状态（例如"新单笔上限 + 旧累计上限"这种组合）。</p>
 *
 * <p><b>额度字段（可选）：</b>{@code perPaymentLimit} / {@code totalLimit} /
 * {@code limitCurrency} 三者要么同时为空（老同意，未配置额度，支付链路不做任何额度判定，
 * 行为与引入额度能力前完全一致），要么同时非空。额度只在本地管理侧创建与调整，
 * 支付请求报文不携带这些字段。</p>
 *
 * @param consentId       同意标识
 * @param partnerId       归属合作方
 * @param scopes          客户授权的权限范围（不可变）
 * @param status          生命周期状态
 * @param createdAt       创建时间
 * @param expiresAt       有效期截止时间（UTC），判定"过期"的唯一依据
 * @param revokedAt       撤销时间；非撤销状态为 null
 * @param perPaymentLimit 单笔支付金额上限（含）；未配置为 null
 * @param totalLimit      累计成功支付金额上限（含）；未配置为 null
 * @param limitCurrency   额度约定币种（ISO 4217）；未配置为 null
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
        BigDecimal totalLimit,
        String limitCurrency) {

    public Consent {
        scopes = scopes == null ? Set.of() : Set.copyOf(scopes);
    }

    /** 工厂：创建一个 AUTHORISED 同意（未配置额度，行为同引入额度能力之前）。 */
    public static Consent authorised(String consentId, String partnerId,
                                     Set<PermissionScope> scopes, Instant createdAt, Instant expiresAt) {
        return new Consent(consentId, partnerId, scopes, ConsentStatus.AUTHORISED,
                createdAt, expiresAt, null, null, null, null);
    }

    /** 该同意是否配置了额度信息（三个额度字段同生同灭）。 */
    public boolean hasLimits() {
        return perPaymentLimit != null && totalLimit != null && limitCurrency != null;
    }

    /** 返回该同意在指定时刻是否仍有效（未撤销、未过期）。本方法不改变对象。 */
    public boolean effectiveAt(Instant now) {
        return status == ConsentStatus.AUTHORISED && now.isBefore(expiresAt);
    }

    /** 返回一个全新的 REVOKED 快照；原对象保持不变。 */
    public Consent asRevoked(Instant revokedAt) {
        return new Consent(consentId, partnerId, scopes, ConsentStatus.REVOKED,
                createdAt, expiresAt, revokedAt, perPaymentLimit, totalLimit, limitCurrency);
    }

    /** 返回一个仅替换额度信息的新快照；原对象保持不变。 */
    public Consent withLimits(BigDecimal newPerPaymentLimit, BigDecimal newTotalLimit,
                              String newLimitCurrency) {
        return new Consent(consentId, partnerId, scopes, status,
                createdAt, expiresAt, revokedAt, newPerPaymentLimit, newTotalLimit, newLimitCurrency);
    }

    /** 返回一个仅替换有效期截止的新快照（续期/缩短）；原对象保持不变。 */
    public Consent withExpiresAt(Instant newExpiresAt) {
        return new Consent(consentId, partnerId, scopes, status,
                createdAt, newExpiresAt, revokedAt, perPaymentLimit, totalLimit, limitCurrency);
    }
}
