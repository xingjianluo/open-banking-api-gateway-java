package com.github.xingjianluo.openbankingapigatewayjava.payment;

import java.math.BigDecimal;

/**
 * 支付发起请求体。
 *
 * <p>版本兼容：{@code priority} 是 v2 新增的<b>可选</b>字段；v1 请求缺省该字段时，
 * 服务端赋予默认值 {@code NORMAL}，语义与 v1 完全一致（新增字段不改变既有行为）。
 * 未知字段由 Jackson 容忍读取（tolerant reader），不会导致老请求失败。</p>
 *
 * @param paymentRef       合作方侧业务参考号
 * @param amount           金额
 * @param currency         币种（ISO 4217）
 * @param debtorAccountId  付款账户标识
 * @param creditorAccountId 收款账户标识
 * @param priority         v2 新增：优先级 NORMAL/HIGH；缺省 NORMAL
 */
public record PaymentRequest(
        String paymentRef,
        BigDecimal amount,
        String currency,
        String debtorAccountId,
        String creditorAccountId,
        String priority) {

    /** 缺省优先级，v1 请求与 v2 未填该字段的请求均使用此值。 */
    public String effectivePriority() {
        return (priority == null || priority.isBlank()) ? "NORMAL" : priority.trim().toUpperCase();
    }
}
