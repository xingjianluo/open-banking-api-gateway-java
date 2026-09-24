package com.github.xingjianluo.openbankingapigatewayjava.model.api;

import java.time.OffsetDateTime;

/**
 * 支付发起/查询响应。
 */
public record PaymentResponse(
        String paymentId,
        String consentId,
        String status,
        String creditorAccount,
        String amount,
        String currency,
        boolean replayed,
        OffsetDateTime createdAt
) {
}
