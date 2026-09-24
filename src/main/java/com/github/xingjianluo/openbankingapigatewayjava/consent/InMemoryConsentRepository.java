package com.github.xingjianluo.openbankingapigatewayjava.consent;

import com.github.xingjianluo.openbankingapigatewayjava.model.consent.Consent;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Repository;

@Repository
public class InMemoryConsentRepository implements ConsentRepository {

    private final ConcurrentHashMap<String, Consent> store = new ConcurrentHashMap<>();

    @Override
    public Optional<Consent> findById(String consentId) {
        return Optional.ofNullable(store.get(consentId));
    }

    @Override
    public Consent save(Consent consent) {
        store.put(consent.getConsentId(), consent);
        return consent;
    }
}
