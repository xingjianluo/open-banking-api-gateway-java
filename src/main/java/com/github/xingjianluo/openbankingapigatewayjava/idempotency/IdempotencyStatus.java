package com.github.xingjianluo.openbankingapigatewayjava.idempotency;

/**
 * 幂等记录生命周期。
 *
 * <ul>
 *   <li>{@link #IN_FLIGHT}：首个请求正在执行，副作用尚未落定；</li>
 *   <li>{@link #COMPLETED}：首个请求已完成，响应快照可用于确定性重放。</li>
 * </ul>
 */
public enum IdempotencyStatus {
    IN_FLIGHT,
    COMPLETED
}
