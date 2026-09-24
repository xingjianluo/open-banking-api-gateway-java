package com.github.xingjianluo.openbankingapigatewayjava.quota;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.github.xingjianluo.openbankingapigatewayjava.common.ApiException;
import com.github.xingjianluo.openbankingapigatewayjava.common.ErrorCode;

/**
 * 合作方调用配额（本地内存模拟）。
 *
 * <p>每个合作方一个原子计数器与配额上限；只有"实际执行副作用"的请求才消耗配额，
 * 幂等重放与被拒绝请求不计费，避免重试放大合作方成本。超限映射为稳定的
 * {@code QUOTA_EXCEEDED} / HTTP 429，本地管理接口可调整上限或重置计数以便演示。</p>
 */
@Service
public class QuotaService {

    private static final Logger log = LoggerFactory.getLogger(QuotaService.class);

    private record Bucket(AtomicInteger used, AtomicInteger limit) {
    }

    private final ConcurrentMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    private Bucket bucket(String partnerId) {
        return buckets.computeIfAbsent(partnerId,
                id -> new Bucket(new AtomicInteger(0), new AtomicInteger(Integer.MAX_VALUE)));
    }

    /** 设置合作方配额上限（本地管理接口/演示数据使用）。 */
    public void configureLimit(String partnerId, int limit) {
        bucket(partnerId).limit().set(limit);
    }

    /** 重置已用计数（本地管理接口使用）。 */
    public void resetUsed(String partnerId) {
        bucket(partnerId).used().set(0);
    }

    public int used(String partnerId) {
        return bucket(partnerId).used().get();
    }

    public int limit(String partnerId) {
        return bucket(partnerId).limit().get();
    }

    /**
     * 尝试消耗一次配额。
     *
     * @throws ApiException QUOTA_EXCEERED 当已用计数已达上限
     */
    public void acquire(String partnerId) {
        Bucket b = bucket(partnerId);
        int current = b.used().getAndIncrement();
        if (current >= b.limit().get()) {
            // 回滚预占，保持计数语义为"成功获得配额的次数"
            b.used().decrementAndGet();
            log.warn("配额拒绝 请求输入 partnerId={} 判定依据=已用{} >= 上限{}",
                    partnerId, current, b.limit().get());
            throw new ApiException(ErrorCode.QUOTA_EXCEEDED,
                    "合作方 " + partnerId + " 配额已用尽（上限 " + b.limit().get() + "），请稍后重试或申请扩容");
        }
        log.info("配额通过 请求输入 partnerId={} 判定依据=占用后计数{}/{}",
                partnerId, current + 1, b.limit().get());
    }

    /** 供管理接口查看全部合作方配额快照。 */
    public Map<String, int[]> snapshot() {
        ConcurrentMap<String, int[]> view = new ConcurrentHashMap<>();
        buckets.forEach((id, b) -> view.put(id, new int[]{b.used().get(), b.limit().get()}));
        return Map.copyOf(view);
    }
}
