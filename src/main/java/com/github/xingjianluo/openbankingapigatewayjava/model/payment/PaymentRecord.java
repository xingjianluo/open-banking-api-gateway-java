package com.github.xingjianluo.openbankingapigatewayjava.model.payment;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 已完成支付的本地记录（幂等动作成功后写入），供重放返回与支付查询使用。
 */
public record PaymentRecord(
        String paymentId,
        String partnerId,
        String consentId,
        String creditorAccount,
        BigDecimal amount,
        String currency,
        String status,
        OffsetDateTime createdAt
) {
}
