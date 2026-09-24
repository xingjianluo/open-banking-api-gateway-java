package com.github.xingjianluo.openbankingapigatewayjava.idempotency;

import java.time.Instant;

/**
 * 一个幂等键的处理记录。
 *
 * <p>状态迁移为 {@code IN_FLIGHT -> COMPLETED}，迁移后保存首个请求的响应快照
 * （HTTP 状态码 + 响应体），供重放请求获得<b>确定的同一结果</b>。
 * {@code expiresAt} 是幂等键的保留截止时间；超过后复用该键必须拒绝
 * （{@code IDEMPOTENCY_KEY_EXPIRED}），而不是静默覆盖。</p>
 */
public final class IdempotencyRecord {

    private final String key;
    private final String partnerId;
    private final String requestFingerprint;
    private final Instant createdAt;
    private final Instant expiresAt;

    private volatile IdempotencyStatus status;
    private volatile int responseStatus;
    private volatile Object responseBody;
    private volatile Instant completedAt;

    public IdempotencyRecord(String key, String partnerId, String requestFingerprint,
                             Instant createdAt, Instant expiresAt) {
        this.key = key;
        this.partnerId = partnerId;
        this.requestFingerprint = requestFingerprint;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
        this.status = IdempotencyStatus.IN_FLIGHT;
    }

    /** 首个请求完成，写入确定性响应快照。仅允许迁移一次。 */
    public synchronized void complete(int responseStatus, Object responseBody, Instant completedAt) {
        if (this.status == IdempotencyStatus.COMPLETED) {
            return;
        }
        this.responseStatus = responseStatus;
        this.responseBody = responseBody;
        this.completedAt = completedAt;
        this.status = IdempotencyStatus.COMPLETED;
        this.notifyAll();
    }

    public String key() {
        return key;
    }

    public String partnerId() {
        return partnerId;
    }

    public String requestFingerprint() {
        return requestFingerprint;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant expiresAt() {
        return expiresAt;
    }

    public IdempotencyStatus status() {
        return status;
    }

    public int responseStatus() {
        return responseStatus;
    }

    public Object responseBody() {
        return responseBody;
    }

    public Instant completedAt() {
        return completedAt;
    }
}
