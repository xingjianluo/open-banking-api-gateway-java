package com.github.xingjianluo.openbankingapigatewayjava.idempotency;

/**
 * 幂等判定结论。
 *
 * <ul>
 *   <li>{@link #PROCEED}：本键首次出现（或原记录已超期并被清理），调用方获得执行权，
 *       副作用只允许在本次执行中产生一次；</li>
 *   <li>{@link #REPLAY}：同键、同内容的已完成请求，返回缓存的确定性结果，不重复执行；</li>
 *   <li>{@link #IN_FLIGHT}：同键请求正在并发处理，拒绝（IDEMPOTENCY_IN_FLIGHT）；</li>
 *   <li>{@link #CONFLICT}：同键但请求指纹不一致，拒绝（IDEMPOTENCY_CONFLICT）；</li>
 *   <li>{@link #KEY_EXPIRED}：键超过保留窗口被复用，拒绝（IDEMPOTENCY_KEY_EXPIRED）。</li>
 * </ul>
 */
public enum IdempotencyOutcome {
    PROCEED,
    REPLAY,
    IN_FLIGHT,
    CONFLICT,
    KEY_EXPIRED
}
