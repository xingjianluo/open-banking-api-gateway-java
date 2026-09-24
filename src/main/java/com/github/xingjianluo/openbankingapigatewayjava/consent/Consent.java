package com.github.xingjianluo.openbankingapigatewayjava.consent;

import java.time.Instant;
import java.util.Set;

/**
 * 同意（consent）不可变值对象。
 *
 * <p><b>并发一致性设计：</b>Consent 一经发布即为不可变快照；撤销操作不会就地修改字段，
 * 而是由仓储用 {@code compareAndSet} 原子替换为一个新的 REVOKED 快照。任何线程通过
 * 仓储读到的永远是"完整的旧快照"或"完整的新快照"，授权判定中途不会看到
 * 半更新状态。</p>
 *
 * @param consentId 同意标识
 * @param partnerId 归属合作方
 * @param scopes    客户授权的权限范围（不可变）
 * @param status    生命周期状态
 * @param createdAt 创建时间
 * @param expiresAt 有效期截止时间（UTC），判定"过期"的唯一依据
 * @param revokedAt 撤销时间；非撤销状态为 null
 */
public record Consent(
        String consentId,
        String partnerId,
        Set<PermissionScope> scopes,
        ConsentStatus status,
        Instant createdAt,
        Instant expiresAt,
        Instant revokedAt) {

    public Consent {
        scopes = scopes == null ? Set.of() : Set.copyOf(scopes);
    }

    /** 工厂：创建一个 AUTHORISED 同意。 */
    public static Consent authorised(String consentId, String partnerId,
                                     Set<PermissionScope> scopes, Instant createdAt, Instant expiresAt) {
        return new Consent(consentId, partnerId, scopes, ConsentStatus.AUTHORISED,
                createdAt, expiresAt, null);
    }

    /** 返回该同意在指定时刻是否仍有效（未撤销、未过期）。本方法不改变对象。 */
    public boolean effectiveAt(Instant now) {
        return status == ConsentStatus.AUTHORISED && now.isBefore(expiresAt);
    }

    /** 返回一个全新的 REVOKED 快照；原对象保持不变。 */
    public Consent asRevoked(Instant revokedAt) {
        return new Consent(consentId, partnerId, scopes, ConsentStatus.REVOKED,
                createdAt, expiresAt, revokedAt);
    }
}
