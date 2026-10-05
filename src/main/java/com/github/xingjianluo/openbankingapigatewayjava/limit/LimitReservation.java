package com.github.xingjianluo.openbankingapigatewayjava.limit;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 一次累计额度预占的句柄。
 *
 * <p>预占在判定通过时已经把 {@code amount} 计入用量；若后续环节（配额、下游调用）
 * 失败、副作用没有真正落账，必须调用 {@link #release()} 把预占退回，避免"被拒绝的
 * 请求白白吃掉额度"。释放是幂等的：多次调用只生效一次，并发释放也只扣一次。
 * 成功落账的请求不释放，预占即转为正式用量。</p>
 */
public final class LimitReservation {

    /** 未配置累计上限时使用的空句柄：不跟踪用量，释放无操作。 */
    static final LimitReservation NO_OP = new LimitReservation();

    private final LimitService owner;   // NO_OP 时为 null
    private final String consentId;     // NO_OP 时为 null
    private final BigDecimal amount;    // NO_OP 时为 null
    private final AtomicBoolean released = new AtomicBoolean(false);

    private LimitReservation() {
        this.owner = null;
        this.consentId = null;
        this.amount = null;
    }

    LimitReservation(LimitService owner, String consentId, BigDecimal amount) {
        this.owner = owner;
        this.consentId = consentId;
        this.amount = amount;
    }

    /** 退回预占（仅当后续副作用未落账时调用）；幂等，重复/并发调用只生效一次。 */
    public void release() {
        if (owner != null && released.compareAndSet(false, true)) {
            owner.doRelease(consentId, amount);
        }
    }

    /** 是否为真实预占（配置了累计上限才为 true）。 */
    public boolean isTracked() {
        return owner != null;
    }
}
