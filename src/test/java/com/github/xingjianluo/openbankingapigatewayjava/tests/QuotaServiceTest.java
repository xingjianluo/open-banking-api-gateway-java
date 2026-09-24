package com.github.xingjianluo.openbankingapigatewayjava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.xingjianluo.openbankingapigatewayjava.common.ApiException;
import com.github.xingjianluo.openbankingapigatewayjava.common.ErrorCode;
import com.github.xingjianluo.openbankingapigatewayjava.quota.QuotaService;

/**
 * 配额单元测试。
 */
class QuotaServiceTest {

    private static final Logger log = LoggerFactory.getLogger(QuotaServiceTest.class);

    @Test
    @DisplayName("达到上限后拒绝 QUOTA_EXCEEDED，拒绝不占用计数；重置后恢复")
    void quotaExceededAndReset() {
        QuotaService quota = new QuotaService();
        quota.configureLimit("p1", 2);
        log.info("[用例输入] partner=p1 上限=2，连续 acquire 三次");

        quota.acquire("p1");
        quota.acquire("p1");
        ApiException ex = assertThrows(ApiException.class, () -> quota.acquire("p1"));
        assertEquals(ErrorCode.QUOTA_EXCEEDED, ex.errorCode());
        assertEquals(2, quota.used("p1"));
        log.info("[判定依据] errorCode={} used 保持=2（拒绝不消耗）", ex.errorCode());

        quota.resetUsed("p1");
        quota.acquire("p1");
        assertEquals(1, quota.used("p1"));
        log.info("[判定依据] 重置后恢复，used=1");
    }
}
