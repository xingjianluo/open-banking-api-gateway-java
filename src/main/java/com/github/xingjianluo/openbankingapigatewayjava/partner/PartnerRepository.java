package com.github.xingjianluo.openbankingapigatewayjava.partner;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.springframework.stereotype.Repository;

/**
 * 合作方本地仓储：进程内 {@link ConcurrentHashMap} 模拟合作方注册表。
 */
@Repository
public class PartnerRepository {

    private final ConcurrentMap<String, Partner> partners = new ConcurrentHashMap<>();

    public void save(Partner partner) {
        partners.put(partner.partnerId(), partner);
    }

    public Optional<Partner> findById(String partnerId) {
        if (partnerId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(partners.get(partnerId));
    }
}
