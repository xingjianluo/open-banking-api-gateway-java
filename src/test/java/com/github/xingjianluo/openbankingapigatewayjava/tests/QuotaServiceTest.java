package com.github.xingjianluo.openbankingapigatewayjava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.github.xingjianluo.openbankingapigatewayjava.error.ApiException;
import com.github.xingjianluo.openbankingapigatewayjava.error.ErrorCode;
import com.github.xingjianluo.openbankingapigatewayjava.quota.QuotaService;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 配额测试：窗口内超限拒绝 QUOTA_EXCEEDED，不同合作方独立计数，窗口滚动后恢复。 */
class QuotaServiceTest {

    private static final Logger log = LoggerFactory.getLogger(QuotaServiceTest.class);

    @Test
    @DisplayName("达到上限后拒绝 QUOTA_EXCEEDED，且超限不扣减；不同合作方独立")
    void quotaEnforced_perPartner() {
        QuotaService quota = new QuotaService(3, Duration.ofMinutes(1));
        log.info("[配额] 输入: limit=3，partner-a 连续 4 次，partner-b 1 次");
        for (int i = 0; i < 3; i++) {
            quota.checkAndConsume("partner-a");
        }
        ApiException ex = assertThrows(ApiException.class, () -> quota.checkAndConsume("partner-a"));
        assertEquals(ErrorCode.QUOTA_EXCEEDED, ex.getErrorCode());
        // 超限拒绝不消耗配额：换窗口前仍稳定拒绝
        assertThrows(ApiException.class, () -> quota.checkAndConsume("partner-a"));
        // 另一合作方不受影响
        quota.checkAndConsume("partner-b");
        log.info("[配额] 判定依据: partner-a 已用=3 上限=3 -> {}; partner-b 独立计数放行",
                ErrorCode.QUOTA_EXCEEDED.code());
    }

    @Test
    @DisplayName("窗口滚动后配额恢复")
    void quotaResets_afterWindow() throws InterruptedException {
        QuotaService quota = new QuotaService(1, Duration.ofMillis(80));
        log.info("[配额] 输入: limit=1 window=80ms");
        quota.checkAndConsume("p");
        assertThrows(ApiException.class, () -> quota.checkAndConsume("p"));
        Thread.sleep(100);
        quota.checkAndConsume("p");
        log.info("[配额] 判定依据: 超过窗口长度后开启新窗口，再次放行");
    }
}
