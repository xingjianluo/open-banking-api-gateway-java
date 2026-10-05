package com.github.xingjianluo.openbankingapigatewayjava.limit;

import java.math.BigDecimal;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.github.xingjianluo.openbankingapigatewayjava.common.ApiException;
import com.github.xingjianluo.openbankingapigatewayjava.common.ErrorCode;
import com.github.xingjianluo.openbankingapigatewayjava.consent.Consent;

/**
 * 同意额度判定与累计用量记账。
 *
 * <p>判定基于调用方传入的<b>同一份不可变同意快照</b>（与授权判定同源），因此一次请求
 * 内部看到的单笔上限、累计上限、币种必然来自同一个完整状态，不会读到调额改到一半的
 * 中间状态。固定判定顺序，三种拒绝原因互不相同：</p>
 * <ol>
 *   <li>约定了币种且请求币种不一致 → {@code CURRENCY_MISMATCH}；</li>
 *   <li>约定了单笔上限且金额超过 → {@code PAYMENT_LIMIT_EXCEEDED}；</li>
 *   <li>约定了累计上限且"已用+本次"超过 → {@code CUMULATIVE_LIMIT_EXCEEDED}。</li>
 * </ol>
 *
 * <p><b>并发不透支：</b>累计用量是按同意维护的 {@link AtomicReference}，"读取已用 →
 * 校验不超上限 → 写入新已用"通过 CAS 循环一次完成。剩余额度只够一笔时，并发请求中
 * 只有一个 CAS 成功，其余在重试中读到新余额并被拒绝——不会因为同时读到旧余额而一起
 * 通过。调额/续期替换的是同意快照，用量本身不受影响；某笔支付相对它读到的那个快照
 * 给出的结论是确定的。</p>
 *
 * <p><b>只计真实副作用：</b>判定通过即预占（计入用量）；后续环节失败（配额、下游异常）
 * 由 {@link LimitReservation#release()} 幂等退回。被拒绝的请求在预占前就已抛出，
 * 幂等重放根本不会走到这里，两者都不消耗额度。</p>
 */
@Service
public class LimitService {

    private static final Logger log = LoggerFactory.getLogger(LimitService.class);

    /** 每个同意的累计已用额度（仅对配置了累计上限的同意创建条目）。 */
    private final ConcurrentMap<String, AtomicReference<BigDecimal>> usage = new ConcurrentHashMap<>();

    /**
     * 按同意快照判定额度，通过则原子预占本次金额。
     *
     * @param consent  与本次授权判定同源的快照（其额度字段即判定依据）
     * @param amount   本次支付金额（已校验为正数）
     * @param currency 请求币种
     * @return 预占句柄；后续副作用未落账时必须 {@link LimitReservation#release()}
     * @throws ApiException CURRENCY_MISMATCH / PAYMENT_LIMIT_EXCEEDED / CUMULATIVE_LIMIT_EXCEEDED
     */
    public LimitReservation reserve(Consent consent, BigDecimal amount, String currency) {
        if (!consent.hasLimits()) {
            // 未配置任何额度约束：行为与引入额度能力之前完全一致
            return LimitReservation.NO_OP;
        }

        if (consent.limitCurrency() != null
                && !consent.limitCurrency().equalsIgnoreCase(currency)) {
            log.info("额度拒绝 请求输入 consentId={} amount={} currency={} 判定依据=币种与约定不符 约定币种={}",
                    consent.consentId(), amount, currency, consent.limitCurrency());
            throw new ApiException(ErrorCode.CURRENCY_MISMATCH,
                    "consentId=" + consent.consentId() + " 约定币种 " + consent.limitCurrency()
                            + "，请求币种 " + currency);
        }

        if (consent.perPaymentLimit() != null
                && amount.compareTo(consent.perPaymentLimit()) > 0) {
            log.info("额度拒绝 请求输入 consentId={} amount={} currency={} 判定依据=超过单笔上限 单笔上限={}",
                    consent.consentId(), amount, currency, consent.perPaymentLimit());
            throw new ApiException(ErrorCode.PAYMENT_LIMIT_EXCEEDED,
                    "consentId=" + consent.consentId() + " 单笔上限 " + consent.perPaymentLimit()
                            + "，本次金额 " + amount);
        }

        if (consent.cumulativeLimit() == null) {
            // 只配置了单笔/币种约束：无需跟踪累计用量
            return LimitReservation.NO_OP;
        }

        AtomicReference<BigDecimal> usedRef = usage.computeIfAbsent(consent.consentId(),
                id -> new AtomicReference<>(BigDecimal.ZERO));
        while (true) {
            BigDecimal used = usedRef.get();
            BigDecimal after = used.add(amount);
            if (after.compareTo(consent.cumulativeLimit()) > 0) {
                log.info("额度拒绝 请求输入 consentId={} amount={} currency={} 判定依据=占用后{}超过累计上限{} 当前已用={}",
                        consent.consentId(), amount, currency, after, consent.cumulativeLimit(), used);
                throw new ApiException(ErrorCode.CUMULATIVE_LIMIT_EXCEEDED,
                        "consentId=" + consent.consentId() + " 累计上限 " + consent.cumulativeLimit()
                                + "，已用 " + used + "，本次 " + amount + " 将超出");
            }
            if (usedRef.compareAndSet(used, after)) {
                log.info("额度预占 请求输入 consentId={} amount={} currency={} 判定依据=占用后{}/{}未超累计上限",
                        consent.consentId(), amount, currency, after, consent.cumulativeLimit());
                return new LimitReservation(this, consent.consentId(), amount);
            }
            // CAS 失败：有并发占用，重读余额重试（剩余不足时将在下一轮被拒绝）
        }
    }

    /** 退回预占：仅由 {@link LimitReservation#release()} 调用。 */
    void doRelease(String consentId, BigDecimal amount) {
        AtomicReference<BigDecimal> usedRef = usage.get(consentId);
        if (usedRef == null) {
            return;
        }
        while (true) {
            BigDecimal used = usedRef.get();
            BigDecimal restored = used.subtract(amount);
            if (usedRef.compareAndSet(used, restored)) {
                log.info("额度退回 consentId={} 退回金额={} 退回后已用={}", consentId, amount, restored);
                return;
            }
        }
    }

    /** 当前累计已用额度（未配置/未发生过占用时为 0）。 */
    public BigDecimal used(String consentId) {
        AtomicReference<BigDecimal> usedRef = usage.get(consentId);
        return usedRef == null ? BigDecimal.ZERO : usedRef.get();
    }

    /** 重置累计用量（本地管理接口/演示用）。 */
    public void resetUsed(String consentId) {
        AtomicReference<BigDecimal> usedRef = usage.get(consentId);
        if (usedRef != null) {
            usedRef.set(BigDecimal.ZERO);
        }
        log.info("额度用量重置 consentId={}", consentId);
    }
}
