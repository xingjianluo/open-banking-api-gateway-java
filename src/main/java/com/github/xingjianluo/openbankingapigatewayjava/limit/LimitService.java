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
 * 同意额度判定与累计用量台账（本地内存模拟）。
 *
 * <p>判定顺序固定，三种拒绝原因互不相同：</p>
 * <ol>
 *   <li>请求币种 ≠ 同意约定币种 → {@code CONSENT_CURRENCY_MISMATCH}；</li>
 *   <li>单笔金额 &gt; 单笔上限 → {@code LIMIT_PER_PAYMENT_EXCEEDED}；</li>
 *   <li>当前累计用量 + 本次金额 &gt; 累计上限 → {@code LIMIT_TOTAL_EXCEEDED}。</li>
 * </ol>
 *
 * <p><b>并发一致性：</b>每个同意一本台账（{@link AtomicReference} 持有的累计用量），
 * "读剩余额度-判定-累加"在该同意台账的监视器锁内一次完成：同一同意下的并发支付串行
 * 竞争额度，剩余额度只够一笔时只有一笔能预占成功，不会因同时读到到旧余额而一起通过；
 * 不同同意之间完全并行。额度上限取自授权阶段读到的同一个不可变 {@link Consent} 快照，
 * 调整额度/续期只会整体替换快照，进行中的请求要么按旧快照要么按新快照判定，
 * 不会读到"改到一半"的组合。</p>
 *
 * <p><b>只累计真实副作用：</b>预占发生在所有前置判定之后、下游调用之前；下游异常或
 * 后续环节失败时由 {@link #release(LimitReservation)} 全额归还。幂等重放在幂等层
 * 直接返回缓存结果，根本不进入本服务，不会重复累计。</p>
 */
@Service
public class LimitService {

    private static final Logger log = LoggerFactory.getLogger(LimitService.class);

    /** 每同意一本台账：值为当前累计已落账+已预占金额。 */
    private final ConcurrentMap<String, AtomicReference<BigDecimal>> ledgers = new ConcurrentHashMap<>();

    private AtomicReference<BigDecimal> ledger(String consentId) {
        return ledgers.computeIfAbsent(consentId, id -> new AtomicReference<>(BigDecimal.ZERO));
    }

    /**
     * 按同意快照做额度判定并原子预占累计用量。
     *
     * @param consent  授权阶段读到的不可变同意快照（额度上限的判定依据）
     * @param amount   本次支付金额（已校验 &gt; 0）
     * @param currency 本次支付币种
     * @return 预占句柄；同意未配置额度时返回 null（不做任何判定，行为同老版本）
     * @throws ApiException CONSENT_CURRENCY_MISMATCH / LIMIT_PER_PAYMENT_EXCEEDED /
     *                      LIMIT_TOTAL_EXCEEDED，三种原因互相区分
     */
    public LimitReservation reserve(Consent consent, BigDecimal amount, String currency) {
        if (!consent.hasLimits()) {
            log.info("额度判定 跳过 请求输入 consentId={} amount={} currency={} 判定依据=该同意未配置额度(老同意)，不做额度检查",
                    consent.consentId(), amount, currency);
            return null;
        }

        if (!consent.limitCurrency().equalsIgnoreCase(currency)) {
            log.info("额度拒绝 请求输入 consentId={} amount={} currency={} 判定依据=币种与约定不符 约定币种={}",
                    consent.consentId(), amount, currency, consent.limitCurrency());
            throw new ApiException(ErrorCode.CONSENT_CURRENCY_MISMATCH,
                    "请求币种 " + currency + " 与同意约定币种 " + consent.limitCurrency() + " 不一致");
        }

        if (amount.compareTo(consent.perPaymentLimit()) > 0) {
            log.info("额度拒绝 请求输入 consentId={} amount={} currency={} 判定依据=超过单笔上限 perPaymentLimit={}",
                    consent.consentId(), amount, currency, consent.perPaymentLimit());
            throw new ApiException(ErrorCode.LIMIT_PER_PAYMENT_EXCEEDED,
                    "单笔金额 " + amount + " 超过该同意单笔上限 " + consent.perPaymentLimit());
        }

        AtomicReference<BigDecimal> used = ledger(consent.consentId());
        synchronized (used) {
            BigDecimal current = used.get();
            BigDecimal after = current.add(amount);
            if (after.compareTo(consent.totalLimit()) > 0) {
                log.info("额度拒绝 请求输入 consentId={} amount={} currency={} 判定依据=累计额度不足 已用={} 占用后={} 累计上限={}",
                        consent.consentId(), amount, currency, current, after, consent.totalLimit());
                throw new ApiException(ErrorCode.LIMIT_TOTAL_EXCEEDED,
                        "本次占用后累计 " + after + " 将超过该同意累计上限 " + consent.totalLimit()
                                + "（当前已用 " + current + "）");
            }
            used.set(after);
            log.info("额度预占 请求输入 consentId={} amount={} currency={} 判定依据=占用后{} <= 累计上限{}",
                    consent.consentId(), amount, currency, after, consent.totalLimit());
        }
        return new LimitReservation(consent.consentId(), amount);
    }

    /**
     * 归还预占：副作用未真正发生（下游异常、后续环节拒绝）时调用，
     * 保证"没落账不吃额度"。重复归还是安全的（幂等键层保证每键只走一次执行路径，
     * 这里仍按句柄精确扣减，不会扣成负数之外的其他同意）。
     */
    public void release(LimitReservation reservation) {
        if (reservation == null) {
            return;
        }
        AtomicReference<BigDecimal> used = ledger(reservation.consentId());
        synchronized (used) {
            BigDecimal before = used.get();
            BigDecimal after = before.subtract(reservation.amount());
            if (after.signum() < 0) {
                // 防御：重复释放不应把台账扣成负数
                after = BigDecimal.ZERO;
            }
            used.set(after);
            log.info("额度归还 请求输入 consentId={} amount={} 判定依据=副作用未发生，归还预占 归还前={} 归还后={}",
                    reservation.consentId(), reservation.amount(), before, after);
        }
    }

    /** 查询某同意当前累计用量（含在途预占；管理接口/测试用）。 */
    public BigDecimal usedAmount(String consentId) {
        return ledger(consentId).get();
    }
}
