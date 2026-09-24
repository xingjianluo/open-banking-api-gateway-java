package com.github.xingjianluo.openbankingapigatewayjava.partner;

import com.github.xingjianluo.openbankingapigatewayjava.model.partner.Partner;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Repository;

@Repository
public class InMemoryPartnerRepository implements PartnerRepository {

    private final ConcurrentHashMap<String, Partner> store = new ConcurrentHashMap<>();

    @Override
    public Optional<Partner> findById(String partnerId) {
        return Optional.ofNullable(store.get(partnerId));
    }

    @Override
    public Partner save(Partner partner) {
        store.put(partner.partnerId(), partner);
        return partner;
    }
}
