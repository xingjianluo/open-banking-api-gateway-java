package com.github.xingjianluo.openbankingapigatewayjava.consent;

/**
 * 同意（consent）生命周期状态。
 *
 * <ul>
 *   <li>{@link #AUTHORISED}：客户已授权，且在有效期内可访问；</li>
 *   <li>{@link #EXPIRED}：逻辑状态（由 expiresAt 判定，通常不落库），拒绝原因 CONSENT_EXPIRED；</li>
 *   <li>{@link #REVOKED}：被客户/合作方显式撤销的终态，拒绝原因 CONSENT_REVOKED。</li>
 * </ul>
 */
public enum ConsentStatus {
    AUTHORISED,
    EXPIRED,
    REVOKED
}
