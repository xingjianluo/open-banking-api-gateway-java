package com.github.xingjianluo.openbankingapigatewayjava.idempotency;

/**
 * 幂等处理结果：首次执行或命中历史结果（重放），二者均携带确定的结果。
 */
public record IdempotencyOutcome<T>(
        boolean firstExecution,
        T result,
        boolean replayed
) {
    public static <T> IdempotencyOutcome<T> first(T result) {
        return new IdempotencyOutcome<>(true, result, false);
    }

    public static <T> IdempotencyOutcome<T> replay(T cachedResult) {
        return new IdempotencyOutcome<>(false, cachedResult, true);
    }
}
