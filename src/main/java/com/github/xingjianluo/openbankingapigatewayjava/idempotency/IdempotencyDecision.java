package com.github.xingjianluo.openbankingapigatewayjava.idempotency;

/**
 * 幂等判定结果。
 *
 * @param outcome 判定结论
 * @param record  关联的幂等记录（PROCEED 时为本次新建记录；REPLAY/拒绝时为既有记录；
 *                不会为 null 的场景由 {@link IdempotencyService} 保证）
 */
public record IdempotencyDecision(IdempotencyOutcome outcome, IdempotencyRecord record) {

    public static IdempotencyDecision proceed(IdempotencyRecord record) {
        return new IdempotencyDecision(IdempotencyOutcome.PROCEED, record);
    }

    public static IdempotencyDecision replay(IdempotencyRecord record) {
        return new IdempotencyDecision(IdempotencyOutcome.REPLAY, record);
    }

    public static IdempotencyDecision reject(IdempotencyOutcome outcome, IdempotencyRecord record) {
        return new IdempotencyDecision(outcome, record);
    }
}
