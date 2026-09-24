package com.github.xingjianluo.openbankingapigatewayjava.payment;

import com.github.xingjianluo.openbankingapigatewayjava.model.payment.PaymentRecord;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Repository;

@Repository
public class InMemoryPaymentRepository implements PaymentRepository {

    private final ConcurrentHashMap<String, PaymentRecord> store = new ConcurrentHashMap<>();

    @Override
    public PaymentRecord save(PaymentRecord record) {
        store.put(record.paymentId(), record);
        return record;
    }

    @Override
    public Optional<PaymentRecord> findById(String paymentId) {
        return Optional.ofNullable(store.get(paymentId));
    }
}
