package com.github.xingjianluo.openbankingapigatewayjava.quota;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 合作方配额控制（固定窗口，本地内存模拟）。
 * 配额检查与扣减在同一原子操作内完成；由幂等动作内部调用，
 * 因此重放请求不会重复扣减配额；超限映射为 QUOTA_EXCEEDED。
 */
@Service
public class QuotaService {

    private static final Logger log = LoggerFactory.getLogger(QuotaService.class);

    private final int limit;
    private final Duration window;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    public QuotaService(@Value("${obg.quota.limit:5}") int limit,
                        @Value("${obg.quota.window-seconds:60}") long windowSeconds) {
        this.limit = limit;
        this.window = Duration.ofSeconds(windowSeconds);
    }

    /** 测试/演示用构造器。 */
    public QuotaService(int limit, Duration window) {
        this.limit = limit;
        this.window = window;
    }

    /**
     * 原子地检查并消耗一次配额；不足时抛出 QUOTA_EXCEEDED（不扣减）。
     */
    public void checkAndConsume(String partnerId) {
        long now = System.currentTimeMillis();
        long windowMillis = window.toMillis();
        boolean[] allowed = {false};
        windows.compute(partnerId, (id, current) -> {
            long currentWindowStart = current == null ? Long.MIN_VALUE : current.windowStart;
            if (current == null || now - currentWindowStart >= windowMillis) {
                current = new Window(now - (now % windowMillis), 0);
            }
            if (current.used < limit) {
                current = new Window(current.windowStart, current.used + 1);
                allowed[0] = true;
            }
            return current;
        });
        if (!allowed[0]) {
            log.warn("配额判定: partnerId={} 窗口配额 {} 已用尽 -> QUOTA_EXCEEDED", partnerId, limit);
            throw new com.github.xingjianluo.openbankingapigatewayjava.error.ApiException(
                    com.github.xingjianluo.openbankingapigatewayjava.error.ErrorCode.QUOTA_EXCEEDED,
                    "partnerId=" + partnerId);
        }
        log.info("配额判定: partnerId={} 消耗一次配额成功（上限 {}，窗口 {}s）", partnerId, limit, window.toSeconds());
    }

    /** 单个固定窗口的不可变快照（配合 compute 原子替换）。 */
    private static final class Window {
        private final long windowStart;
        private final int used;

        private Window(long windowStart, int used) {
            this.windowStart = windowStart;
            this.used = used;
        }
    }
}
