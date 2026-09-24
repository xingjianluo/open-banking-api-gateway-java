package com.github.xingjianluo.openbankingapigatewayjava.model.partner;

import com.github.xingjianluo.openbankingapigatewayjava.model.consent.ConsentScope;
import java.util.Set;

/**
 * 本地模拟的合作方（TPP）档案。
 */
public record Partner(
        String partnerId,
        String name,
        Set<ConsentScope> grantedScopes
) {
}
