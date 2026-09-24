package com.github.xingjianluo.openbankingapigatewayjava.model.consent;

import java.time.OffsetDateTime;
import java.util.Set;

/**
 * 本地模拟的合作方同意授权对象。
 */
public final class Consent {

    private final String consentId;
    private final String partnerId;
    private final Set<ConsentScope> scopes;
    private final OffsetDateTime expiresAt;
    private volatile ConsentStatus status;

    public Consent(String consentId, String partnerId, Set<ConsentScope> scopes,
                   ConsentStatus status, OffsetDateTime expiresAt) {
        this.consentId = consentId;
        this.partnerId = partnerId;
        this.scopes = Set.copyOf(scopes);
        this.status = status;
        this.expiresAt = expiresAt;
    }

    public String getConsentId() {
        return consentId;
    }

    public String getPartnerId() {
        return partnerId;
    }

    public Set<ConsentScope> getScopes() {
        return scopes;
    }

    public OffsetDateTime getExpiresAt() {
        return expiresAt;
    }

    public ConsentStatus getStatus() {
        return status;
    }

    public void setStatus(ConsentStatus status) {
        this.status = status;
    }
}
