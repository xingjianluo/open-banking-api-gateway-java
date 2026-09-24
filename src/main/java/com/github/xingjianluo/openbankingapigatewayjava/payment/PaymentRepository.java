package com.github.xingjianluo.openbankingapigatewayjava.payment;

import com.github.xingjianluo.openbankingapigatewayjava.model.payment.PaymentRecord;
import java.util.Optional;

/** 支付记录仓储。 */
public interface PaymentRepository {
    PaymentRecord save(PaymentRecord record);
    Optional<PaymentRecord> findById(String paymentId);
}
