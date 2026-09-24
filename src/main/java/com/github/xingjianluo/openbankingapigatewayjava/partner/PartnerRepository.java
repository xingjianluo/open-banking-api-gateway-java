package com.github.xingjianluo.openbankingapigatewayjava.partner;

import com.github.xingjianluo.openbankingapigatewayjava.model.partner.Partner;
import java.util.Optional;

/** 本地内存合作方仓储。 */
public interface PartnerRepository {
    Optional<Partner> findById(String partnerId);
    Partner save(Partner partner);
}
