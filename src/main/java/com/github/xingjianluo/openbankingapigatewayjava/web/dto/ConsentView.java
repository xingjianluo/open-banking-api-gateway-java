package com.github.xingjianluo.openbankingapigatewayjava.web.dto;

import java.time.OffsetDateTime;
import java.util.Set;

/** 同意视图。 */
public record ConsentView(
        String consentId,
        String partnerId,
        Set<String> scopes,
        String status,
        OffsetDateTime expiresAt
) {
}
