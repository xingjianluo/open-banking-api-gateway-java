package com.github.xingjianluo.openbankingapigatewayjava.consent;

import com.github.xingjianluo.openbankingapigatewayjava.model.consent.Consent;
import java.util.Optional;

/** 本地内存同意仓储。 */
public interface ConsentRepository {
    Optional<Consent> findById(String consentId);
    Consent save(Consent consent);
}
