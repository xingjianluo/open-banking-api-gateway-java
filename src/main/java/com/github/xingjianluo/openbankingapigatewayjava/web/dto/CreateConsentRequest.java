package com.github.xingjianluo.openbankingapigatewayjava.web.dto;

import java.util.List;

/** 本地管理接口：创建同意。ttlSeconds 缺省为 3600。 */
public record CreateConsentRequest(
        String partnerId,
        List<String> scopes,
        String status,
        Long ttlSeconds
) {
}
