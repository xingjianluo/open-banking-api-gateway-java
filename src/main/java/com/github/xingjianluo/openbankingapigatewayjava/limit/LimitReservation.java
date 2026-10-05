package com.github.xingjianluo.openbankingapigatewayjava.limit;

import java.math.BigDecimal;

/**
 * 一次额度预占的句柄。
 *
 * <p>只有真正走到下游副作用的支付才持有预占；若后续环节失败（配额、下游异常等），
 * 由 {@link LimitService#release(LimitReservation)} 归还，保证"没有落账就不吃额度"。</p>
 *
 * @param consentId 预占所属的同意
 * @param amount    预占金额
 */
public record LimitReservation(String consentId, BigDecimal amount) {
}
