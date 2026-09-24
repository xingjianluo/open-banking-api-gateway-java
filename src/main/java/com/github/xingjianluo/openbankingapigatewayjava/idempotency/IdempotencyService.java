package com.github.xingjianluo.openbankingapigatewayjava.idempotency;

import java.time.Duration;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.github.xingjianluo.openbankingapigatewayjava.common.ApiException;
import com.github.xingjianluo.openbankingapigatewayjava.common.ErrorCode;

/**
 * 幂等提交与重放防护。
 *
 * <p>同一幂等键的"检查-插入"在其 stripe 锁内一次完成，因此并发提交中恰好只有一个请求
 * 拿到 {@link IdempotencyOutcome#PROCEED} 并产生副作用，其余并发请求拿到
 * {@link IdempotencyOutcome#IN_FLIGHT} 被明确拒绝（HTTP 409），不会静默排队或重复执行。
 * 首个请求完成后，同键同内容的重放得到 {@link IdempotencyOutcome#REPLAY}，
 * 返回缓存的确定性响应快照。</p>
 *
 * <p>拒绝规则：</p>
 * <ul>
 *   <li>同键但请求指纹不一致 → {@code IDEMPOTENCY_CONFLICT}（409），不覆盖原记录；</li>
 *   <li>同键被其他合作方占用 → 同样以 {@code IDEMPOTENCY_CONFLICT} 拒绝，不泄露归属；</li>
 *   <li>当前时间超过记录的保留截止 → {@code IDEMPOTENCY_KEY_EXPIRED}（410），
 *       拒绝复用，原记录保留不动。</li>
 * </ul>
 */
@Service
public class IdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);

    private final IdempotencyStore store;
    private final Duration retention;

    public IdempotencyService(
            IdempotencyStore store,
            @Value("${obg.idempotency.retention-seconds:86400}") long retentionSeconds) {
        this.store = store;
        this.retention = Duration.ofSeconds(retentionSeconds);
    }

    /**
     * 开启一次幂等判定。同键调用在分段锁内串行。
     *
     * @param key                请求头 Idempotency-Key
     * @param partnerId         调用方合作方标识（键归属校验）
     * @param requestFingerprint 请求内容指纹（同键必须同指纹）
     */
    public IdempotencyDecision begin(String key, String partnerId, String requestFingerprint) {
        Instant now = Instant.now();
        Object lock = store.lockFor(key);
        synchronized (lock) {
            IdempotencyRecord existing = store.get(key);
            if (existing == null) {
                IdempotencyRecord record = new IdempotencyRecord(
                        key, partnerId, requestFingerprint, now, now.plus(retention));
                store.putIfAbsent(key, record);
                log.info("幂等判定 请求输入 key={} partnerId={} fingerprint={} 判定依据=无既有记录，授予唯一执行权(PROCEED) expiresAt={}",
                        key, partnerId, requestFingerprint, record.expiresAt());
                return IdempotencyDecision.proceed(record);
            }

            if (!now.isBefore(existing.expiresAt())) {
                log.info("幂等拒绝 请求输入 key={} partnerId={} fingerprint={} 判定依据=幂等键已过保留期 now={} expiresAt={}",
                        key, partnerId, requestFingerprint, now, existing.expiresAt());
                return IdempotencyDecision.reject(IdempotencyOutcome.KEY_EXPIRED, existing);
            }
            if (!existing.partnerId().equals(partnerId)) {
                log.info("幂等拒绝 请求输入 key={} partnerId={} fingerprint={} 判定依据=幂等键归属其他合作方",
                        key, partnerId, requestFingerprint);
                return IdempotencyDecision.reject(IdempotencyOutcome.CONFLICT, existing);
            }
            if (!existing.requestFingerprint().equals(requestFingerprint)) {
                log.info("幂等拒绝 请求输入 key={} partnerId={} fingerprint={} 判定依据=请求指纹不一致 原指纹={}",
                        key, partnerId, requestFingerprint, existing.requestFingerprint());
                return IdempotencyDecision.reject(IdempotencyOutcome.CONFLICT, existing);
            }
            if (existing.status() == IdempotencyStatus.IN_FLIGHT) {
                log.info("幂等拒绝 请求输入 key={} partnerId={} fingerprint={} 判定依据=首个请求仍在处理中(IN_FLIGHT)",
                        key, partnerId, requestFingerprint);
                return IdempotencyDecision.reject(IdempotencyOutcome.IN_FLIGHT, existing);
            }
            log.info("幂等重放 请求输入 key={} partnerId={} fingerprint={} 判定依据=同键同内容已完成(COMPLETED)，返回缓存结果 completedAt={}",
                    key, partnerId, requestFingerprint, existing.completedAt());
            return IdempotencyDecision.replay(existing);
        }
    }

    /** 首个请求执行结束，写入确定性响应快照（成功或映射后的业务失败都写入）。 */
    public void complete(IdempotencyRecord record, int httpStatus, Object body) {
        record.complete(httpStatus, body, Instant.now());
        log.info("幂等记录完成 key={} httpStatus={} completedAt={}",
                record.key(), httpStatus, record.completedAt());
    }

    /** 把拒绝类判定转换为稳定的业务异常。 */
    public ApiException rejectException(IdempotencyDecision decision) {
        IdempotencyRecord record = decision.record();
        return switch (decision.outcome()) {
            case IN_FLIGHT -> new ApiException(ErrorCode.IDEMPOTENCY_IN_FLIGHT,
                    "Idempotency-Key=" + record.key() + " 的首个请求尚未完成");
            case CONFLICT -> new ApiException(ErrorCode.IDEMPOTENCY_CONFLICT,
                    "Idempotency-Key=" + record.key() + " 与首次请求内容不一致或归属不符");
            case KEY_EXPIRED -> new ApiException(ErrorCode.IDEMPOTENCY_KEY_EXPIRED,
                    "Idempotency-Key=" + record.key() + " 保留截止 " + record.expiresAt()
                            + "，请更换新键");
            default -> new ApiException(ErrorCode.INTERNAL_ERROR, "非预期的幂等判定: " + decision.outcome());
        };
    }
}
