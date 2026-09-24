package com.github.xingjianluo.openbankingapigatewayjava.downstream;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 本地模拟的下游银行核心：真正产生“副作用”的地方。
 * 仅当本类被实际调用时 {@code invocationCount} 才会递增，
 * 因此幂等并发测试可据此断言“只产生一次副作用”。
 * 当币种为 ERR 时模拟下游故障，供异常映射测试使用。
 */
@Component
public class BankClient {

    private static final Logger log = LoggerFactory.getLogger(BankClient.class);

    private final AtomicLong paymentSequence = new AtomicLong();
    private final AtomicLong invocationCount = new AtomicLong();

    /**
     * 发起支付（有副作用）。
     *
     * @return 下游生成的支付 ID
     * @throws DownstreamException 币种为 ERR 时模拟下游异常（内部细节，不外泄）
     */
    public String initiatePayment(String creditorAccount, BigDecimal amount, String currency) {
        invocationCount.incrementAndGet();
        log.info("下游银行处理: creditorAccount={}, amount={}, currency={}, invocation#{}",
                creditorAccount, amount, currency, invocationCount.get());
        if ("ERR".equalsIgnoreCase(currency)) {
            // 内部细节异常：SQLSTATE/堆栈等绝不能透出给合作方
            throw new DownstreamException("core-ledger timeout [segment=42, ref=INTERNAL-7F3]");
        }
        long seq = paymentSequence.incrementAndGet();
        return "PAY-" + String.format("%010d", seq);
    }

    public long getInvocationCount() {
        return invocationCount.get();
    }
}
