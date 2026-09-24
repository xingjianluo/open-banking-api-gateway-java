package com.github.xingjianluo.openbankingapigatewayjava.payment;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.springframework.stereotype.Repository;

/**
 * 支付资源本地仓储（进程内存模拟资源状态）。
 */
@Repository
public class PaymentRepository {

    private final ConcurrentMap<String, PaymentResponse> payments = new ConcurrentHashMap<>();

    public PaymentResponse save(PaymentResponse payment) {
        payments.put(payment.paymentId(), payment);
        return payment;
    }

    public Optional<PaymentResponse> findById(String paymentId) {
        if (paymentId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(payments.get(paymentId));
    }

    public long count() {
        return payments.size();
    }
}
