package com.github.xingjianluo.openbankingapigatewayjava.model.api;

import java.math.BigDecimal;

/**
 * 支付发起请求（V2 规范）。
 * debtorName 为 V2 新增的可选字段，缺省时使用既有语义（空字符串表示未提供）。
 */
public record PaymentRequest(
        String consentId,
        String debtorAccount,
        String creditorAccount,
        BigDecimal amount,
        String currency,
        String debtorName
) {
}
